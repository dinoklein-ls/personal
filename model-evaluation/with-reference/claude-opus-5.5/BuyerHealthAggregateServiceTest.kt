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
import org.junit.jupiter.params.provider.EnumSource
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
 * participation carries the same ownership share, so that factor cancels in the health ratio,
 * and expected values follow from plain loan-level balances: original principal minus the
 * principal paid by the relevant period boundary.
 *
 * Unlike current distress, the headline and the delta share one population — participations
 * that started T0 active — with the delta further restricted to those holding T3 history.
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

        /** ALL7330 = 100 alongside ALL0300 >= 3 makes an obligor spotless. */
        private const val SPOTLESS_7330 = 100

        /** The fewest trades a spotless obligor may hold. */
        private const val SPOTLESS_MIN_0300 = 3

        /** ALL7330 short of 100: visible to Experian, neither spotless nor excluded. */
        private const val BLEMISHED_7330 = 90

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
            /** ALL0300: the obligor's total number of trades. */
            val all0300: Int = SPOTLESS_MIN_0300,
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

        /** Past the 60 days that make a loan unhealthy. */
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

    /** Two spotless loans paying every period; both sit exactly at the minimum trade count. */
    @Test
    fun `spotless portfolio scores full health with zero delta`() {
        val deal = seedDatabase(listOf(LOAN_A, LOAN_B))

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne(1),
                delta = Percentage.ZERO,
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loan B misses spotless per Experian on either criterion — ALL7330 short of 100, or one
     * trade too few — so it weighs into the denominator but not the numerator on both sides of
     * the delta.
     */
    @ParameterizedTest(name = "ALL7330 = {0}, ALL0300 = {1}")
    @CsvSource(
        "$BLEMISHED_7330, 5",
        "100, 2",
    )
    fun `loan short of spotless per Experian enters the denominator only`(
        all7330: Int,
        all0300: Int,
    ) {
        val deal = seedDatabase(listOf(LOAN_A, LOAN_B.copy(all7330 = all7330, all0300 = all0300)))

        val result = buyerHealthForDeal(deal)

        // T0: spotless loan A at close, 100,000 - 4 x 1,000 = 96,000, over 96,000 + loan B at
        // close 50,000 - 4 x 6,000 = 26,000, i.e. 96,000 / 122,000. T3: 99,000 / (99,000 +
        // 44,000). Loan B's faster paydown lifts the health, so the delta is positive.
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
     * Loan B carries an Experian force-exclusion value, so it vanishes from both sums — but,
     * as a non-charged-off loan with Experian data, it still counts towards the loan count.
     */
    @ParameterizedTest(name = "ALL7330 = {0}")
    @ValueSource(ints = [999, 994])
    fun `force-excluded loan vanishes from the sums but not the loan count`(exclusion7330: Int) {
        val deal = seedDatabase(listOf(LOAN_A, LOAN_B.copy(all7330 = exclusion7330)))

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne(1),
                delta = Percentage.ZERO,
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Spotless loan B stops its regular payments after November and is reported delinquent
     * within T0 (December) by a day count on either side of the 60-day threshold; a payment
     * still arrives afterwards, so its T0 close principal differs from its open principal and
     * pins down which of the two the denominator uses.
     */
    @ParameterizedTest(name = "{0} days delinquent within T0: health={1}, delta={2}")
    @CsvSource(
        "59, 1, 0",
        "60, 0.8, -0.2",
    )
    fun `LS delinquency within T0 overrides a spotless Experian classification from 60 days`(
        daysDelinquent: Int,
        expectedHealth: String,
        expectedDelta: String,
    ) {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(1)))) { loans ->
                insertLoanPerformance {
                    loanId = loans.last().id
                    effectiveDate = UNHEALTHY_STATE_DATE
                    this.daysDelinquent = daysDelinquent
                }
                loans.last().insertPrincipalAvailablePayment(UNHEALTHY_STATE_PAYMENT, LAST_PERIOD.atDay(PAYMENT_DAY))
            }

        val result = buyerHealthForDeal(deal)

        // Delinquent, loan B leaves the numerator but stays in the denominator at close,
        // 50,000 - 3 x 6,000 - 8,000 = 24,000 (at open it would be 32,000); loan A adds 96,000
        // to both, so health = 96,000 / 120,000 = 0.8. T3 is spotless throughout.
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
     * Spotless loan B stops its regular payments after November and enters repossession or
     * bankruptcy within T0 (December), but a payment still arrives afterwards. Either state
     * makes the loan unhealthy regardless of days delinquent.
     */
    @ParameterizedTest
    @EnumSource(value = StatusType::class, names = ["REPOSSESSION", "BANKRUPTCY"])
    fun `LS repossession or bankruptcy within T0 overrides a spotless Experian classification`(statusType: StatusType) {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(1)))) { loans ->
                insertLoanStatus {
                    loanId = loanStaticEntityId(loans.last().id.value)
                    effectiveDate = UNHEALTHY_STATE_DATE
                    this.statusType = statusType
                }
                loans.last().insertPrincipalAvailablePayment(UNHEALTHY_STATE_PAYMENT, LAST_PERIOD.atDay(PAYMENT_DAY))
            }

        val result = buyerHealthForDeal(deal)

        // Loan B at close, 24,000, in the denominator only: 96,000 / 120,000 = 0.8, against a
        // spotless T3.
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
     * Spotless loan B stops paying after November and charges off within T0 (December). A
     * charged-off loan enters the denominator at its open principal and drops out of the loan
     * count.
     */
    @Test
    fun `loan charging off within the T0 period enters the denominator at its open principal`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(1)))) { loans ->
                insertLoanStatus {
                    loanId = loanStaticEntityId(loans.last().id.value)
                    effectiveDate = UNHEALTHY_STATE_DATE
                    statusType = StatusType.CHARGED_OFF
                }
            }

        val result = buyerHealthForDeal(deal)

        // Loan B at open, 50,000 - 3 x 6,000 = 32,000, in the denominator only: 96,000 /
        // 128,000 = 0.75, against a spotless T3.
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
     * Loan B carries an Experian force-exclusion value and charges off within T0 (December). The
     * charge-off takes priority over the exclusion, so loan B still weighs into the denominator
     * at its open principal.
     */
    @Test
    fun `LS charge-off within T0 overrides an Experian force exclusion`() {
        val deal =
            seedDatabase(
                listOf(LOAN_A, LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(1), all7330 = 999)),
            ) { loans ->
                insertLoanStatus {
                    loanId = loanStaticEntityId(loans.last().id.value)
                    effectiveDate = UNHEALTHY_STATE_DATE
                    statusType = StatusType.CHARGED_OFF
                }
            }

        val result = buyerHealthForDeal(deal)

        // As for a plain charge-off at T0: 96,000 / (96,000 + 32,000). At T3 the exclusion
        // governs, leaving loan A alone and spotless.
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
     * Loan B carries an Experian force-exclusion value and is reported 75 days delinquent within
     * T0 (December). Only a charge-off outranks the exclusion: a delinquent, force-excluded loan
     * stays out of both sums.
     */
    @Test
    fun `LS delinquency within T0 does not override an Experian force exclusion`() {
        val deal =
            seedDatabase(
                listOf(LOAN_A, LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(1), all7330 = 999)),
            ) { loans ->
                insertLoanPerformance {
                    loanId = loans.last().id
                    effectiveDate = UNHEALTHY_STATE_DATE
                    daysDelinquent = DELINQUENT_DAYS
                }
            }

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne(1),
                delta = Percentage.ZERO,
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loan B stops paying after October and charges off within T1 (November), so it enters T0
     * already terminated. The headline and the delta share one population, so loan B is
     * filtered out of both — unlike current distress, the charge-off does not reach the delta.
     */
    @Test
    fun `loan charged off before T0 is excluded from both the headline and the delta`() {
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
                buyerHealth = Percentage.ofOne(1),
                delta = Percentage.ZERO,
                loanCount = 1,
            ),
            result,
        )
    }

    /**
     * Loan B pays through October and then repays its remaining principal within T1 (November),
     * so it is repaid throughout T0 and is filtered out. Repayment is a computed state, staged
     * via payments.
     */
    @Test
    fun `loan repaid in full before T0 is excluded from the calculation`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(all7330 = BLEMISHED_7330, lastPaymentMonth = LAST_PERIOD.minusMonths(2)))) { loans ->
                val remainingPrincipal = LOAN_B.principal - 2 * LOAN_B.monthlyPrincipalPayment
                loans.last().insertPrincipalAvailablePayment(remainingPrincipal, T1_STATE_DATE)
            }

        val result = buyerHealthForDeal(deal)

        // Blemished loan B would otherwise pull the health below 1 on both sides.
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne(1),
                delta = Percentage.ZERO,
                loanCount = 1,
            ),
            result,
        )
    }

    /**
     * The deal's only loan pays through November and then repays its remaining principal within
     * T0 (December). Its principal at the T0 close is zero, and with no other loan it would be
     * the whole denominator — so the loan must be excluded and the calculation degrade to nulls,
     * not raise a division-by-zero error.
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
     * T0 at all. The buyer health functions are strict, so the missing classification nulls
     * loan B's contributions and the charge-off leaves no trace in the sums.
     */
    @Test
    fun `LS charge-off within T0 drops out when the Experian row arrives after the T0 period end`() {
        val deal =
            seedDatabase(
                listOf(
                    LOAN_A,
                    LOAN_B.copy(lastPaymentMonth = LAST_PERIOD.minusMonths(1), experianPresence = ExperianPresence.HASH_ONLY),
                ),
            ) { loans ->
                insertExperianRow(loans.last().id.value, EXPERIAN_DATA_AFTER_T0_DATE) {
                    it[p13All7330] = SPOTLESS_7330
                    it[p13All0300] = SPOTLESS_MIN_0300
                }
                insertLoanStatus {
                    loanId = loanStaticEntityId(loans.last().id.value)
                    effectiveDate = UNHEALTHY_STATE_DATE
                    statusType = StatusType.CHARGED_OFF
                }
            }

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne(1),
                delta = Percentage.ZERO,
                loanCount = 1,
            ),
            result,
        )
    }

    /**
     * Loan B's only Experian row, blemished, arrives between T3 and T0: it classifies the T0
     * side, while at T3 no classification applies and loan B drops out of that side.
     */
    @Test
    fun `Experian row received after the T3 period end applies to the T0 side only`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(experianPresence = ExperianPresence.HASH_ONLY))) { loans ->
                insertExperianRow(loans.last().id.value, EXPERIAN_LATE_DATA_DATE) {
                    it[p13All7330] = BLEMISHED_7330
                    it[p13All0300] = SPOTLESS_MIN_0300
                }
            }

        val result = buyerHealthForDeal(deal)

        // T0: 96,000 / (96,000 + 26,000). T3: loan A alone, spotless, so 1.
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne("0.78688525"),
                delta = Percentage.ofOne("-0.21311475"),
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loan B is blemished per its baseline Experian row, and a spotless row received within T1
     * (November) supersedes it for T0 while the baseline still governs T3.
     */
    @Test
    fun `spotless Experian row received after T3 supersedes the baseline for the T0 side`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(all7330 = BLEMISHED_7330))) { loans ->
                insertExperianRow(loans.last().id.value, EXPERIAN_LATE_DATA_DATE) {
                    it[p13All7330] = SPOTLESS_7330
                    it[p13All0300] = SPOTLESS_MIN_0300
                }
            }

        val result = buyerHealthForDeal(deal)

        // T0: both loans spotless, so 1. T3: 99,000 / (99,000 + 44,000).
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne(1),
                delta = Percentage.ofOne("0.30769231"),
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loan B is blemished per its baseline Experian row, and a row received within T1
     * (November) carries a spotless ALL7330 but a null ALL0300. Rows missing either attribute are
     * skipped when picking the row in effect, so the blemished baseline carries forward to T0.
     */
    @Test
    fun `Experian row with a null attribute does not supersede the baseline`() {
        val deal =
            seedDatabase(listOf(LOAN_A, LOAN_B.copy(all7330 = BLEMISHED_7330))) { loans ->
                insertExperianRow(loans.last().id.value, EXPERIAN_LATE_DATA_DATE) {
                    it[p13All7330] = SPOTLESS_7330
                }
            }

        val result = buyerHealthForDeal(deal)

        // Identical to a blemished loan B throughout: 96,000 / 122,000 at T0, 99,000 / 143,000 at T3.
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
     * Spotless loan B is sold at the early closing (August), pays once, and is reported 75 days
     * delinquent within T3 (September), a state that persists through T0. It is unhealthy on
     * both sides of the delta, which reflects only loan A's paydown between the two periods.
     */
    @Test
    fun `LS delinquency from T3 makes the loan unhealthy on both sides of the delta`() {
        val deal =
            seedDatabase(
                listOf(
                    LOAN_A,
                    LOAN_B.copy(saleDate = EARLY_SALE_DATE, lastPaymentMonth = LAST_PERIOD.minusMonths(4)),
                ),
            ) { loans ->
                insertLoanPerformance {
                    loanId = loans.last().id
                    effectiveDate = T3_STATE_DATE
                    daysDelinquent = DELINQUENT_DAYS
                }
            }

        val result = buyerHealthForDeal(deal)

        // Loan B holds 50,000 - 6,000 = 44,000 from August on. T0: 96,000 / (96,000 + 44,000);
        // T3: 99,000 / (99,000 + 44,000). The delta is the difference of the two unrounded
        // values, rounded to 8 decimals.
        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne("0.68571429"),
                delta = Percentage.ofOne("-0.00659341"),
                loanCount = 2,
            ),
            result,
        )
    }

    /**
     * Loans invisible to Experian: one with no SSN hash at all, one with a hash but no dataset
     * rows. Both would be spotless, yet silently vanish from the calculation.
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
                buyerHealth = Percentage.ofOne(1),
                delta = Percentage.ZERO,
                loanCount = 1,
            ),
            result,
        )
    }

    /**
     * A multi-close deal: a second closing within T2 (October) adds loans C and D, which
     * therefore exist at T0 without T3 history — they contribute to the headline health but
     * not to the delta; loan B enters bankruptcy within T0 and stops paying.
     */
    @Test
    fun `loans bought at a later closing contribute to the headline health but not to the delta`() {
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
        // and D 60,000 - 3 x 1,500 = 55,500 — for 225,500; the denominator adds bankrupt loan B
        // at close, 50,000 - 3 x 6,000 = 32,000, for 257,500. The delta only compares loans A
        // and B, the two with T3 history: 96,000 / 128,000 = 0.75 at T0 against a spotless T3.
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

        // T0 numerator: loan A at close, 100,000 - 5 x 1,000 = 95,000, counted once per
        // participation for 190,000. The denominator adds repossessed loan B at close,
        // 50,000 - 4 x 6,000 = 26,000. Health = 190,000 / 216,000, which the query rounds to 8
        // decimals. At T3 (September) loan B is not yet repossessed, so T3 is spotless.
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
     * health is still calculated over the two spotless loans, but the delta degrades to null.
     */
    @Test
    fun `deal without four months of history yields headline health but no delta`() {
        val deal = seedDatabase(listOf(LOAN_C, LOAN_D))

        val result = buyerHealthForDeal(deal)

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne(1),
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
