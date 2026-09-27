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
 * principal every period — except after a loan turns unhealthy or charged-off, when payments
 * may stop. Every participation carries the same ownership share, so that factor cancels in
 * buyer health ratios, and expected values follow from plain loan-level balances: original
 * principal minus the principal paid by the relevant period boundary.
 *
 * The headline buyer health and the delta are calculated over two different
 * populations — participations that started T0 active versus participations that ended T3
 * active — so the expected values below spell out both sides where they differ.
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
            /** p13All7330: no trades flagged or scored as 1 (flat) or all trades scored as 7 (spotless). */
            val p13All7330: Int = SPOTLESS_7330,
            /** p13All0300: number of trades with derogatory information. */
            val p13All0300: Int = CLEAN_0300,
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
         * The date a loan turns unhealthy — reported delinquent (60+ days), repossessed, or bankrupt.
         * Falls in the T0 period (December), so earlier periods remain unaffected.
         */
        private val UNHEALTHY_STATE_DATE = LocalDate.of(2023, 12, 15)

        /** Unhealthy: 60+ days delinquent (the threshold used in buyer health classification). */
        private const val DELINQUENT_DAYS = 75

        /** Falls in the T1 period (November), the period the activity filter inspects. */
        private val T1_STATE_DATE = LocalDate.of(2023, 11, 15)

        /** Falls in the T2 period (October). */
        private val T2_STATE_DATE = LocalDate.of(2023, 10, 15)

        /** Falls in the T3 period (September); the last period the delta population filter inspects. */
        private val T3_STATE_DATE = LocalDate.of(2023, 9, 15)

        /** A payment received within T0 after the loan turned unhealthy — payments can still
         * arrive in delinquency, repossession, or bankruptcy. */
        private const val UNHEALTHY_STATE_PAYMENT = 8_000

        /** Spotless: all trades scored as 7. */
        private const val SPOTLESS_7330 = 100

        /** Force exclusion: 999 (Insufficient updating) or 994 (Scoring not possible). */
        private const val FORCE_EXCLUDE_7330_1 = 999
        private const val FORCE_EXCLUDE_7330_2 = 994

        /** 3 or more trades with derogatory information. */
        private const val CLEAN_0300 = 3

        /** 2 trades with derogatory information (not spotless). */
        private const val NOT_SPOTLESS_0300 = 2

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
            it[p13All7330] = spec.p13All7330
            it[p13All0300] = spec.p13All0300
        }
    }

    /** Two clean loans with spotless Experian classification paying every period. */
    @Test
    fun `spotless portfolio scores perfect buyer health with zero delta`() {
        val deal = seedDatabase(listOf(LOAN_A, LOAN_B))

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ONE,
                delta = Percentage.ZERO,
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loan B stops its regular payments after November and is reported 75 days delinquent within
     * the T0 period (December) — unhealthy according to Loan Street data — and a payment
     * still arrives afterwards, so its T0 close principal differs from its open principal and
     * pins down which of the two the sums use.
     */
    @Test
    fun `loan reported 60 plus days delinquent within the T0 period is unhealthy and reduces buyer health`() {
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

        // T0 numerator: unhealthy loan B is excluded from numerator, only clean loan A contributes
        // Clean loan A at close: 100,000 - 4 * 1,000 = 96,000
        // Loan B at close: 50,000 - 3 * 6,000 - 8,000 = 24,000 (unhealthy)
        // Denominator: loan A close (96,000) + loan B open (32,000) = 128,000
        // Buyer health = 96,000 / 128,000 = 0.75
        // Both loans ended T3 healthy, so delta = 0 (no change from T3 to T0)
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne("0.75"),
                delta = Percentage.ZERO,
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loan B stops its regular payments after November and enters repossession within the T0
     * period (December), but a payment still arrives afterwards. Repossession counts as unhealthy.
     */
    @Test
    fun `loan entering repossession within the T0 period counts as unhealthy`() {
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

        // Same calculation as delinquent case: unhealthy loan excluded from numerator
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne("0.75"),
                delta = Percentage.ZERO,
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loan B stops its regular payments after November and enters bankruptcy within the T0
     * period (December), but a payment still arrives afterwards. Bankruptcy counts as unhealthy.
     */
    @Test
    fun `loan entering bankruptcy within the T0 period counts as unhealthy`() {
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
                buyerHealth = Percentage.ofOne("0.75"),
                delta = Percentage.ZERO,
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loan B stops paying after October and charges off within T1 (November), so it enters T0
     * already terminated and is filtered out of the headline — but having ended T3 healthy it
     * stays in the delta population, where the charge-off amount is picked up from the November
     * period's open principal.
     */
    @Test
    fun `loan charged off before T0 is excluded from the headline but enters the delta at its charge-off principal`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(2)))) { loans ->
                insertLoanStatus {
                    loanId = loanStaticEntityId(loans.last().id.value)
                    effectiveDate = T1_STATE_DATE
                    statusType = StatusType.CHARGED_OFF
                }
            }

        val result = buyerHealthForDeal(deal)

        // Headline: only clean loan A remains, so buyer health = 1.0 over one loan
        // Loan B charged off before T0, excluded from headline calculation
        // Delta: loan B contributes as charged-off at T0 (excluded from numerator)
        // T0 numerator: only loan A at close (96,000)
        // T0 denominator: loan A close (96,000) + loan B open at charge-off (50,000 - 2 * 6,000 = 38,000) = 134,000
        // T0 buyer health = 96,000 / 134,000 = 0.71641791
        // T3: both loans healthy, buyer health = 1.0
        // Delta = 0.71641791 - 1.0 = -0.28358209
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ONE,
                delta = Percentage.ofOne("-0.28358209"),
                loanCount = 1,
            ),
            result,
        )
    }

    /**
     * Loan B stops paying after November and charges off within the T0 period (December),
     * having been healthy through T3. A charged-off loan enters both numerator and denominator
     * at its open principal but is excluded from numerator.
     */
    @Test
    fun `loan charging off within the T0 period is excluded from numerator but included in denominator`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(1)))) { loans ->
                insertLoanStatus {
                    loanId = loanStaticEntityId(loans.last().id.value)
                    effectiveDate = UNHEALTHY_STATE_DATE
                    statusType = StatusType.CHARGED_OFF
                }
            }

        val result = buyerHealthForDeal(deal)

        // T0 numerator: charged-off loan B excluded, only loan A contributes
        // Loan A at close: 100,000 - 4 * 1,000 = 96,000
        // Loan B at open (since charged off in T0): 50,000 - 3 * 6,000 = 32,000
        // Denominator: 96,000 + 32,000 = 128,000
        // Buyer health = 96,000 / 128,000 = 0.75
        // T3: both loans healthy, buyer health = 1.0
        // Delta = 0.75 - 1.0 = -0.25
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne("0.75"),
                delta = Percentage.ofOne("-0.25"),
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loan B pays through October and then repays its remaining principal within T1 (November),
     * so it is repaid throughout T0 and is filtered out of the headline and the delta
     * populations alike.
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
                buyerHealth = Percentage.ONE,
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
     * Loan B stops paying after November and charges off within T0 (December), while its only
     * Experian row arrives after the T0 period end — so no Experian classification applies to
     * T0 at all. Loan Street data takes priority over Experian data, and a missing Experian
     * classification must not weaken that: the charge-off weighs into numerator/denominator
     * exactly as in the plain charge-off scenario.
     */
    @Test
    fun `LS charge-off within T0 counts even when the Experian row arrives after the T0 period end`() {
        val deal =
            seedDatabase(
                listOf(
                    LOAN_A,
                    LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(1), experianPresence = ExperianPresence.HASH_ONLY),
                ),
            ) { loans ->
                insertExperianRow(loans.last().id.value, EXPERIAN_DATA_AFTER_T0_DATE) {
                    it[p13All7330] = SPOTLESS_7330
                    it[p13All0300] = CLEAN_0300
                }
                insertLoanStatus {
                    loanId = loanStaticEntityId(loans.last().id.value)
                    effectiveDate = UNHEALTHY_STATE_DATE
                    statusType = StatusType.CHARGED_OFF
                }
            }

        val result = buyerHealthForDeal(deal)

        // Identical to the plain T0 charge-off: loan B excluded from numerator
        // Loan A close: 96,000, Loan B open: 32,000
        // Buyer health = 0.75, Delta = -0.25 (from T3 health of 1.0)
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne("0.75"),
                delta = Percentage.ofOne("-0.25"),
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loan B's Experian data indicates force exclusion (p13All7330 = 999 or 994), which should
     * exclude it from both numerator and denominator entirely.
     */
    @Test
    fun `loan with Experian force exclusion is omitted from calculation`() {
        val deal =
            seedDatabase(
                listOf(
                    LOAN_A,
                    LOAN_B.copy(p13All7330 = FORCE_EXCLUDE_7330_1),
                ),
            )

        val result = buyerHealthForDeal(deal)

        // Loan B excluded due to force exclusion code, only loan A contributes
        // Single spotless loan gives perfect buyer health
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ONE,
                delta = Percentage.ZERO,
                loanCount = 1,
            ),
            result,
        )
    }

    /**
     * Loan B has Experian classification that is not spotless (p13All7330 = 100 but p13All0300 < 3),
     * but remains healthy according to Loan Street data. Loan Street data takes priority.
     */
    @Test
    fun `non-spotless Experian classification overridden by clean LS data`() {
        val deal =
            seedDatabase(
                listOf(
                    LOAN_A,
                    LOAN_B.copy(p13All0300 = NOT_SPOTLESS_0300),
                ),
            )

        val result = buyerHealthForDeal(deal)

        // Both loans clean according to LS data (no delinquency/bankruptcy/repossession/charge-off)
        // Experian non-spotless classification doesn't matter since LS data is clean
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ONE,
                delta = Percentage.ZERO,
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * A multi-close deal: a second closing within T2 (October) adds loans C and D, which
     * therefore exist at T0 without T3 history — they contribute to the headline rates but
     * not to the delta, which compares only participations that ended T3 active.
     */
    @Test
    fun `loans bought at a later closing contribute to the headline rates but not to the delta`() {
        val deal = seedDatabase(listOf(LOAN_A, LOAN_B, LOAN_C, LOAN_D))

        val result = buyerHealthForDeal(deal)

        // All loans spotless and clean
        // Headline: all 4 loans contribute
        // Delta: only loans A and B contribute (have T3 history)
        // Both headline and delta should show perfect health
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ONE,
                delta = Percentage.ZERO,
                loanCount = 4,
            ),
            result,
        )
    }

    /**
     * Both loans are sold at an early closing (August), giving them five periods of snapshot
     * data; loan B enters repossession within T2 (October) and remains there through T0, so it
     * is unhealthy at T0 without any T0-period state change; its payments continue through
     * November.
     */
    @Test
    fun `loan repossessed at T2 remains unhealthy through T0`() {
        val deal =
            seedDatabase(
                listOf(
                    LOAN_A.copy(saleDate = EARLY_SALE_DATE),
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

        // Loan B unhealthy from T2 through T0
        // T0 numerator: only loan A contributes
        // Loan A at close: 100,000 - 5 * 1,000 = 95,000
        // Loan B at open (since unhealthy): 50,000 - 4 * 6,000 = 26,000
        // Denominator: 95,000 + 26,000 = 121,000
        // Buyer health = 95,000 / 121,000 = 0.78512397
        // T3: loan B not yet repossessed, both healthy
        // T3 buyer health = 1.0
        // Delta = 0.78512397 - 1.0 = -0.21487603
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne("0.78512397"),
                delta = Percentage.ofOne("-0.21487603"),
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * The deal's only closing is within T2 (October), so no loan has T3 history: the headline
     * buyer health is still calculated over the two clean loans, but the delta population is empty
     * and the delta degrades to null.
     */
    @Test
    fun `deal without four months of history yields headline buyer health but no delta`() {
        val deal = seedDatabase(listOf(LOAN_C, LOAN_D))

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ONE,
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