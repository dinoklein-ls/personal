package com.loanstreet.accounting.web.analytics.experian.buyerhealth

import com.launchdarkly.sdk.server.LDClient
import com.loanstreet.DefaultSystemClockConfig
import com.loanstreet.accounting.helios.table.heliosTables
import com.loanstreet.accounting.institution.model.AccInstitutionId
import com.loanstreet.accounting.legacy.analytics.generateWholeDealSnapshots
import com.loanstreet.accounting.legacy.getLoanService
import com.loanstreet.accounting.legacy.getObligorService
import com.loanstreet.accounting.legacy.insertDeal
import com.loanstreet.accounting.legacy.insertLegacyInstitution
import com.loanstreet.accounting.legacy.insertLoan
import com.loanstreet.accounting.legacy.insertLoanPerformance
import com.loanstreet.accounting.legacy.insertLoanStatus
import com.loanstreet.accounting.legacy.insertParticipation
import com.loanstreet.accounting.legacy.insertPool
import com.loanstreet.accounting.legacy.insertPrincipalAvailablePayment
import com.loanstreet.accounting.legacy.report.enums.StatusType
import com.loanstreet.accounting.legacy.table.primaryTables
import com.loanstreet.accounting.legacy.view.LoansLoanView
import com.loanstreet.accounting.lib.Percentage
import com.loanstreet.accounting.lib.money.Money
import com.loanstreet.accounting.loan.database.loanStaticEntityId
import com.loanstreet.accounting.loan.model.LoanId
import com.loanstreet.accounting.platform.insertUser
import com.loanstreet.accounting.test.EmptyDBContainer
import com.loanstreet.accounting.test.rebuildDatabaseWithTables
import com.loanstreet.accounting.test.refreshMaterializedViews
import com.loanstreet.accounting.test.truncateTables
import com.loanstreet.accounting.web.analytics.experian.ExperianPresence
import com.loanstreet.accounting.web.analytics.experian.insertExperianRow
import com.loanstreet.accounting.web.analytics.experian.insertSsnHash
import com.loanstreet.accounting.web.analytics.filtering.DealQueryBuilder
import com.ninjasquad.springmockk.MockkBean
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises the buyer health SQL in [BuyerHealthAggregateService] against snapshots produced by
 * the real snapshot pipeline from basic loan data — institutions, loans, participations,
 * payments, and reported state changes — rather than hand-inserted snapshot rows.
 *
 * The schema is built once for the class; each test then truncates every table and seeds a
 * single deal describing its own scenario — institutions, pool, deal, loans, participations,
 * and the Experian dataset; every snapshot table is then populated by
 * [generateWholeDealSnapshots].
 *
 * The generator produces monthly periods from a deal's first closing through the requested last
 * period, so a sale in September 2023 with a December 2023 last period yields exactly the four
 * periods the calculation needs: T0 is December and the reference period three months prior
 * (T3) is September.
 *
 * The seeded loans have distinct origination dates, principals, and interest rates, each paying
 * principal every period — except after a loan turns unhealthy, when its payments stop. Every
 * participation carries the same ownership share, so that factor cancels in the health ratios,
 * and expected values follow from plain loan-level balances: original principal minus the
 * principal paid by the relevant period boundary.
 *
 * Buyer health is the share of principal held by spotless borrowers: a borrower is spotless per
 * Experian when every trade has been clean (ALL7330 = 100) across at least three trades
 * (ALL0300 >= 3), and Loan Street indicators — delinquency, repossession, bankruptcy, charge-off
 * — override a spotless classification. A spotless loan contributes its close principal to the
 * numerator and the denominator; any other loan contributes its close principal to the
 * denominator only, except a loan charging off within the period, which contributes its open
 * principal. The headline and the delta are calculated over two populations — every
 * participation that started T0 active versus the subset with four periods of history — so the
 * expected values below spell out both sides where they differ.
 *
 * Rates are rounded to 8 decimals; the delta is the difference of the unrounded rates, rounded
 * to 8 decimals. The loan count is the distinct count of loans in the headline population that
 * did not charge off within T0, exclusion per Experian notwithstanding.
 */
@Testcontainers
@ExtendWith(SpringExtension::class)
@Import(
    BuyerHealthConfig::class,
    DealQueryBuilder::class,
    DefaultSystemClockConfig::class,
)
@TestPropertySource(locations = ["classpath:application-test.properties"])
@MockkBean(LDClient::class, relaxed = true)
@Suppress("VarCouldBeVal", "NamedArguments", "MagicNumber")
internal class BuyerHealthAggregateServiceTest {
    companion object {
        @Container
        private val container = EmptyDBContainer()

        /** The generator resolves the selling institution by this ticker. */
        private const val SELLER_TICKER = "SELLER"

        private val SALE_DATE = LocalDate.of(2023, 9, 15)

        /** A closing one month before [SALE_DATE]; participations sold here span five periods. */
        private val EARLY_SALE_DATE = LocalDate.of(2023, 8, 15)

        /** A second closing within the T2 period (October), for multi-close deals. */
        private val SECOND_SALE_DATE = LocalDate.of(2023, 10, 15)

        /** The last generated period; the earliest that still yields the four periods T3..T0. */
        private val LAST_PERIOD = YearMonth.of(2023, 12)

        /** Day of month on which every loan pays, safely inside period boundaries. */
        private const val PAYMENT_DAY = 20

        /** ALL7330: every trade excluding collections has never been delinquent or derogatory. */
        private const val SPOTLESS_7330 = 100

        /** ALL7330: some trade has been delinquent or derogatory, so the borrower is not spotless. */
        private const val BLEMISHED_7330 = 80

        /** ALL7330 sentinel for no trade excluding collections; forces the loan out of the sums. */
        private const val FORCE_EXCLUSION_999_7330 = 999

        /** ALL7330 sentinel for no open or closed trade; forces the loan out of the sums. */
        private const val FORCE_EXCLUSION_994_7330 = 994

        /** ALL0300: the minimum number of trades for a clean record to count as spotless. */
        private const val SPOTLESS_TRADE_COUNT_0300 = 3

        /** ALL0300: one trade short of the spotless minimum. */
        private const val THIN_FILE_TRADE_COUNT_0300 = 2

        private data class LoanSpec(
            val originationDate: LocalDate,
            val principal: Int,
            val interestRate: Percentage,
            val monthlyPrincipalPayment: Int,
            /** Payments run monthly from the sale month through this month. */
            val lastPaymentMonth: YearMonth = LAST_PERIOD,
            /** The closing at which the loan is sold to the buyer. */
            val saleDate: LocalDate = SALE_DATE,
            /** Later closings, each selling the buyer a further participation in the loan. */
            val additionalSaleDates: List<LocalDate> = emptyList(),
            /** ALL7330: the share of the obligor's trades that were never delinquent or derogatory. */
            val all7330: Int = SPOTLESS_7330,
            /** ALL0300: the obligor's number of trades excluding collections. */
            val all0300: Int = SPOTLESS_TRADE_COUNT_0300,
            val experianPresence: ExperianPresence = ExperianPresence.HASH_AND_DATA,
        )

        private val LOAN_A =
            LoanSpec(
                originationDate = LocalDate.of(2023, 1, 15),
                principal = 100_000,
                interestRate = Percentage.ofOne(0.02),
                monthlyPrincipalPayment = 1_000,
            )

        private val LOAN_B =
            LoanSpec(
                originationDate = LocalDate.of(2023, 3, 10),
                principal = 50_000,
                interestRate = Percentage.ofOne(0.05),
                monthlyPrincipalPayment = 6_000,
            )

        private val LOAN_C =
            LoanSpec(
                originationDate = LocalDate.of(2023, 4, 20),
                principal = 80_000,
                interestRate = Percentage.ofOne(0.03),
                monthlyPrincipalPayment = 2_000,
                saleDate = SECOND_SALE_DATE,
            )

        private val LOAN_D =
            LoanSpec(
                originationDate = LocalDate.of(2023, 5, 5),
                principal = 60_000,
                interestRate = Percentage.ofOne(0.04),
                monthlyPrincipalPayment = 1_500,
                saleDate = SECOND_SALE_DATE,
            )

        private val LOAN_E =
            LoanSpec(
                originationDate = LocalDate.of(2023, 2, 1),
                principal = 90_000,
                interestRate = Percentage.ofOne(0.06),
                monthlyPrincipalPayment = 4_000,
            )

        private val LOAN_F =
            LoanSpec(
                originationDate = LocalDate.of(2023, 6, 25),
                principal = 60_000,
                interestRate = Percentage.ofOne(0.035),
                monthlyPrincipalPayment = 3_000,
            )

        /** Received before the T3 period end, so it applies to every generated period. */
        private val EXPERIAN_DATA_DATE = LocalDate.of(2023, 9, 1)

        /** Received after T3 but in time for T0; used to prove per-period data row selection. */
        private val EXPERIAN_LATE_DATA_DATE = LocalDate.of(2023, 11, 10)

        /** Received within T0 (December), before the period ends. */
        private val EXPERIAN_T0_DATA_DATE = LocalDate.of(2023, 12, 10)

        /**
         * The date a loan turns unhealthy — reported delinquent, repossessed, or bankrupt.
         * Falls in the T0 period (December), so earlier periods remain unaffected.
         */
        private val UNHEALTHY_STATE_DATE = LocalDate.of(2023, 12, 15)

        /** Unhealthy: 60+ days delinquent. */
        private const val DELINQUENT_DAYS = 75

        /** Falls in the T1 period (November), the period the activity filter inspects. */
        private val T1_STATE_DATE = LocalDate.of(2023, 11, 15)

        /** Falls in the T2 period (October). */
        private val T2_STATE_DATE = LocalDate.of(2023, 10, 15)

        /** Falls in the T3 period (September). */
        private val T3_STATE_DATE = LocalDate.of(2023, 9, 15)

        /** A payment received within T0 after the loan turned unhealthy — payments can still
         * arrive in delinquency, repossession, or bankruptcy. */
        private const val UNHEALTHY_STATE_PAYMENT = 8_000

        /** Every loan in the population spotless. */
        private val FULL_HEALTH = Percentage.ofOne(1)

        /** Spotless loan A over loans A and B at the T0 close: 96,000 / (96,000 + 26,000). */
        private val A_SPOTLESS_T0_OVER_A_AND_B = Percentage.ofOne("0.78688525")

        /**
         * The delta when loan A is spotless and loan B is not on both sides: 96,000 / 122,000 at
         * T0 against 99,000 / (99,000 + 44,000) at the September close, so loan B's faster paydown
         * lifts loan A's share.
         */
        private val A_SPOTLESS_B_NOT_DELTA = Percentage.ofOne("0.09457755")

        private val ALL_TABLES = primaryTables + heliosTables

        @JvmStatic
        @BeforeAll
        fun rebuildDatabase() {
            transaction(container.getDatabase()) {
                ALL_TABLES.rebuildDatabaseWithTables()
            }
        }
    }

    @TestConfiguration
    class Config {
        @Bean
        fun primaryDatabase() = container.getDatabase()
    }

    @Autowired
    private lateinit var buyerHealthAggregateService: BuyerHealthAggregateService

    @Autowired
    private lateinit var dealQueryBuilder: DealQueryBuilder

    /** Coordinates of the deal seeded by [seedDatabase]. */
    private data class SeededDeal(
        val buyerId: Int,
        val dealId: Int,
    )

    /**
     * Truncates every table, seeds a single deal holding one loan per [specs] entry, then runs
     * the accounting update and snapshot generation. [stage] runs after seeding, within the
     * same transaction, for reported state changes, extra payments, or extra data rows.
     */
    private fun seedDatabase(
        specs: List<LoanSpec>,
        stage: (loans: List<LoansLoanView>) -> Unit = {},
    ): SeededDeal {
        val database = container.getDatabase()
        val loanService = getLoanService(database)
        val obligorService = getObligorService(database)

        var buyerId = 0
        var dealId = 0
        var loans = emptyList<LoansLoanView>()
        transaction(database) {
            ALL_TABLES.truncateTables()

            insertUser()
            val seller =
                insertLegacyInstitution {
                    name = "Seller"
                    ticker = SELLER_TICKER
                }
            val buyer =
                insertLegacyInstitution {
                    name = "Buyer"
                    ticker = "BUYER"
                }
            buyerId = buyer.id.value

            val pool = insertPool { institution = seller }
            val deal = insertDeal { this.pool = pool }
            dealId = deal.id.value

            loans =
                specs.map { spec ->
                    val loan =
                        insertLoan(
                            poolId = pool.id.value,
                            originationDate = spec.originationDate,
                            interestRate = spec.interestRate,
                        ) {
                            copy(
                                institutionId = AccInstitutionId(seller.id.value),
                                originalPrincipalAmount = Money.of(spec.principal.toString()),
                            )
                        }
                    (listOf(spec.saleDate) + spec.additionalSaleDates).forEach { closingDate ->
                        insertParticipation {
                            loanId = loanStaticEntityId(loan.id.value)
                            buyerInstitution = buyer
                            effectiveDate = closingDate
                        }
                    }
                    insertData(loan.id.value, spec)

                    var month = YearMonth.from(spec.saleDate)
                    while (month <= spec.lastPaymentMonth) {
                        loan.insertPrincipalAvailablePayment(spec.monthlyPrincipalPayment, month.atDay(PAYMENT_DAY))
                        month = month.plusMonths(1)
                    }
                    loan
                }
            stage(loans)
        }

        loanService.updateAccounting(loans.map { LoanId(it.id.value) }.associateWith { LocalDate.MIN })
        generateWholeDealSnapshots(database, loanService, obligorService, LAST_PERIOD)
        transaction(database) { refreshMaterializedViews() }

        return SeededDeal(buyerId, dealId)
    }

    /** Calculates buyer health for [deal] against the currently seeded database. */
    private fun buyerHealthForDeal(deal: SeededDeal): InternalBuyerHealthDto =
        buyerHealthAggregateService.getBuyerHealth(
            dealQueryBuilder.buildQueryByParticipation(deal.buyerId, deal.dealId, null),
        )

    private fun insertData(
        loanId: Int,
        spec: LoanSpec,
    ) {
        if (spec.experianPresence == ExperianPresence.NONE) {
            return
        }
        insertSsnHash(loanId)
        if (spec.experianPresence == ExperianPresence.HASH_ONLY) {
            return
        }
        insertExperianRow(loanId, EXPERIAN_DATA_DATE) {
            it[p13All7330] = spec.all7330
            it[p13All0300] = spec.all0300
        }
    }

    /** Two spotless loans paying every period. */
    @Test
    fun `spotless portfolio scores full health with zero delta`() {
        val deal = seedDatabase(listOf(LOAN_A, LOAN_B))

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = FULL_HEALTH,
                delta = Percentage.ZERO,
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loan B falls short of spotless per Experian on every period: either a trade has been
     * delinquent or derogatory, or the record is clean across too few trades. It stays in the
     * denominator at its close principal while contributing nothing to the numerator, so the
     * health follows loan A's share of the principal.
     */
    @ParameterizedTest(name = "ALL7330 = {0}, ALL0300 = {1}")
    @CsvSource(
        "$BLEMISHED_7330, $SPOTLESS_TRADE_COUNT_0300",
        "$SPOTLESS_7330, $THIN_FILE_TRADE_COUNT_0300",
    )
    fun `loan short of spotless per Experian enters the denominator only`(
        all7330: Int,
        all0300: Int,
    ) {
        val deal = seedDatabase(listOf(LOAN_A, LOAN_B.copy(all7330 = all7330, all0300 = all0300)))

        val result = buyerHealthForDeal(deal)

        // T0: spotless loan A at close, 100,000 - 4 x 1,000 = 96,000, over loans A and B at close,
        // 96,000 + 50,000 - 4 x 6,000 = 122,000. T3: 99,000 / (99,000 + 44,000). Both loans have
        // four periods, so the delta population matches the headline.
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = A_SPOTLESS_T0_OVER_A_AND_B,
                delta = A_SPOTLESS_B_NOT_DELTA,
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loan B stops its regular payments after November and is reported 75 days delinquent within
     * the T0 period (December) — unhealthy despite a spotless Experian record — and a payment
     * still arrives afterwards, so its T0 close principal differs from its open principal and
     * pins down which of the two the denominator uses.
     */
    @Test
    fun `loan reported 60 plus days delinquent within the T0 period leaves the numerator at its close principal`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(1)))) { loans ->
                insertLoanPerformance {
                    loanId = loans.last().id
                    effectiveDate = UNHEALTHY_STATE_DATE
                    daysDelinquent = DELINQUENT_DAYS
                }
                loans.last().insertPrincipalAvailablePayment(UNHEALTHY_STATE_PAYMENT, LAST_PERIOD.atDay(PAYMENT_DAY))
            }

        val result = buyerHealthForDeal(deal)

        // T0 numerator: spotless loan A at close, 96,000. The denominator adds delinquent loan B
        // at close, 50,000 - 3 x 6,000 - 8,000 = 24,000 (at open it would be 32,000). Health =
        // 96,000 / 120,000 = 0.8. Both loans have four periods, so the delta population matches;
        // at T3 both are spotless for full health, leaving the delta at 0.8 - 1.
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne("0.8"),
                delta = Percentage.ofOne("-0.2"),
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loan B stops its regular payments after November and enters repossession within the T0
     * period (December), but a payment still arrives afterwards. Repossession overrides the
     * spotless Experian record regardless of days delinquent.
     */
    @Test
    fun `loan entering repossession within the T0 period leaves the numerator`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(1)))) { loans ->
                insertLoanStatus {
                    loanId = loanStaticEntityId(loans.last().id.value)
                    effectiveDate = UNHEALTHY_STATE_DATE
                    statusType = StatusType.REPOSSESSION
                }
                loans.last().insertPrincipalAvailablePayment(UNHEALTHY_STATE_PAYMENT, LAST_PERIOD.atDay(PAYMENT_DAY))
            }

        val result = buyerHealthForDeal(deal)

        // T0: spotless loan A at close, 96,000, over 96,000 + repossessed loan B at close,
        // 50,000 - 3 x 6,000 - 8,000 = 24,000. Health = 0.8 against full health at T3.
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne("0.8"),
                delta = Percentage.ofOne("-0.2"),
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loan B stops its regular payments after November and enters bankruptcy within the T0
     * period (December), but a payment still arrives afterwards. Bankruptcy overrides the
     * spotless Experian record regardless of days delinquent.
     */
    @Test
    fun `loan entering bankruptcy within the T0 period leaves the numerator`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(1)))) { loans ->
                insertLoanStatus {
                    loanId = loanStaticEntityId(loans.last().id.value)
                    effectiveDate = UNHEALTHY_STATE_DATE
                    statusType = StatusType.BANKRUPTCY
                }
                loans.last().insertPrincipalAvailablePayment(UNHEALTHY_STATE_PAYMENT, LAST_PERIOD.atDay(PAYMENT_DAY))
            }

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne("0.8"),
                delta = Percentage.ofOne("-0.2"),
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loan B stops paying after October and charges off within T1 (November), so it enters T0
     * already terminated. Unlike current distress, buyer health draws its delta population from
     * the headline population, so the charge-off is filtered out of both sides alike and loan A
     * alone remains on either side.
     */
    @Test
    fun `loan charged off before T0 is excluded from both the headline and the delta populations`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(2)))) { loans ->
                insertLoanStatus {
                    loanId = loanStaticEntityId(loans.last().id.value)
                    effectiveDate = T1_STATE_DATE
                    statusType = StatusType.CHARGED_OFF
                }
            }

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = FULL_HEALTH,
                delta = Percentage.ZERO,
                loanCount = 1,
            ),
            result,
        )
    }

    /**
     * Loan B stops paying after November and charges off within the T0 period (December),
     * having been spotless through T3. A charged-off loan enters the denominator at its open
     * principal and nothing else — and it drops out of the loan count while its principal still
     * weighs on the sums.
     */
    @Test
    fun `loan charging off within the T0 period enters the denominator at its open principal and leaves the loan count`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(1)))) { loans ->
                insertLoanStatus {
                    loanId = loanStaticEntityId(loans.last().id.value)
                    effectiveDate = UNHEALTHY_STATE_DATE
                    statusType = StatusType.CHARGED_OFF
                }
            }

        val result = buyerHealthForDeal(deal)

        // T0 numerator: spotless loan A at close, 96,000. The denominator adds charged-off loan B
        // at open, 50,000 - 3 x 6,000 = 32,000 (at close it would be zero). Health =
        // 96,000 / 128,000 = 0.75 against full health at T3.
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne("0.75"),
                delta = Percentage.ofOne("-0.25"),
                loanCount = 1,
            ),
            result,
        )
    }

    /**
     * Loan B pays through October and then repays its remaining principal within T1 (November),
     * so it is repaid throughout T0 and is filtered out of the headline and the delta
     * populations alike. Repayment is a computed state, staged via payments.
     */
    @Test
    fun `loan repaid in full before T0 is excluded from both the headline and the delta populations`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(2)))) { loans ->
                val remainingPrincipal = LOAN_B.principal - 2 * LOAN_B.monthlyPrincipalPayment
                loans.last().insertPrincipalAvailablePayment(remainingPrincipal, T1_STATE_DATE)
            }

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = FULL_HEALTH,
                delta = Percentage.ZERO,
                loanCount = 1,
            ),
            result,
        )
    }

    /**
     * Loans B and E are sold at the early closing (August) and pay once, in August, before loan B
     * repays its remaining principal within T3 (September) and loan E charges off within T3.
     * Both therefore carry five periods of history yet enter T0 already terminated, so both
     * are filtered out; loan A, sold at the regular September closing and paying throughout, is
     * the only loan left on either side.
     */
    @Test
    fun `loans repaid or charged off within T3 are excluded from both populations`() {
        val deal =
            seedDatabase(
                listOf(
                    LOAN_A,
                    LOAN_B.copy(saleDate = EARLY_SALE_DATE, lastPaymentMonth = LAST_PERIOD.minusMonths(4)),
                    LOAN_E.copy(saleDate = EARLY_SALE_DATE, lastPaymentMonth = LAST_PERIOD.minusMonths(4)),
                ),
            ) { loans ->
                val remainingPrincipal = LOAN_B.principal - LOAN_B.monthlyPrincipalPayment
                loans[1].insertPrincipalAvailablePayment(remainingPrincipal, T3_STATE_DATE)
                insertLoanStatus {
                    loanId = loanStaticEntityId(loans[2].id.value)
                    effectiveDate = T3_STATE_DATE
                    statusType = StatusType.CHARGED_OFF
                }
            }

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = FULL_HEALTH,
                delta = Percentage.ZERO,
                loanCount = 1,
            ),
            result,
        )
    }

    /**
     * The deal's only loan pays through November and then repays its remaining principal within
     * T0 (December). Its principal at the T0 close is zero, and with no other loan it would be
     * the whole denominator — so the loan must be excluded from the population and the
     * calculation degrade to nulls, not raise a division-by-zero error.
     */
    @Test
    fun `single loan repaid in full within the T0 period yields nulls instead of dividing by zero`() {
        val deal =
            seedDatabase(listOf(LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(1)))) { loans ->
                val remainingPrincipal = LOAN_B.principal - 3 * LOAN_B.monthlyPrincipalPayment
                loans.single().insertPrincipalAvailablePayment(remainingPrincipal, LAST_PERIOD.atDay(PAYMENT_DAY))
            }

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = null,
                delta = null,
                loanCount = 0,
            ),
            result,
        )
    }

    /**
     * Loans invisible to Experian: one with no SSN hash at all, one with a hash but no dataset
     * rows. Both silently vanish from the calculation.
     */
    @Test
    fun `loans invisible to Experian are omitted from the calculation`() {
        val deal =
            seedDatabase(
                listOf(
                    LOAN_A,
                    LOAN_E.copy(experianPresence = ExperianPresence.NONE),
                    LOAN_F.copy(experianPresence = ExperianPresence.HASH_ONLY),
                ),
            )

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = FULL_HEALTH,
                delta = Percentage.ZERO,
                loanCount = 1,
            ),
            result,
        )
    }

    /**
     * Loan A is spotless while loan B is blemished, so the sums on both sides are observable;
     * loan E is force-excluded on its data row received before T3 ends — the explicit 999
     * sentinel or the 994 one — so the exclusion applies to every period. Loan E vanishes from
     * the sums on both sides, yet it still counts towards the loan count, which disregards
     * exclusion per Experian.
     */
    @ParameterizedTest(name = "ALL7330 = {0}")
    @ValueSource(ints = [FORCE_EXCLUSION_999_7330, FORCE_EXCLUSION_994_7330])
    fun `force-excluded loan vanishes from the sums but not from the loan count`(exclusion7330: Int) {
        val deal =
            seedDatabase(
                listOf(
                    LOAN_A,
                    LOAN_B.copy(all7330 = BLEMISHED_7330),
                    LOAN_E.copy(all7330 = exclusion7330),
                ),
            )

        val result = buyerHealthForDeal(deal)

        // T0: 96,000 / 122,000 over loans A and B alone; T3: 99,000 / 143,000 likewise. With
        // loan E in the sums, T0 would instead be (96,000 + 74,000) / 196,000.
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = A_SPOTLESS_T0_OVER_A_AND_B,
                delta = A_SPOTLESS_B_NOT_DELTA,
                loanCount = 3,
            ),
            result,
        )
    }

    /**
     * Loan A is spotless while loan B is blemished. Loan E is spotless on its early data row and
     * receives the exclusion indication between T3 and T0, so each side picks the latest data
     * row received on or before its period end: the T3 side still counts loan E, in the
     * numerator and the denominator alike, while the headline and the T0 side of the delta
     * exclude it.
     */
    @Test
    fun `exclusion first received after T3 removes the loan from the T0 side only`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(all7330 = BLEMISHED_7330), LOAN_E)) { loans ->
                insertExperianRow(loans.last().id.value, EXPERIAN_LATE_DATA_DATE) {
                    it[p13All7330] = FORCE_EXCLUSION_999_7330
                    it[p13All0300] = SPOTLESS_TRADE_COUNT_0300
                }
            }

        val result = buyerHealthForDeal(deal)

        // Headline and the delta's T0 side: 96,000 / 122,000 over loans A and B alone. The
        // delta's T3 side: spotless loans A and E at the September close, 99,000 + 90,000 -
        // 4,000 = 185,000, over all three loans, 99,000 + 44,000 + 86,000 = 229,000. Delta =
        // 96,000 / 122,000 - 185,000 / 229,000, rounded to 8 decimals.
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = A_SPOTLESS_T0_OVER_A_AND_B,
                delta = Percentage.ofOne("-0.02097502"),
                loanCount = 3,
            ),
            result,
        )
    }

    /**
     * Loan B is blemished on its data row received within T3, then a row received within T0
     * reports it spotless. T3 sees loan A alone spotless; T0 sees both loans spotless, so the
     * health recovers to full and the delta is the T3 shortfall.
     */
    @Test
    fun `blemished value received within T3 is superseded by a spotless value received within T0`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(all7330 = BLEMISHED_7330))) { loans ->
                insertExperianRow(loans.last().id.value, EXPERIAN_T0_DATA_DATE) {
                    it[p13All7330] = SPOTLESS_7330
                    it[p13All0300] = SPOTLESS_TRADE_COUNT_0300
                }
            }

        val result = buyerHealthForDeal(deal)

        // T0: full health. T3: 99,000 / 143,000. Delta = 1 - 99,000 / 143,000, rounded to 8 decimals.
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = FULL_HEALTH,
                delta = Percentage.ofOne("0.30769231"),
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loan B is blemished on its data row received within T3, then a row received within T0
     * carries a spotless ALL7330 but no ALL0300. A row missing either attribute is skipped when
     * picking the row in effect, so the blemished T3 row carries forward and loan B is short of
     * spotless at T0 as well — it is neither promoted to spotless nor dropped from the sums.
     */
    @Test
    fun `blemished value received within T3 stays in effect when a row missing an attribute arrives within T0`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(all7330 = BLEMISHED_7330))) { loans ->
                insertExperianRow(loans.last().id.value, EXPERIAN_T0_DATA_DATE) {
                    it[p13All7330] = SPOTLESS_7330 // ALL0300 left null
                }
            }

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = A_SPOTLESS_T0_OVER_A_AND_B,
                delta = A_SPOTLESS_B_NOT_DELTA,
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * A multi-close deal: a second closing within T2 (October) adds loans C and D, which
     * therefore exist at T0 without T3 history — they contribute to the headline but not to the
     * delta, which compares only participations with four periods of history; loan B enters
     * bankruptcy within T0 and stops paying.
     */
    @Test
    fun `loans bought at a later closing contribute to the headline but not to the delta`() {
        val deal =
            seedDatabase(
                listOf(LOAN_A, LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(1)), LOAN_C, LOAN_D),
            ) { loans ->
                insertLoanStatus {
                    loanId = loanStaticEntityId(loans[1].id.value)
                    effectiveDate = UNHEALTHY_STATE_DATE
                    statusType = StatusType.BANKRUPTCY
                }
            }

        val result = buyerHealthForDeal(deal)

        // T0 numerator: the spotless loans at close — A 96,000, C 80,000 - 3 x 2,000 = 74,000,
        // and D 60,000 - 3 x 1,500 = 55,500 — 225,500 in all. The denominator adds bankrupt
        // loan B at close, 50,000 - 3 x 6,000 = 32,000. Health = 225,500 / 257,500, which the
        // query rounds to 8 decimals. The delta only compares loans A and B, the two with T3
        // history: 96,000 / 128,000 = 0.75 at T0 against full health at T3.
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne("0.87572816"),
                delta = Percentage.ofOne("-0.25"),
                loanCount = 4,
            ),
            result,
        )
    }

    /**
     * Both loans are sold at an early closing (August), giving them five periods of snapshot
     * data; loan B enters repossession within T2 (October) and remains there through T0, so it
     * is unhealthy at T0 without any T0-period state change; its payments continue through
     * November. A second closing in September additionally sells the buyer a further
     * participation in loan A — with four periods of history it passes the T3 filter and
     * contributes to the calculation, doubling loan A's weight in the sums.
     */
    @Test
    fun `double dip on loan A, with both participations contributing, and loan B repossessed at T2`() {
        val deal =
            seedDatabase(
                listOf(
                    LOAN_A.copy(saleDate = EARLY_SALE_DATE, additionalSaleDates = listOf(SALE_DATE)),
                    LOAN_B.copy(saleDate = EARLY_SALE_DATE, lastPaymentMonth = LAST_PERIOD.minusMonths(1)),
                ),
            ) { loans ->
                insertLoanStatus {
                    loanId = loanStaticEntityId(loans.last().id.value)
                    effectiveDate = T2_STATE_DATE
                    statusType = StatusType.REPOSSESSION
                }
            }

        val result = buyerHealthForDeal(deal)

        // T0 numerator: spotless loan A at close, 100,000 - 5 x 1,000 = 95,000, counted once per
        // participation, 190,000. The denominator adds repossessed loan B at close, 50,000 -
        // 4 x 6,000 = 26,000. Health = 190,000 / 216,000, which the query rounds to 8 decimals.
        // At T3 (September) loan B is not yet repossessed, so every participation is spotless
        // for full health and the delta is the T0 shortfall.
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne("0.87962963"),
                delta = Percentage.ofOne("-0.12037037"),
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * The deal's only closing is within T2 (October), so no loan has T3 history: the health is
     * still calculated over the two spotless loans, but the delta population is empty and the
     * delta degrades to null.
     */
    @Test
    fun `deal without four months of history yields a health value but no delta`() {
        val deal = seedDatabase(listOf(LOAN_C, LOAN_D))

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = FULL_HEALTH,
                delta = null,
                loanCount = 2,
            ),
            result,
        )
    }

    /** A spotless deal exists, but the query targets fictional deal 9999 and matches nothing. */
    @Test
    fun `deal with no matching participations yields nulls`() {
        val deal = seedDatabase(listOf(LOAN_A, LOAN_B))

        val result = buyerHealthForDeal(deal.copy(dealId = 9999))

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = null,
                delta = null,
                loanCount = 0,
            ),
            result,
        )
    }
}
