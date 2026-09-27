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
import com.loanstreet.accounting.obligor.database.ObligorStaticSsnHashTable
import com.loanstreet.accounting.platform.insertUser
import com.loanstreet.accounting.test.EmptyDBContainer
import com.loanstreet.accounting.test.rebuildDatabaseWithTables
import com.loanstreet.accounting.test.refreshMaterializedViews
import com.loanstreet.accounting.test.truncateTables
import com.loanstreet.accounting.web.analytics.experian.ExperianPresence
import com.loanstreet.accounting.web.analytics.experian.insertExperianRow
import com.loanstreet.accounting.web.analytics.experian.ssnHashFor
import com.loanstreet.accounting.web.analytics.filtering.DealQueryBuilder
import com.ninjasquad.springmockk.MockkBean
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.assertAll
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
import kotlin.test.assertNull

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
 * participation carries the same ownership share, so that factor cancels in the buyer health
 * ratio, and expected values follow from plain loan-level balances: original principal minus
 * the principal paid by the relevant period boundary.
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
            /** ALL7330: the percentage of trades never in collections. */
            val all7330: Int = SPOTLESS_7330,
            /** ALL0300: the number of trades reported. */
            val all0300: Int = TRADE_COUNT_0300,
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
                all0300 = INSUFFICIENT_TRADES_0300,
            )

        private val LOAN_F =
            LoanSpec(
                originationDate = LocalDate.of(2023, 6, 25),
                principal = 60_000,
                interestRate = Percentage.ofOne(0.035),
                monthlyPrincipalPayment = 3_000,
                all7330 = BLEMISHED_7330,
            )

        /** Received before the T3 period end, so it applies to every generated period. */
        private val EXPERIAN_DATA_DATE = LocalDate.of(2023, 9, 1)

        /** Received after T3 but in time for T0; used to prove per-period data row selection. */
        private val EXPERIAN_LATE_DATA_DATE = LocalDate.of(2023, 11, 10)

        /**
         * The date a loan turns unhealthy — reported delinquent, repossessed, or bankrupt.
         * Falls in the T0 period (December), so earlier periods remain unaffected.
         */
        private val UNHEALTHY_STATE_DATE = LocalDate.of(2023, 12, 15)
        private const val DELINQUENT_DAYS = 75

        /** Falls in the T1 period (November), the period the activity filter inspects. */
        private val T1_STATE_DATE = LocalDate.of(2023, 11, 15)

        /** Falls in the T2 period (October). */
        private val T2_STATE_DATE = LocalDate.of(2023, 10, 15)

        /** A payment received within T0 after the loan turned unhealthy — payments can still
         * arrive in repossession or bankruptcy. */
        private const val UNHEALTHY_STATE_PAYMENT = 8_000

        /** Spotless: no derogatory events (ALL7330 = 100) and enough trades reported (ALL0300 >= 3). */
        private const val SPOTLESS_7330 = 100
        private const val TRADE_COUNT_0300 = 5

        /** Blemished: less than 100 percent of trades free of collections. */
        private const val BLEMISHED_7330 = 95

        /** Blemished: fewer trades reported than the spotless criteria require. */
        private const val INSUFFICIENT_TRADES_0300 = 2

        /** Sentinel ALL7330 values that force the loan out of the calculation entirely. */
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
        ObligorStaticSsnHashTable.insert {
            it[ObligorStaticSsnHashTable.loanId] = loanId
            it[obligorId] = 0
            it[ssnSha256] = ssnHashFor(loanId)
        }
        if (spec.experianPresence == ExperianPresence.HASH_ONLY) {
            return
        }
        insertExperianRow(loanId, EXPERIAN_DATA_DATE) {
            it[p13All7330] = spec.all7330
            it[p13All0300] = spec.all0300
        }
    }

    /** Two healthy, spotless loans paying every period. */
    @Test
    fun `healthy spotless portfolio scores 100 percent with zero delta`() {
        val deal = seedDatabase(listOf(LOAN_A, LOAN_B))

        val result = buyerHealthForDeal(deal)

        assertAll(
            { assertEquals(Percentage.ofOne("1"), result.buyerHealth, "buyer health") },
            { assertEquals(Percentage.ZERO, result.delta, "delta") },
            { assertEquals(2, result.loanCount, "loan count") },
        )
    }

    /**
     * Loan B stops its regular payments after November and is reported delinquent within the T0
     * period (December), but a payment still arrives afterwards — a delinquent loan can keep
     * making partial payments — so its T0 close principal differs from its open principal and
     * pins down which of the two the denominator uses.
     */
    @Test
    fun `loan reported delinquent within the T0 period drops from the numerator at its close principal`() {
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

        // T0 numerator: loan A at close, 100,000 - 4 x 1,000 = 96,000. The denominator adds
        // delinquent loan B at close: 50,000 - 3 x 6,000 - 8,000 = 24,000 (at open it would
        // be 32,000, and the ratio 0.75). T0 = 96,000 / 120,000 = 0.8; at T3 both loans are
        // healthy, so T3 = 1.
        assertAll(
            { assertEquals(Percentage.ofOne("0.8"), result.buyerHealth, "buyer health") },
            { assertEquals(Percentage.ofOne("-0.2"), result.delta, "delta") },
            { assertEquals(2, result.loanCount, "loan count") },
        )
    }

    /**
     * Loan B stops its regular payments after November and enters repossession within the T0
     * period (December), but a payment still arrives afterwards — payments can still happen in
     * repossession — so its T0 close principal differs from its open principal and pins down
     * which of the two the denominator uses.
     */
    @Test
    fun `loan entering repossession within the T0 period drops from the numerator at its close principal`() {
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

        // T0 numerator: loan A at close, 100,000 - 4 x 1,000 = 96,000. The denominator adds
        // repossessed loan B at close: 50,000 - 3 x 6,000 - 8,000 = 24,000 (at open it would
        // be 32,000, and the ratio 0.75). T0 = 96,000 / 120,000 = 0.8; at T3 both loans are
        // healthy, so T3 = 1.
        assertAll(
            { assertEquals(Percentage.ofOne("0.8"), result.buyerHealth, "buyer health") },
            { assertEquals(Percentage.ofOne("-0.2"), result.delta, "delta") },
            { assertEquals(2, result.loanCount, "loan count") },
        )
    }

    /**
     * As the repossession case, but loan B enters bankruptcy instead: regular payments stop
     * after November, bankruptcy is entered within T0, and a further payment still arrives
     * afterwards, distinguishing the loan's T0 close principal from its open.
     */
    @Test
    fun `loan entering bankruptcy within the T0 period drops from the numerator at its close principal`() {
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

        // Same arithmetic as the repossession scenario: 96,000 / (96,000 + 24,000).
        assertAll(
            { assertEquals(Percentage.ofOne("0.8"), result.buyerHealth, "buyer health") },
            { assertEquals(Percentage.ofOne("-0.2"), result.delta, "delta") },
            { assertEquals(2, result.loanCount, "loan count") },
        )
    }

    /**
     * Loan B stops paying after October and charges off within T1 (November), so it enters T0
     * already terminated and is filtered out.
     */
    @Test
    fun `loan charged off before its most recent period is excluded from the calculation`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(2)))) { loans ->
                insertLoanStatus {
                    loanId = loanStaticEntityId(loans.last().id.value)
                    effectiveDate = T1_STATE_DATE
                    statusType = StatusType.CHARGED_OFF
                }
            }

        val result = buyerHealthForDeal(deal)

        // Only healthy loan A remains in both periods.
        assertAll(
            { assertEquals(Percentage.ofOne("1"), result.buyerHealth, "buyer health") },
            { assertEquals(Percentage.ZERO, result.delta, "delta") },
            { assertEquals(1, result.loanCount, "loan count") },
        )
    }

    /**
     * Loan B stops paying after November and charges off within the T0 period (December),
     * having been healthy through T3. Unlike the other unhealthy states, a charged-off loan
     * enters the denominator at its open principal.
     */
    @Test
    fun `loan charging off within the T0 period drops from the numerator at its open principal`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(1)))) { loans ->
                insertLoanStatus {
                    loanId = loanStaticEntityId(loans.last().id.value)
                    effectiveDate = UNHEALTHY_STATE_DATE
                    statusType = StatusType.CHARGED_OFF
                }
            }

        val result = buyerHealthForDeal(deal)

        // T0 numerator: loan A at close, 100,000 - 4 x 1,000 = 96,000. The denominator adds
        // charged-off loan B at open: 50,000 - 3 x 6,000 = 32,000. T0 = 96,000 / 128,000 = 0.75;
        // at T3 both loans are healthy, so T3 = 1.
        assertAll(
            { assertEquals(Percentage.ofOne("0.75"), result.buyerHealth, "buyer health") },
            { assertEquals(Percentage.ofOne("-0.25"), result.delta, "delta") },
            // A loan charging off during T0 is excluded from the count despite still
            // weighing in the denominator.
            { assertEquals(1, result.loanCount, "loan count") },
        )
    }

    /**
     * Loan A pays as usual and is spotless throughout. Loan B is spotless as of T3, but a
     * force-exclusion indicator received within T1 applies to T0, and the loan then charges off
     * within T0.
     *
     * Charge-off takes precedence over force exclusion in the T0 denominator, so loan B weighs
     * in at its open principal rather than dropping out. Both ways of losing that principal —
     * excluding the participation outright, as force exclusion alone would, or taking the
     * charged-off loan at its close principal, which the pipeline zeroes — leave loan A as the
     * whole denominator and put buyer health at 1, well clear of the expected 0.75.
     */
    @Test
    fun `loan force-excluded from T1 and charged off within T0 weighs in the denominator at its open principal`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(1)))) { loans ->
                insertExperianRow(loans.last().id.value, EXPERIAN_LATE_DATA_DATE) {
                    it[p13All7330] = FORCE_EXCLUSION_999_7330
                    it[p13All0300] = TRADE_COUNT_0300
                }
                insertLoanStatus {
                    loanId = loanStaticEntityId(loans.last().id.value)
                    effectiveDate = UNHEALTHY_STATE_DATE
                    statusType = StatusType.CHARGED_OFF
                }
            }

        val result = buyerHealthForDeal(deal)

        // T0 numerator: loan A at close, 100,000 - 4 x 1,000 = 96,000; loan B is both excluded
        // and charged off, so it contributes nothing. The denominator adds loan B at open:
        // 50,000 - 3 x 6,000 = 32,000. T0 = 96,000 / 128,000 = 0.75. At T3 the exclusion indicator
        // has not yet been received and neither loan is charged off, so T3 = 1.
        assertAll(
            { assertEquals(Percentage.ofOne("0.75"), result.buyerHealth, "buyer health") },
            { assertEquals(Percentage.ofOne("-0.25"), result.delta, "delta") },
            // A loan charging off during T0 is excluded from the count despite still
            // weighing in the denominator.
            { assertEquals(1, result.loanCount, "loan count") },
        )
    }

    /**
     * Loan B pays through October and then repays its remaining principal within T1 (November),
     * so it enters T0 already repaid in full and is filtered out. Repayment is a computed
     * state, staged via payments.
     */
    @Test
    fun `loan repaid in full before its most recent period is excluded from the calculation`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(2)))) { loans ->
                val remainingPrincipal = LOAN_B.principal - 2 * LOAN_B.monthlyPrincipalPayment
                loans.last().insertPrincipalAvailablePayment(remainingPrincipal, T1_STATE_DATE)
            }

        val result = buyerHealthForDeal(deal)

        // Only healthy loan A remains in both periods.
        assertAll(
            { assertEquals(Percentage.ofOne("1"), result.buyerHealth, "buyer health") },
            { assertEquals(Percentage.ZERO, result.delta, "delta") },
            { assertEquals(1, result.loanCount, "loan count") },
        )
    }

    /**
     * The deal's only loan pays through November and then repays its remaining principal within
     * T0 (December). Its principal at the T0 close is zero, and with no other loan it would be
     * the whole T0 denominator — so the loan must be excluded and the calculation degrade to
     * nulls, not raise a division-by-zero error.
     */
    @Test
    fun `single loan repaid in full within the T0 period yields nulls instead of dividing by zero`() {
        val deal =
            seedDatabase(listOf(LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(1)))) { loans ->
                val remainingPrincipal = LOAN_B.principal - 3 * LOAN_B.monthlyPrincipalPayment
                loans.single().insertPrincipalAvailablePayment(remainingPrincipal, LAST_PERIOD.atDay(PAYMENT_DAY))
            }

        val result = buyerHealthForDeal(deal)

        assertAll(
            { assertNull(result.buyerHealth, "buyer health") },
            { assertNull(result.delta, "delta") },
            // The repaid loan is filtered out entirely, leaving nothing to count.
            { assertEquals(0, result.loanCount, "loan count") },
        )
    }

    /**
     * A multi-close deal: a second closing within T2 (October) adds loans C and D, which
     * therefore exist at T0 without T3 history — they contribute to the T0 buyer health but
     * not to the delta, which compares only participations that have T3 history; loan B
     * enters bankruptcy within T0 and stops paying.
     */
    @Test
    fun `loans bought at a later closing contribute to buyer health at T0 but not to the delta`() {
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

        // T0 numerator: healthy loans at close — A 96,000, C 80,000 - 3 x 2,000 = 74,000, and
        // D 60,000 - 3 x 1,500 = 55,500 — totalling 225,500. The denominator adds bankrupt
        // loan B at close: 50,000 - 3 x 6,000 = 32,000. T0 = 225,500 / 257,500, which the
        // query rounds to 8 decimals. The delta only compares loans A and B, the two with T3
        // history: 96,000 / 128,000 = 0.75 at T0 against a healthy T3 of 1.
        assertAll(
            { assertEquals(Percentage.ofOne("0.87572816"), result.buyerHealth, "buyer health") },
            { assertEquals(Percentage.ofOne("-0.25"), result.delta, "delta") },
            // All four loans count: the T3-history filter no longer applies to the count.
            { assertEquals(4, result.loanCount, "loan count") },
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
    fun `double dip on loan A, with both participations contributing, and loan B goes unhealthy at T2`() {
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

        // T0 numerator: loan A at close, 100,000 - 5 x 1,000 = 95,000, counted once per
        // participation. The denominator adds repossessed loan B at close: 50,000 - 4 x 6,000
        // = 26,000. T0 = 190,000 / 216,000 = 95/108, a repeating decimal the SQL division
        // yields at scale 20; at T3 (September) loan B is not yet repossessed, so T3 = 1.
        assertAll(
            { assertEquals(Percentage.ofOne("0.87962963"), result.buyerHealth, "buyer health") },
            { assertEquals(Percentage.ofOne("-0.12037037"), result.delta, "delta") },
            // Loan A is counted once despite holding two participations.
            { assertEquals(2, result.loanCount, "loan count") },
        )
    }

    /**
     * The deal's only closing is within T2 (October), so no loan has T3 history: the T0 buyer
     * health is still calculated over the two healthy loans, but the delta has nothing with a
     * T3 to compare and degrades to null.
     */
    @Test
    fun `deal without four months of history yields buyer health but no delta`() {
        val deal = seedDatabase(listOf(LOAN_C, LOAN_D))

        val result = buyerHealthForDeal(deal)

        assertAll(
            { assertEquals(Percentage.ofOne("1"), result.buyerHealth, "buyer health") },
            { assertNull(result.delta, "delta") },
            { assertEquals(2, result.loanCount, "loan count") },
        )
    }

    /** A healthy deal exists, but the query targets fictional deal 9999 and matches nothing. */
    @Test
    fun `deal with no matching participations yields nulls`() {
        val deal = seedDatabase(listOf(LOAN_A, LOAN_B))

        val result = buyerHealthForDeal(deal.copy(dealId = 9999))

        assertAll(
            { assertNull(result.buyerHealth, "buyer health") },
            { assertNull(result.delta, "delta") },
            { assertEquals(0, result.loanCount, "loan count") },
        )
    }

    /**
     * Two spotless loans alongside two blemished ones — loan E lacking sufficient reported
     * trades, loan F with a trade in collections. The blemished pair's balances equal the
     * spotless pair's at both period boundaries, so blemished loans counting in the denominator
     * only puts buyer health at exactly one half.
     */
    @Test
    fun `blemished loans contribute to the denominator only`() {
        val deal = seedDatabase(listOf(LOAN_A, LOAN_B, LOAN_E, LOAN_F))

        val result = buyerHealthForDeal(deal)

        // T0: spotless (96,000 + 26,000) over all four at close (122,000 + 74,000 + 48,000);
        // T3: 143,000 over 286,000 — one half at both boundaries, so the delta is zero.
        assertAll(
            { assertEquals(Percentage.ofOne("0.5"), result.buyerHealth, "buyer health") },
            { assertEquals(Percentage.ZERO, result.delta, "delta") },
            { assertEquals(4, result.loanCount, "loan count") },
        )
    }

    /**
     * Two spotless loans alongside two force-excluded ones, one per ALL7330 sentinel value.
     * Were they mistakenly processed as merely blemished, the mirrored balances would put buyer
     * health at one half; excluded entirely, only the spotless pair remains and buyer health is
     * whole.
     */
    @Test
    fun `force-excluded loans vanish from numerator and denominator`() {
        val deal =
            seedDatabase(
                listOf(
                    LOAN_A,
                    LOAN_B,
                    LOAN_E.copy(all7330 = FORCE_EXCLUSION_999_7330, all0300 = TRADE_COUNT_0300),
                    LOAN_F.copy(all7330 = FORCE_EXCLUSION_994_7330),
                ),
            )

        val result = buyerHealthForDeal(deal)

        assertAll(
            { assertEquals(Percentage.ofOne("1"), result.buyerHealth, "buyer health") },
            { assertEquals(Percentage.ZERO, result.delta, "delta") },
            // Force exclusion empties the health sums, but the loans still count.
            { assertEquals(4, result.loanCount, "loan count") },
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

        // Only loan A remains in both periods.
        assertAll(
            { assertEquals(Percentage.ofOne("1"), result.buyerHealth, "buyer health") },
            { assertEquals(Percentage.ZERO, result.delta, "delta") },
            { assertEquals(1, result.loanCount, "loan count") },
        )
    }

    /**
     * The loan's early data row is spotless and applies to T3; a blemished indicator received between
     * T3 and T0 applies to T0.
     */
    @Test
    fun `each period uses the latest data row received on or before its end`() {
        val deal =
            seedDatabase(listOf(LOAN_A)) { loans ->
                insertExperianRow(loans.first().id.value, EXPERIAN_LATE_DATA_DATE) {
                    it[p13All7330] = BLEMISHED_7330
                    it[p13All0300] = TRADE_COUNT_0300
                }
            }

        val result = buyerHealthForDeal(deal)

        // Spotless as of T3 (early data row), blemished by T0 (late data row).
        assertAll(
            { assertEquals(Percentage.ZERO, result.buyerHealth, "buyer health") },
            { assertEquals(Percentage.ofOne("-1"), result.delta, "delta") },
            { assertEquals(1, result.loanCount, "loan count") },
        )
    }

    @Test
    fun `portfolio with only force-excluded loans yields nulls`() {
        val deal = seedDatabase(listOf(LOAN_A.copy(all7330 = FORCE_EXCLUSION_994_7330)))

        val result = buyerHealthForDeal(deal)

        assertAll(
            { assertNull(result.buyerHealth, "buyer health") },
            { assertNull(result.delta, "delta") },
            // Force exclusion empties the health sums, but the loan still counts.
            { assertEquals(1, result.loanCount, "loan count") },
        )
    }
}
