package com.loanstreet.accounting.web.analytics.experian.buyerhealth

import com.loanstreet.accounting.legacy.insertAnalyticsParticipationLoanSnapshot
import com.loanstreet.accounting.legacy.insertDeal
import com.loanstreet.accounting.legacy.insertDealSnapshot
import com.loanstreet.accounting.legacy.insertLegacyInstitution
import com.loanstreet.accounting.legacy.insertLoan
import com.loanstreet.accounting.legacy.insertLoanSnapshot
import com.loanstreet.accounting.legacy.insertParticipation
import com.loanstreet.accounting.legacy.insertParticipationDealSnapshot
import com.loanstreet.accounting.legacy.insertPeriod
import com.loanstreet.accounting.legacy.insertPool
import com.loanstreet.accounting.legacy.table.AnalyticsExperianDatasetTable
import com.loanstreet.accounting.legacy.table.primaryTables
import com.loanstreet.accounting.legacy.view.AccInstitutionView
import com.loanstreet.accounting.legacy.view.AnalyticsLoanSnapshotView
import com.loanstreet.accounting.legacy.view.AnalyticsParticipationDealSnapshotView
import com.loanstreet.accounting.legacy.view.LoansPoolView
import com.loanstreet.accounting.legacy.view.SalesToolsDealView
import com.loanstreet.accounting.lib.Percentage
import com.loanstreet.accounting.lib.money.Money
import com.loanstreet.accounting.loan.database.loanStaticEntityId
import com.loanstreet.accounting.platform.insertUser
import com.loanstreet.accounting.test.EmptyDBContainer
import com.loanstreet.accounting.web.analytics.experian.ExperianPresence
import com.loanstreet.accounting.web.analytics.experian.insertExperianRow
import com.loanstreet.accounting.web.analytics.experian.insertSsnHash
import com.loanstreet.accounting.web.analytics.filtering.DealQueryBuilder
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises [BuyerHealthAggregateService.getBuyerHealth] against fixtures built per test: a single
 * pool-based deal bought into by one buyer, with each loan carrying its own participation, so the
 * participation IDs are those [DealQueryBuilder] produces for that buyer and deal.
 *
 * Unless a test says otherwise, every participation reports $100 of outstanding principal at the
 * open and close of each period, making the expected health a plain share of spotless loans.
 */
@Testcontainers
internal class BuyerHealthAggregateServiceTest {
    companion object {
        @Container
        private val container = EmptyDBContainer()

        @JvmStatic
        @BeforeAll
        fun createSchema() {
            container.createSchema(primaryTables)
        }

        private val SALE_DATE = LocalDate.of(2023, 12, 31)
        private val JAN = LocalDate.of(2024, 1, 31)
        private val FEB = LocalDate.of(2024, 2, 29)
        private val MAR = LocalDate.of(2024, 3, 31)
        private val APR = LocalDate.of(2024, 4, 30)

        /** ALL7330 = 100 with the minimum of 3 trades that qualifies a buyer as spotless. */
        private val SPOTLESS: AnalyticsExperianDatasetTable.(InsertStatement<Number>) -> Unit = {
            it[p13All7330] = 100
            it[p13All0300] = 3
        }

        /** ALL7330 = 100, but one trade short of qualifying as spotless. */
        private val THIN_FILE: AnalyticsExperianDatasetTable.(InsertStatement<Number>) -> Unit = {
            it[p13All7330] = 100
            it[p13All0300] = 2
        }

        @JvmStatic
        fun experianClassifications() =
            listOf(
                Arguments.of(100, 3, Percentage.ONE_HUNDRED),
                Arguments.of(100, 2, Percentage.ZERO),
                Arguments.of(99, 3, Percentage.ZERO),
            )

        @JvmStatic
        fun unhealthyLoanStreetStates() =
            listOf(
                Arguments.of("60 days delinquent", { s: AnalyticsLoanSnapshotView -> s.currentDaysDelinquent = 60 }),
                Arguments.of("in bankruptcy", { s: AnalyticsLoanSnapshotView -> s.inBankruptcy = true }),
                Arguments.of("in repossession", { s: AnalyticsLoanSnapshotView -> s.inRepossession = true }),
            )

        @JvmStatic
        fun inactiveAtStartOfLatestPeriod() =
            listOf(
                Arguments.of("repaid in T0", listOf(FEB), { s: AnalyticsLoanSnapshotView -> s.repaidInFull = s.period.end == FEB }),
                Arguments.of("repaid in T1", listOf(JAN, FEB), { s: AnalyticsLoanSnapshotView -> s.repaidInFull = true }),
                Arguments.of("charged off in T1", listOf(JAN, FEB), { s: AnalyticsLoanSnapshotView -> s.isChargedOff = true }),
            )
    }

    private val db = container.getDatabase()
    private val service = BuyerHealthAggregateService(db, workMem = 128)

    private lateinit var seller: AccInstitutionView
    private lateinit var buyer: AccInstitutionView
    private lateinit var pool: LoansPoolView
    private lateinit var deal: SalesToolsDealView

    /** The buyer's snapshot of the deal for each of the months the tests span, keyed by period end. */
    private lateinit var participationDealSnapshots: Map<LocalDate, AnalyticsParticipationDealSnapshotView>

    /**
     * Seeds the deal, its participants and its monthly snapshots on clean tables, then runs
     * [statement] to add the loans of the case at hand.
     */
    private fun withDeal(statement: () -> Unit) =
        container.withCleanTables {
            insertUser()
            seller = insertLegacyInstitution { name = "Seller" }
            buyer = insertLegacyInstitution { name = "Buyer" }
            pool = insertPool { institution = seller }
            deal = insertDeal {}
            deal.pool = pool
            participationDealSnapshots = listOf(JAN, FEB, MAR, APR).associateWith(::insertParticipationDealSnapshotFor)
            commit()
            statement()
        }

    private fun insertParticipationDealSnapshotFor(periodEnd: LocalDate): AnalyticsParticipationDealSnapshotView {
        val period =
            insertPeriod {
                start = periodEnd.withDayOfMonth(1)
                end = periodEnd
            }
        val dealSnapshot =
            insertDealSnapshot {
                this.period = period
                dealId = deal.id
                sellerId = seller.id.value
            }
        return insertParticipationDealSnapshot {
            this.period = period
            dealId = deal.id
            dealSnapshotId = dealSnapshot.id
            institutionId = buyer.id
            saleDate = SALE_DATE
        }
    }

    /**
     * Inserts a loan into the deal's pool, the buyer's participation in it, and loan and
     * participation snapshots for each of [periods]; [loanState] sets the Loan Street indicators
     * on each loan snapshot. Returns the loan ID.
     */
    private fun insertParticipatedLoan(
        periods: List<LocalDate> = listOf(JAN),
        openPrincipal: Money = Money.of("100"),
        closePrincipal: Money = Money.of("100"),
        loanState: (AnalyticsLoanSnapshotView) -> Unit = {},
    ): Int {
        val loan = insertLoan(poolId = pool.id.value, institutionId = seller.id.value)
        val loanEntityId = loanStaticEntityId(loan.id.value)
        val participation =
            insertParticipation {
                loanId = loanEntityId
                buyerInstitution = buyer
                effectiveDate = SALE_DATE
            }
        periods.forEach { periodEnd ->
            val participationDealSnapshot = participationDealSnapshots.getValue(periodEnd)
            val loanSnapshot =
                insertLoanSnapshot {
                    period = participationDealSnapshot.period
                    dealSnapshotId = participationDealSnapshot.dealSnapshotId
                    loanId = loanEntityId
                    loanState(this)
                }
            insertAnalyticsParticipationLoanSnapshot(loanEntityId, loanSnapshot, participationDealSnapshot) {
                institutionId = buyer.id
                participationId = participation.id.value
                saleDate = SALE_DATE
                outstandingPrincipalAtOpen = openPrincipal
                outstandingPrincipalAtClose = closePrincipal
            }
        }
        return loan.id.value
    }

    /** Gives the loan's primary obligor an SSN hash and a single Experian record, received on [receivedAt]. */
    private fun insertSoleExperianRecord(
        loanId: Int,
        attributes: AnalyticsExperianDatasetTable.(InsertStatement<Number>) -> Unit,
        receivedAt: LocalDate = JAN.withDayOfMonth(1),
    ) {
        insertSsnHash(loanId)
        insertExperianRow(loanId, receivedAt, attributes)
    }

    private fun getBuyerHealth() =
        service.getBuyerHealth(
            DealQueryBuilder(db).buildQueryByParticipation(
                institutionId = buyer.id.value,
                dealId = deal.id.value,
                saleDate = null,
            ),
        )

    @ParameterizedTest(name = "ALL7330 = {0}, ALL0300 = {1} -> {2}")
    @MethodSource("experianClassifications")
    fun `should classify a loan as spotless only for ALL7330 of 100 with at least 3 trades`(
        all7330: Int,
        all0300: Int,
        expectedHealth: Percentage,
    ) {
        withDeal {
            insertSoleExperianRecord(insertParticipatedLoan(), attributes = {
                it[p13All7330] = all7330
                it[p13All0300] = all0300
            })
        }

        assertEquals(InternalBuyerHealthDto(buyerHealth = expectedHealth, delta = null, loanCount = 1), getBuyerHealth())
    }

    @Test
    fun `should weigh spotless loans by their outstanding principal at close`() {
        withDeal {
            insertSoleExperianRecord(insertParticipatedLoan(openPrincipal = Money.of("500"), closePrincipal = Money.of("300")), SPOTLESS)
            insertSoleExperianRecord(insertParticipatedLoan(openPrincipal = Money.of("500"), closePrincipal = Money.of("100")), THIN_FILE)
        }

        assertEquals(InternalBuyerHealthDto(buyerHealth = Percentage.ofOne("0.75"), delta = null, loanCount = 2), getBuyerHealth())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unhealthyLoanStreetStates")
    fun `should override a spotless Experian record when Loan Street indicates the loan is unhealthy`(
        description: String,
        loanState: (AnalyticsLoanSnapshotView) -> Unit,
    ) {
        withDeal {
            insertSoleExperianRecord(insertParticipatedLoan(), SPOTLESS)
            insertSoleExperianRecord(insertParticipatedLoan(loanState = loanState), SPOTLESS)
        }

        assertEquals(InternalBuyerHealthDto(buyerHealth = Percentage.ofOne("0.5"), delta = null, loanCount = 2), getBuyerHealth())
    }

    @Test
    fun `should not treat a loan short of 60 days delinquent as unhealthy`() {
        withDeal {
            insertSoleExperianRecord(insertParticipatedLoan { it.currentDaysDelinquent = 59 }, SPOTLESS)
        }

        assertEquals(InternalBuyerHealthDto(buyerHealth = Percentage.ONE_HUNDRED, delta = null, loanCount = 1), getBuyerHealth())
    }

    /**
     * A loan charging off in the latest period weighs in by its principal at open, as its principal
     * at close is gone, yet it is left out of the loan count.
     */
    @Test
    fun `should weigh a loan charged off in the latest period by its principal at open and exclude it from the count`() {
        withDeal {
            insertSoleExperianRecord(insertParticipatedLoan(), SPOTLESS)
            val chargedOffLoanId =
                insertParticipatedLoan(openPrincipal = Money.of("300"), closePrincipal = Money.of("0")) { it.isChargedOff = true }
            insertSoleExperianRecord(chargedOffLoanId, SPOTLESS)
        }

        assertEquals(InternalBuyerHealthDto(buyerHealth = Percentage.ofOne("0.25"), delta = null, loanCount = 1), getBuyerHealth())
    }

    /**
     * Force-excluded loans are dropped from both sides of the ratio, though they still count, as
     * they have Experian data.
     */
    @ParameterizedTest(name = "ALL7330 = {0}")
    @ValueSource(ints = [999, 994])
    fun `should exclude a loan from the health when Experian forces its exclusion`(all7330: Int) {
        withDeal {
            insertSoleExperianRecord(insertParticipatedLoan(), SPOTLESS)
            insertSoleExperianRecord(insertParticipatedLoan(closePrincipal = Money.of("300")), attributes = {
                it[p13All7330] = all7330
                it[p13All0300] = 0
            })
        }

        assertEquals(InternalBuyerHealthDto(buyerHealth = Percentage.ONE_HUNDRED, delta = null, loanCount = 2), getBuyerHealth())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inactiveAtStartOfLatestPeriod")
    fun `should leave out a loan that is not active throughout the latest period`(
        description: String,
        periods: List<LocalDate>,
        loanState: (AnalyticsLoanSnapshotView) -> Unit,
    ) {
        withDeal {
            insertSoleExperianRecord(insertParticipatedLoan(periods = listOf(FEB)), SPOTLESS)
            insertSoleExperianRecord(insertParticipatedLoan(periods = periods, loanState = loanState), THIN_FILE)
        }

        assertEquals(InternalBuyerHealthDto(buyerHealth = Percentage.ONE_HUNDRED, delta = null, loanCount = 1), getBuyerHealth())
    }

    /**
     * The record received after the period end is not yet known as of the period, and the record
     * missing attributes is skipped, so the loan falls back to the oldest, spotless record.
     */
    @Test
    fun `should use the latest complete Experian record received by the end of the period`() {
        withDeal {
            val loanId = insertParticipatedLoan()
            insertSsnHash(loanId)
            insertExperianRow(loanId, JAN.withDayOfMonth(10), SPOTLESS)
            insertExperianRow(loanId, JAN.withDayOfMonth(20)) { it[p13All7330] = 100 }
            insertExperianRow(loanId, FEB.withDayOfMonth(5), THIN_FILE)
        }

        assertEquals(InternalBuyerHealthDto(buyerHealth = Percentage.ONE_HUNDRED, delta = null, loanCount = 1), getBuyerHealth())
    }

    /**
     * A loan whose only Experian record arrives after the period has no classification for the
     * period, so it stays out of the health, yet it counts, as it has Experian data.
     */
    @Test
    fun `should count but not weigh a loan whose Experian data was received after the period`() {
        withDeal {
            insertSoleExperianRecord(insertParticipatedLoan(), SPOTLESS)
            insertSoleExperianRecord(insertParticipatedLoan(), THIN_FILE, receivedAt = FEB.withDayOfMonth(1))
        }

        assertEquals(InternalBuyerHealthDto(buyerHealth = Percentage.ONE_HUNDRED, delta = null, loanCount = 2), getBuyerHealth())
    }

    @ParameterizedTest
    @EnumSource(value = ExperianPresence::class, names = ["HASH_ONLY", "NONE"])
    fun `should leave out a loan without Experian data`(presence: ExperianPresence) {
        withDeal {
            insertSoleExperianRecord(insertParticipatedLoan(), SPOTLESS)
            val loanId = insertParticipatedLoan()
            if (presence == ExperianPresence.HASH_ONLY) insertSsnHash(loanId)
        }

        assertEquals(InternalBuyerHealthDto(buyerHealth = Percentage.ONE_HUNDRED, delta = null, loanCount = 1), getBuyerHealth())
    }

    @Test
    fun `should leave out a loan whose Experian data belongs to an obligor other than the primary`() {
        withDeal {
            insertSoleExperianRecord(insertParticipatedLoan(), SPOTLESS)
            val loanId = insertParticipatedLoan()
            insertSsnHash(loanId, targetObligorId = 1)
            insertExperianRow(loanId, JAN.withDayOfMonth(1), THIN_FILE)
        }

        assertEquals(InternalBuyerHealthDto(buyerHealth = Percentage.ONE_HUNDRED, delta = null, loanCount = 1), getBuyerHealth())
    }

    @Test
    fun `should return no values when no loan has Experian data`() {
        withDeal {
            insertParticipatedLoan()
        }

        assertEquals(InternalBuyerHealthDto(buyerHealth = null, delta = null, loanCount = 0), getBuyerHealth())
    }

    /**
     * Loans A and B span four periods, making January T3 and April T0; B turns spotless in April,
     * so their health rises from 50% to 100%. Loan C, bought in April, lacks a T3 and is left out
     * of the delta while still weighing on the top-line health, which comes to 2 of 3 loans.
     */
    @Test
    fun `should calculate the delta against T3 over the loans with four periods of data`() {
        val allPeriods = listOf(JAN, FEB, MAR, APR)
        withDeal {
            insertSoleExperianRecord(insertParticipatedLoan(periods = allPeriods), SPOTLESS)
            val loanB = insertParticipatedLoan(periods = allPeriods)
            insertSsnHash(loanB)
            insertExperianRow(loanB, JAN.withDayOfMonth(15), THIN_FILE)
            insertExperianRow(loanB, APR.withDayOfMonth(10), SPOTLESS)
            insertSoleExperianRecord(insertParticipatedLoan(periods = listOf(APR)), THIN_FILE)
        }

        assertEquals(
            InternalBuyerHealthDto(
                buyerHealth = Percentage.ofOne("0.66666667"),
                delta = Percentage.ofOne("0.5"),
                loanCount = 3,
            ),
            getBuyerHealth(),
        )
    }

    @Test
    fun `should not produce a delta when no loan has four periods of data`() {
        withDeal {
            insertSoleExperianRecord(insertParticipatedLoan(periods = listOf(MAR, APR)), SPOTLESS)
        }

        assertEquals(InternalBuyerHealthDto(buyerHealth = Percentage.ONE_HUNDRED, delta = null, loanCount = 1), getBuyerHealth())
    }
}
