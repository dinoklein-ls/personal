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
 * Exercises the buyer health SQL in [BuyerHealthAggregateService] against snapshots
 * produced by the real snapshot pipeline from basic loan data — institutions, loans,
 * participations, payments, and reported state changes — rather than hand-inserted snapshot rows.
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
 * participation carries the same ownership share, so that factor cancels in the health
 * ratio, and expected values follow from plain loan-level balances: original principal minus
 * the principal paid by the relevant period boundary.
 *
 * A loan counts toward the numerator only when it is spotless per Experian (ALL7330 = 100 and
 * ALL0300 >= 3) and clean per Loan Street data; it counts toward the denominator at its close
 * principal, at its open principal when charged off, and not at all when force-excluded per
 * Experian (ALL7330 of 999 or 994) or when no Experian classification applies to the period —
 * the SQL functions are STRICT, so a missing classification drops the loan from that period's
 * sums even when Loan Street data marks it unhealthy or charged off. The loan count, by
 * contrast, follows the Loan Street state alone: every loan with Experian data that is not
 * charged off at T0 is counted, excluded or not.
 *
 * The headline health and the delta are calculated over two different populations — all
 * participations that started T0 active versus the subset with four periods of history — so
 * the expected values below spell out both sides where they differ.
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
            /** ALL7330: the percentage of the obligor's trades never delinquent or derogatory. */
            val all7330: Int = SPOTLESS_7330,
            /** ALL0300: the obligor's total number of trades excluding collections. */
            val all0300: Int = SPOTLESS_0300,
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

        /** Received after the T0 period end, so no Experian classification applies to any period. */
        private val EXPERIAN_DATA_AFTER_T0_DATE = LocalDate.of(2024, 1, 5)

        /**
         * The date a loan turns unhealthy — reported delinquent, repossessed, or bankrupt.
         * Falls in the T0 period (December), so earlier periods remain unaffected.
         */
        private val UNHEALTHY_STATE_DATE = LocalDate.of(2023, 12, 15)

        /** Unhealthy per Loan Street data: 60+ days delinquent. */
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

        /** Every trade never delinquent or derogatory — the spotless ALL7330 value. */
        private const val SPOTLESS_7330 = 100

        /** Comfortably above the minimum of three trades the spotless classification requires. */
        private const val SPOTLESS_0300 = 5

        /** Below the spotless threshold: some trades delinquent or derogatory. */
        private const val NOT_SPOTLESS_7330 = 50

        /** The explicit ALL7330 sentinels that force the loan out of the denominator. */
        private const val FORCE_EXCLUSION_999_7330 = 999
        private const val FORCE_EXCLUSION_994_7330 = 994

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

    /** Two clean loans paying every period, both spotless per Experian. */
    @Test
    fun `clean spotless portfolio scores full health with zero delta`() {
        val deal = seedDatabase(listOf(LOAN_A, LOAN_B))

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ONE_HUNDRED,
                delta = Percentage.ZERO,
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loan B stops its regular payments after November and is reported 75 days delinquent within
     * the T0 period (December), and a payment still arrives afterwards, so its T0 close principal
     * differs from its open principal and pins down which of the two the denominator uses. Loan
     * Street data takes priority over the spotless Experian classification: the loan leaves the
     * numerator but stays in the denominator.
     */
    @Test
    fun `loan reported 60 plus days delinquent within the T0 period leaves the numerator but stays in the denominator at its close principal`() {
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

        // T0 numerator: spotless loan A at close, 100,000 - 4 x 1,000 = 96,000; delinquent loan B
        // contributes nothing. The denominator adds loan B at close, 50,000 - 3 x 6,000 - 8,000
        // = 24,000 (at open it would be 32,000). Health = 96,000 / 120,000 = 0.8. Both loans have
        // T3 history and T3 is spotless, so the delta drops by the whole T0 shortfall.
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
     * period (December), but a payment still arrives afterwards. Repossession counts as unhealthy
     * regardless of days delinquent.
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

        // T0 numerator: spotless loan A at close, 96,000. The denominator adds repossessed loan B
        // at close, 50,000 - 3 x 6,000 - 8,000 = 24,000. Health = 96,000 / 120,000 = 0.8, and
        // with a spotless T3 the delta drops by the whole T0 shortfall.
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
     * period (December), but a payment still arrives afterwards. Bankruptcy counts as unhealthy
     * regardless of days delinquent.
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
     * Loan B stops paying after November and charges off within the T0 period (December),
     * having been spotless through T3. A charged-off loan contributes nothing to the numerator
     * and enters the denominator at its open principal; it also leaves the loan count.
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
        // at open, 50,000 - 3 x 6,000 = 32,000. Health = 96,000 / 128,000 = 0.75. The delta side
        // compares the same T0 rate against a spotless T3, and the count drops loan B.
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
     * Loan B stops paying after October and charges off within T1 (November), so it enters T0
     * already terminated and is filtered out of the calculation entirely — unlike the current
     * distress delta, the buyer health delta has no charge-off pickup for earlier periods.
     */
    @Test
    fun `loan charged off before T0 is excluded from the calculation entirely`() {
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
                buyerHealth = Percentage.ONE_HUNDRED,
                delta = Percentage.ZERO,
                loanCount = 1,
            ),
            result,
        )
    }

    /**
     * Loan B pays through October and then repays its remaining principal within T1 (November),
     * so it is repaid throughout T0 and is filtered out of the calculation. Repayment is a
     * computed state, staged via payments.
     */
    @Test
    fun `loan repaid in full before T0 is excluded from the calculation entirely`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(2)))) { loans ->
                val remainingPrincipal = LOAN_B.principal - 2 * LOAN_B.monthlyPrincipalPayment
                loans.last().insertPrincipalAvailablePayment(remainingPrincipal, T1_STATE_DATE)
            }

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ONE_HUNDRED,
                delta = Percentage.ZERO,
                loanCount = 1,
            ),
            result,
        )
    }

    /**
     * The deal's only loan pays through November and then repays its remaining principal within
     * T0 (December). Its principal at the T0 close is zero, and with no other loan it would be
     * the whole denominator on both the headline and the delta side — so the loan must be
     * excluded from both populations and the calculation degrade to nulls, not raise a
     * division-by-zero error.
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
     * Loan B is clean per Loan Street data but not spotless per Experian — only half of the
     * obligor's trades are free of delinquency or derogation — so it leaves the numerator while
     * staying in the denominator at its close principal, on both sides of the delta.
     */
    @Test
    fun `loan clean per Loan Street data but not spotless per Experian leaves the numerator`() {
        val deal = seedDatabase(listOf(LOAN_A, LOAN_B.copy(all7330 = NOT_SPOTLESS_7330)))

        val result = buyerHealthForDeal(deal)

        // T0: 96,000 / (96,000 + 26,000); T3: 99,000 / (99,000 + 44,000). The delta is the
        // difference of the two unrounded rates, rounded to 8 decimals — positive, as the
        // healthier loan A pays down slower and gains share between T3 and T0.
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne("0.78688525"),
                delta = Percentage.ofOne("0.09457755"),
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * The spotless classification boundary: ALL7330 must be exactly 100 and ALL0300 at least 3.
     * Loan B carries the boundary values while loan A stays spotless, so a non-spotless B shows
     * up as a health shortfall against the same denominators as the plain non-spotless scenario.
     */
    @ParameterizedTest(name = "ALL7330 = {0}, ALL0300 = {1}")
    @CsvSource(
        "100, 3, 1, 0",
        "100, 2, 0.78688525, 0.09457755",
        "99, 5, 0.78688525, 0.09457755",
    )
    fun `spotless classification boundary values`(
        all7330: Int,
        all0300: Int,
        expectedHealth: String,
        expectedDelta: String,
    ) {
        val deal = seedDatabase(listOf(LOAN_A, LOAN_B.copy(all7330 = all7330, all0300 = all0300)))

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne(expectedHealth),
                delta = Percentage.ofOne(expectedDelta),
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loan B is force-excluded per Experian throughout — either the explicit 999 sentinel or the
     * 994 no-trade sentinel — so it vanishes from numerator and denominator alike on both sides
     * of the delta. The loan count, however, follows the Loan Street state alone: loan B is not
     * charged off at T0, so it is still counted.
     */
    @ParameterizedTest(name = "ALL7330 = {0}")
    @ValueSource(ints = [FORCE_EXCLUSION_999_7330, FORCE_EXCLUSION_994_7330])
    fun `force-excluded loans vanish from the sums but not the loan count`(exclusion7330: Int) {
        val deal = seedDatabase(listOf(LOAN_A, LOAN_B.copy(all7330 = exclusion7330)))

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ONE_HUNDRED,
                delta = Percentage.ZERO,
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loan B is not spotless per Experian throughout, so the health is below one and the
     * population on each side of the delta is observable. Loan E is spotless on its early data
     * row and receives the exclusion indication between T3 and T0. The T3 side therefore still
     * counts loan E in both sums, while the headline and the T0 side of the delta exclude it
     * from the denominator. The loan count still follows the T0 Loan Street state alone, so all
     * three loans are counted. Runs once per exclusion value: the 999 and the 994 sentinel.
     */
    @ParameterizedTest(name = "ALL7330 = {0}")
    @ValueSource(ints = [FORCE_EXCLUSION_999_7330, FORCE_EXCLUSION_994_7330])
    fun `exclusion first received after T3 removes the loan from the T0 side only`(exclusion7330: Int) {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(all7330 = NOT_SPOTLESS_7330), LOAN_E)) { loans ->
                insertExperianRow(loans.last().id.value, EXPERIAN_LATE_DATA_DATE) {
                    it[p13All7330] = exclusion7330
                    it[p13All0300] = SPOTLESS_0300
                }
            }

        val result = buyerHealthForDeal(deal)

        // Headline and the delta's T0 side: numerator 96,000 (loan A alone) over loans A and B,
        // 96,000 + 26,000 = 122,000. The delta's T3 side: loans A and E spotless over all three
        // at the September close, (99,000 + 86,000) / (99,000 + 44,000 + 86,000) = 185,000 /
        // 229,000. Delta = 96,000 / 122,000 - 185,000 / 229,000, rounded to 8 decimals.
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne("0.78688525"),
                delta = Percentage.ofOne("-0.02097502"),
                loanCount = 3,
            ),
            result,
        )
    }

    /**
     * Loan B stops paying after November and charges off within T0 (December), while its only
     * Experian row arrives after the T0 period end — so no Experian classification applies to
     * either period. The SQL functions behind the sums are STRICT, so the null classification
     * drops loan B from numerator and denominator alike on both sides — the charge-off cannot
     * weigh in at its open principal the way it does with a classification in effect — yet the
     * loan still satisfies the Experian visibility filter and stays out of the loan count only
     * because it is charged off.
     */
    @Test
    fun `LS charge-off within T0 drops from the sums when no Experian row applies to T0`() {
        val deal =
            seedDatabase(
                listOf(
                    LOAN_A,
                    LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(1), experianPresence = ExperianPresence.HASH_ONLY),
                ),
            ) { loans ->
                insertExperianRow(loans.last().id.value, EXPERIAN_DATA_AFTER_T0_DATE) {
                    it[p13All7330] = SPOTLESS_7330
                    it[p13All0300] = SPOTLESS_0300
                }
                insertLoanStatus {
                    loanId = loanStaticEntityId(loans.last().id.value)
                    effectiveDate = UNHEALTHY_STATE_DATE
                    statusType = StatusType.CHARGED_OFF
                }
            }

        val result = buyerHealthForDeal(deal)

        // Loan B contributes nulls to every sum, leaving spotless loan A alone on both sides:
        // 96,000 / 96,000 at T0 and 99,000 / 99,000 at T3.
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ONE_HUNDRED,
                delta = Percentage.ZERO,
                loanCount = 1,
            ),
            result,
        )
    }

    /**
     * Loan B is sold at the early closing (August), pays once, and is reported 75 days delinquent
     * within T3 (September), a state that persists through T0. Its only Experian row arrives
     * between T3 and T0, so a classification exists for T0 but none for T3. Unlike the current
     * distress calculation — whose functions keep an Loan Street-unhealthy loan in the sums
     * without an Experian classification — the buyer health functions are STRICT, so loan B
     * drops out of the T3 sums entirely; on the T0 side the classification exists and the
     * delinquency weighs in normally.
     */
    @Test
    fun `LS delinquency from T3 drops the loan from the T3 sums when the Experian row arrives after the T3 period end`() {
        val deal =
            seedDatabase(
                listOf(
                    LOAN_A,
                    LOAN_B.copy(
                        saleDate = EARLY_SALE_DATE,
                        lastPaymentMonth = LAST_PERIOD.minusMonths(4),
                        experianPresence = ExperianPresence.HASH_ONLY,
                    ),
                ),
            ) { loans ->
                insertExperianRow(loans.last().id.value, EXPERIAN_LATE_DATA_DATE) {
                    it[p13All7330] = SPOTLESS_7330
                    it[p13All0300] = SPOTLESS_0300
                }
                insertLoanPerformance {
                    loanId = loans.last().id
                    effectiveDate = T3_STATE_DATE
                    daysDelinquent = DELINQUENT_DAYS
                }
            }

        val result = buyerHealthForDeal(deal)

        // Loan B holds 50,000 - 6,000 = 44,000 from August on. T0: delinquent loan B stays in the
        // denominator at close, so 96,000 / (96,000 + 44,000) = 96,000 / 140,000. T3: loan B has
        // no classification and contributes nulls, leaving loan A alone at 99,000 / 99,000 = 1.
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne("0.68571429"),
                delta = Percentage.ofOne("-0.31428571"),
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loans invisible to Experian: one with no SSN hash at all, one with a hash but no dataset
     * rows. Both silently vanish from the calculation, loan count included.
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
                buyerHealth = Percentage.ONE_HUNDRED,
                delta = Percentage.ZERO,
                loanCount = 1,
            ),
            result,
        )
    }

    /**
     * A multi-close deal: a second closing within T2 (October) adds loans C and D, which
     * therefore exist at T0 without T3 history — they contribute to the headline health but
     * not to the delta, which compares only participations with four periods of history; loan B
     * is not spotless per Experian throughout, so the health is below one on both sides.
     */
    @Test
    fun `loans bought at a later closing contribute to the headline but not to the delta`() {
        val deal =
            seedDatabase(
                listOf(LOAN_A, LOAN_B.copy(all7330 = NOT_SPOTLESS_7330), LOAN_C, LOAN_D),
            )

        val result = buyerHealthForDeal(deal)

        // T0 numerator: the spotless loans at close — A 96,000, C 80,000 - 3 x 2,000 = 74,000, and
        // D 60,000 - 3 x 1,500 = 55,500 — totalling 225,500. The denominator adds non-spotless
        // loan B at close, 50,000 - 4 x 6,000 = 26,000. Health = 225,500 / 251,500, which the
        // query rounds to 8 decimals. The delta only compares loans A and B, the two with T3
        // history, and loan B is non-spotless on both sides: 96,000 / 122,000 at T0 against
        // 99,000 / 143,000 at T3 — the same delta as the plain non-spotless scenario.
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne("0.89662028"),
                delta = Percentage.ofOne("0.09457755"),
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
        // participation. The denominator adds repossessed loan B at close, 50,000 - 4 x 6,000 =
        // 26,000. Health = 190,000 / 216,000, which the query rounds to 8 decimals. At T3
        // (September) loan B is not yet repossessed, so both loans are spotless and the delta
        // reflects only loan B's T0 shortfall.
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
     * The deal's only closing is within T2 (October), so no loan has T3 history: the headline
     * health is still calculated over the two spotless loans, but the delta population is empty
     * and the delta degrades to null.
     */
    @Test
    fun `deal without four months of history yields a health value but no delta`() {
        val deal = seedDatabase(listOf(LOAN_C, LOAN_D))

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ONE_HUNDRED,
                delta = null,
                loanCount = 2,
            ),
            result,
        )
    }

    /** A clean deal exists, but the query targets fictional deal 9999 and matches nothing. */
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
