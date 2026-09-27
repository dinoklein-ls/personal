package com.loanstreet.accounting.web.analytics.experian.buyerhealth

import com.loanstreet.accounting.institution.model.AccInstitutionId
import com.loanstreet.accounting.institution.table.LegacyInstitutionTable
import com.loanstreet.accounting.legacy.insertAnalyticsParticipationLoanSnapshot
import com.loanstreet.accounting.legacy.insertDeal
import com.loanstreet.accounting.legacy.insertDealSnapshot
import com.loanstreet.accounting.legacy.insertLegacyInstitution
import com.loanstreet.accounting.legacy.insertLoan
import com.loanstreet.accounting.legacy.insertLoanSnapshot
import com.loanstreet.accounting.legacy.insertLoanStreetInstitution
import com.loanstreet.accounting.legacy.insertParticipation
import com.loanstreet.accounting.legacy.insertParticipationDealSnapshot
import com.loanstreet.accounting.legacy.insertPeriod
import com.loanstreet.accounting.legacy.insertPool
import com.loanstreet.accounting.legacy.table.AnalyticsLoanSnapshotTable
import com.loanstreet.accounting.legacy.table.primaryTablesPlusUser
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
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.and
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises [BuyerHealthAggregateService] against a real PostgreSQL instance, seeding a single
 * buyer's purchases into a single deal per test. Unless a test says otherwise, every purchased loan
 * has an Experian record on file since before the first snapshot period, and the buyer health is
 * the outstanding principal of spotless, healthy loans over the outstanding principal of all loans.
 */
@Testcontainers
@Suppress("VarCouldBeVal")
internal class BuyerHealthAggregateServiceTest {
    companion object {
        @Container
        private val container = EmptyDBContainer()

        @JvmStatic
        @BeforeAll
        fun createSchema() {
            container.createSchema(primaryTablesPlusUser)
        }

        private const val WORK_MEM = 128

        private val JAN = YearMonth.of(2024, 1)
        private val FEB = YearMonth.of(2024, 2)
        private val MAR = YearMonth.of(2024, 3)
        private val APR = YearMonth.of(2024, 4)

        private val SALE_DATE = LocalDate.of(2023, 12, 1)

        /** Received before any snapshot period, so it applies to all of them unless a later record supersedes it. */
        private val BASELINE_RECEIPT = LocalDate.of(2023, 12, 15)

        private const val SPOTLESS_ALL7330 = 100
        private const val SPOTLESS_ALL0300 = 5
        private const val BLEMISHED_ALL7330 = 0

        private const val PRINCIPAL_600 = "600.00"
        private const val PRINCIPAL_400 = "400.00"
    }

    /** A loan the buyer bought into, keyed the way the deal query resolves it. */
    private data class Purchase(
        val loanId: Int,
        val participationId: Int,
    )

    /** The per-period rows every loan snapshot in that period hangs off. */
    private class PeriodFixture(
        val participationDealSnapshot: AnalyticsParticipationDealSnapshotView,
    ) {
        val period get() = participationDealSnapshot.period
        val dealSnapshotId get() = participationDealSnapshot.dealSnapshotId
    }

    private val service = BuyerHealthAggregateService(container.getDatabase(), WORK_MEM)
    private val periods = mutableMapOf<YearMonth, PeriodFixture>()

    private lateinit var seller: AccInstitutionView
    private lateinit var buyer: AccInstitutionView
    private lateinit var pool: LoansPoolView
    private lateinit var deal: SalesToolsDealView

    @BeforeEach
    fun seedDeal() {
        container.withCleanTables {
            insertUser()
            insertLoanStreetInstitution()
            seller = insertLegacyInstitution { name = "Seller" }
            buyer = insertLegacyInstitution { name = "Buyer" }
            pool = insertPool { institution = seller }
            deal = insertDeal { pool = this@BuyerHealthAggregateServiceTest.pool }
        }
    }

    private fun queryRec() =
        DealQueryBuilder(container.getDatabase()).buildQueryByParticipation(
            institutionId = buyer.id.value,
            dealId = deal.id.value,
            saleDate = null,
        )

    private fun periodFixture(month: YearMonth): PeriodFixture =
        periods.getOrPut(month) {
            val snapshotPeriod =
                insertPeriod {
                    start = month.atDay(1)
                    end = month.atEndOfMonth()
                }
            val dealSnapshot =
                insertDealSnapshot {
                    period = snapshotPeriod
                    dealId = deal.id
                    sellerId = seller.id.value
                }
            PeriodFixture(
                insertParticipationDealSnapshot {
                    period = snapshotPeriod
                    dealId = deal.id
                    dealSnapshotId = dealSnapshot.id
                    institutionId = buyer.id
                    saleDate = SALE_DATE
                },
            )
        }

    private fun experian(
        purchase: Purchase,
        receivedAt: LocalDate,
        all7330: Int? = SPOTLESS_ALL7330,
        all0300: Int? = SPOTLESS_ALL0300,
    ) = insertExperianRow(purchase.loanId, receivedAt) {
        it[p13All7330] = all7330
        it[p13All0300] = all0300
    }

    private fun participate(loanId: Int): Purchase {
        val participation =
            insertParticipation {
                buyerInstitution = buyer
                this.loanId = loanStaticEntityId(loanId)
                effectiveDate = SALE_DATE
            }
        return Purchase(loanId = loanId, participationId = participation.id.value)
    }

    /**
     * Sells a new loan of the deal's pool to the buyer, with the Experian attributes given
     * on file since [BASELINE_RECEIPT] (subject to [presence]).
     */
    private fun buyInto(
        presence: ExperianPresence = ExperianPresence.HASH_AND_DATA,
        all7330: Int? = SPOTLESS_ALL7330,
        all0300: Int? = SPOTLESS_ALL0300,
    ): Purchase {
        val loan = insertLoan(poolId = pool.id.value) { copy(institutionId = AccInstitutionId(seller.id.value)) }
        val purchase = participate(loan.id.value)
        if (presence != ExperianPresence.NONE) {
            insertSsnHash(purchase.loanId)
        }
        if (presence == ExperianPresence.HASH_AND_DATA) {
            experian(purchase = purchase, receivedAt = BASELINE_RECEIPT, all7330 = all7330, all0300 = all0300)
        }
        return purchase
    }

    /** A second purchase by the same buyer into an already purchased loan. */
    private fun buyAgainInto(purchase: Purchase) = participate(purchase.loanId)

    /**
     * Records the state of [purchase] at the end of [month]; the loan-level state is shared between
     * purchases into the same loan, so it is only recorded on the first call for a given loan and month.
     */
    private fun snapshot(
        month: YearMonth,
        purchase: Purchase,
        principalAtOpen: String,
        principalAtClose: String = principalAtOpen,
        daysDelinquent: Int = 0,
        chargedOff: Boolean = false,
        repaid: Boolean = false,
        inBankruptcy: Boolean = false,
        inRepossession: Boolean = false,
    ) {
        val fixture = periodFixture(month)
        val loanEntityId = loanStaticEntityId(purchase.loanId)
        val loanSnapshot =
            AnalyticsLoanSnapshotView
                .find {
                    (AnalyticsLoanSnapshotTable.periodId eq fixture.period.id) and (AnalyticsLoanSnapshotTable.loanId eq loanEntityId)
                }.firstOrNull()
                ?: insertLoanSnapshot {
                    period = fixture.period
                    dealSnapshotId = fixture.dealSnapshotId
                    loanId = loanEntityId
                    currentDaysDelinquent = daysDelinquent
                    isChargedOff = chargedOff
                    repaidInFull = repaid
                    this.inBankruptcy = inBankruptcy
                    this.inRepossession = inRepossession
                }
        insertAnalyticsParticipationLoanSnapshot(loanEntityId, loanSnapshot, fixture.participationDealSnapshot) {
            institutionId = EntityID(buyer.id.value, LegacyInstitutionTable)
            participationId = purchase.participationId
            saleDate = SALE_DATE
            outstandingPrincipalAtOpen = Money.of(principalAtOpen)
            outstandingPrincipalAtClose = Money.of(principalAtClose)
        }
    }

    private fun assertBuyerHealth(
        buyerHealth: Percentage?,
        delta: Percentage?,
        loanCount: Int,
    ) = assertEquals(
        InternalBuyerHealthDto(buyerHealth = buyerHealth, delta = delta, loanCount = loanCount),
        service.getBuyerHealth(queryRec()),
    )

    @Test
    fun `should return no health and no loans when the buyer holds nothing in the deal`() {
        assertBuyerHealth(buyerHealth = null, delta = null, loanCount = 0)
    }

    @Test
    fun `should report full health and no delta for a single spotless participation with one period`() {
        container.transaction {
            snapshot(JAN, buyInto(), PRINCIPAL_600)
        }

        assertBuyerHealth(buyerHealth = Percentage.ofOne(1), delta = null, loanCount = 1)
    }

    /**
     * A borrower is spotless when ALL7330 is exactly 100 and ALL0300 is at least 3; the second loan
     * is weighed against a spotless loan of 600, so it either keeps the health at 100% or drags it to 60%.
     */
    @ParameterizedTest(name = "{index}: ALL7330={0}, ALL0300={1} -> {2}")
    @CsvSource(
        "100, 3, 1",
        "100, 2, 0.6",
        "99, 3, 0.6",
    )
    fun `should classify a borrower as spotless only on the exact Experian criteria`(
        all7330: Int,
        all0300: Int,
        expectedHealth: String,
    ) {
        container.transaction {
            snapshot(JAN, buyInto(), PRINCIPAL_600)
            snapshot(JAN, buyInto(all7330 = all7330, all0300 = all0300), PRINCIPAL_400)
        }

        assertBuyerHealth(buyerHealth = Percentage.ofOne(expectedHealth), delta = null, loanCount = 2)
    }

    @ParameterizedTest(name = "{index}: ALL7330={0}")
    @ValueSource(ints = [999, 994])
    fun `should exclude a force-excluded borrower from the weighing but not from the loan count`(all7330: Int) {
        container.transaction {
            snapshot(JAN, buyInto(), PRINCIPAL_600)
            snapshot(JAN, buyInto(all7330 = all7330), PRINCIPAL_400)
        }

        assertBuyerHealth(buyerHealth = Percentage.ofOne(1), delta = null, loanCount = 2)
    }

    /**
     * Loan Street's own knowledge of a loan takes priority over Experian: a borrower that is spotless per
     * Experian still counts as unhealthy when the loan is 60+ days delinquent, in bankruptcy, or in repossession.
     */
    @ParameterizedTest(name = "{index}: {0}")
    @CsvSource(
        "60 days delinquent, 60, false, false, 0.6",
        "59 days delinquent, 59, false, false, 1",
        "in bankruptcy, 0, true, false, 0.6",
        "in repossession, 0, false, true, 0.6",
    )
    fun `should let Loan Street indicators override a spotless Experian record`(
        @Suppress("UnusedParameter")
        description: String,
        daysDelinquent: Int,
        inBankruptcy: Boolean,
        inRepossession: Boolean,
        expectedHealth: String,
    ) {
        container.transaction {
            snapshot(JAN, buyInto(), PRINCIPAL_600)
            snapshot(
                month = JAN,
                purchase = buyInto(),
                principalAtOpen = PRINCIPAL_400,
                daysDelinquent = daysDelinquent,
                inBankruptcy = inBankruptcy,
                inRepossession = inRepossession,
            )
        }

        assertBuyerHealth(buyerHealth = Percentage.ofOne(expectedHealth), delta = null, loanCount = 2)
    }

    /**
     * A loan charging off in the latest period contributes nothing to the healthy principal, is weighed
     * by its principal at the open of the period (its close being zero), and is left out of the loan count.
     */
    @Test
    fun `should weigh a loan charged off in the latest period by its opening principal and not count it`() {
        container.transaction {
            snapshot(JAN, buyInto(), PRINCIPAL_600)
            snapshot(month = JAN, purchase = buyInto(), principalAtOpen = PRINCIPAL_400, principalAtClose = "0.00", chargedOff = true)
        }

        assertBuyerHealth(buyerHealth = Percentage.ofOne("0.6"), delta = null, loanCount = 1)
    }

    /**
     * The excluded participation carries a non-zero, blemished balance on purpose: were it not excluded,
     * the health would drop to 60%, and the loan count would rise (unless charged off).
     */
    @ParameterizedTest(name = "{index}: {0}")
    @CsvSource(
        "repaid in the prior period, true, false, false",
        "charged off in the prior period, false, true, false",
        "repaid in the latest period, false, false, true",
    )
    fun `should exclude a participation that was not active throughout the latest period`(
        @Suppress("UnusedParameter")
        description: String,
        repaidAtT1: Boolean,
        chargedOffAtT1: Boolean,
        repaidAtT0: Boolean,
    ) {
        container.transaction {
            val active = buyInto()
            snapshot(JAN, active, PRINCIPAL_600)
            snapshot(FEB, active, PRINCIPAL_600)

            val inactive = buyInto(all7330 = BLEMISHED_ALL7330)
            snapshot(month = JAN, purchase = inactive, principalAtOpen = PRINCIPAL_400, repaid = repaidAtT1, chargedOff = chargedOffAtT1)
            snapshot(month = FEB, purchase = inactive, principalAtOpen = PRINCIPAL_400, repaid = repaidAtT1 || repaidAtT0, chargedOff = chargedOffAtT1)
        }

        assertBuyerHealth(buyerHealth = Percentage.ofOne(1), delta = null, loanCount = 1)
    }

    @ParameterizedTest(name = "{index}: {0}")
    @EnumSource(ExperianPresence::class, names = ["HASH_ONLY", "NONE"])
    fun `should leave a loan without any Experian record out of scope entirely`(presence: ExperianPresence) {
        container.transaction {
            snapshot(JAN, buyInto(), PRINCIPAL_600)
            snapshot(JAN, buyInto(presence = presence), PRINCIPAL_400)
        }

        assertBuyerHealth(buyerHealth = Percentage.ofOne(1), delta = null, loanCount = 1)
    }

    @Test
    fun `should count a loan whose only Experian record postdates the period without weighing it`() {
        container.transaction {
            snapshot(JAN, buyInto(), PRINCIPAL_600)
            val lateArrival = buyInto(presence = ExperianPresence.HASH_ONLY)
            experian(lateArrival, receivedAt = FEB.atDay(5), all7330 = BLEMISHED_ALL7330)
            snapshot(JAN, lateArrival, PRINCIPAL_400)
        }

        assertBuyerHealth(buyerHealth = Percentage.ofOne(1), delta = null, loanCount = 2)
    }

    /**
     * For each loan the Experian record in force is the most recent one received on or before the
     * period end that carries both attributes; records received afterwards are ignored, and records
     * missing an attribute are skipped in favour of the older data.
     */
    @Test
    fun `should apply the latest complete Experian record received on or before the period end`() {
        container.transaction {
            val fallsBackToBaseline = buyInto()
            experian(purchase = fallsBackToBaseline, receivedAt = JAN.atDay(20), all7330 = null, all0300 = null)
            experian(fallsBackToBaseline, receivedAt = MAR.atDay(5), all7330 = BLEMISHED_ALL7330)

            val supersededBeforePeriodEnd = buyInto()
            experian(supersededBeforePeriodEnd, receivedAt = FEB.atDay(10), all7330 = BLEMISHED_ALL7330)

            val supersededOnPeriodEnd = buyInto()
            experian(supersededOnPeriodEnd, receivedAt = FEB.atEndOfMonth(), all7330 = BLEMISHED_ALL7330)

            for (month in listOf(JAN, FEB)) {
                snapshot(month, fallsBackToBaseline, PRINCIPAL_600)
                snapshot(month, supersededBeforePeriodEnd, PRINCIPAL_400)
                snapshot(month, supersededOnPeriodEnd, "200.00")
            }
        }

        assertBuyerHealth(buyerHealth = Percentage.ofOne("0.5"), delta = null, loanCount = 3)
    }

    /**
     * The latest period is selected per participation, so a participation whose data lags a month
     * is still weighed in, on the basis of its own latest period.
     */
    @Test
    fun `should evaluate each participation as of its own latest period`() {
        container.transaction {
            val blemishedByFebruary = buyInto()
            experian(blemishedByFebruary, receivedAt = FEB.atDay(10), all7330 = BLEMISHED_ALL7330)
            snapshot(JAN, blemishedByFebruary, PRINCIPAL_600)
            snapshot(FEB, blemishedByFebruary, PRINCIPAL_600)

            snapshot(JAN, buyInto(), PRINCIPAL_400)
        }

        assertBuyerHealth(buyerHealth = Percentage.ofOne("0.4"), delta = null, loanCount = 2)
    }

    @Test
    fun `should count a loan once while weighing every purchase into it`() {
        container.transaction {
            val first = buyInto()
            snapshot(JAN, first, PRINCIPAL_600)
            snapshot(JAN, buyAgainInto(first), PRINCIPAL_400)
            snapshot(JAN, buyInto(all7330 = BLEMISHED_ALL7330), "1000.00")
        }

        assertBuyerHealth(buyerHealth = Percentage.ofOne("0.5"), delta = null, loanCount = 2)
    }

    @Test
    fun `should omit the delta when no participation has four periods of data`() {
        container.transaction {
            val purchase = buyInto()
            for (month in listOf(FEB, MAR, APR)) {
                snapshot(month, purchase, PRINCIPAL_600)
            }
        }

        assertBuyerHealth(buyerHealth = Percentage.ofOne(1), delta = null, loanCount = 1)
    }

    /**
     * The delta compares the latest period against the one three months prior, over the participations
     * having both; the top-line health covers the newer participation as well, so the two populations differ.
     */
    @Test
    fun `should compute the delta over participations with four periods while the health covers all`() {
        container.transaction {
            val spotlessThroughout = buyInto()
            val blemishedByFebruary = buyInto()
            experian(blemishedByFebruary, receivedAt = FEB.atDay(10), all7330 = BLEMISHED_ALL7330)
            for (month in listOf(JAN, FEB, MAR, APR)) {
                snapshot(month, spotlessThroughout, PRINCIPAL_600)
                snapshot(month, blemishedByFebruary, PRINCIPAL_400)
            }

            val recentPurchase = buyInto()
            snapshot(MAR, recentPurchase, "1000.00")
            snapshot(APR, recentPurchase, "1000.00")
        }

        // Health: (600 + 1000) / (600 + 400 + 1000); delta: 600 / 1000 in April against 1000 / 1000 in January.
        assertBuyerHealth(buyerHealth = Percentage.ofOne("0.8"), delta = Percentage.ofOne("-0.4"), loanCount = 3)
    }

    @Test
    fun `should reflect a cured delinquency as a positive delta`() {
        container.transaction {
            val spotlessThroughout = buyInto()
            val delinquentInJanuary = buyInto()
            for (month in listOf(JAN, FEB, MAR, APR)) {
                snapshot(month, spotlessThroughout, PRINCIPAL_600)
                snapshot(month = month, purchase = delinquentInJanuary, principalAtOpen = PRINCIPAL_400, daysDelinquent = if (month == JAN) 90 else 0)
            }
        }

        assertBuyerHealth(buyerHealth = Percentage.ofOne(1), delta = Percentage.ofOne("0.4"), loanCount = 2)
    }
}
