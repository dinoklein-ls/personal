package com.loanstreet.accounting.web.analytics.experian.buyerhealth

import com.loanstreet.accounting.lib.toPercentageOfOne
import com.loanstreet.accounting.sql.transaction
import com.loanstreet.accounting.web.analytics.filtering.ParticipationIdsQueryRec
import com.loanstreet.accounting.web.analytics.filtering.QUERY_PLACEHOLDER
import com.loanstreet.accounting.web.analytics.filtering.injectParticipationIdsQuery
import com.loanstreet.accounting.web.analytics.setLocalWorkingMemory
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.statements.StatementType
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.math.BigDecimal

/**
 * Service to calculate buyer health, in the form of a percentage value, based on various Experian
 * data points, for the most recent period (per participation). The service also calculates delta of
 * buyer health between the most recent period and 3 periods prior, though in scope are only those
 * participations that have at least 4 months of data in the system.
 *
 * The buyer health is calculated through a classification of borrowers into being spotless/clean, and
 * weighing them by the outstanding principal in their respective participations.
 *
 * The process of classifying borrowers is based on the Experian fields
 * [com.loanstreet.accounting.legacy.table.AnalyticsExperianDatasetTable.p13All7330] and
 * [com.loanstreet.accounting.legacy.table.AnalyticsExperianDatasetTable.p13All0300], while also taking
 * into account our knowledge of the state of participations, i.e. delinquency, bankruptcy, repossession,
 * or charged-off.
 *
 * @see HEALTH_QUERY
 */
@Service
class BuyerHealthAggregateService(
    private val primaryDatabase: Database,
    @Value("\${analytics.transaction.workmem}")
    private val workMem: Int,
) {
    companion object {
        private val logger = KotlinLogging.logger {}

        /**
         * Query for fetching the buyer health for the most recent period of data available
         * for each participation in scope, as well as the delta to buyer health 3 months
         * prior, along with the distinct loan count of the non-charged-off loans with Experian
         * values that were examined for the purpose of the health value. The period selection is
         * participation specific, hence, during the data ingest of the reporting periods, data for
         * the latest period and 3 months prior may not be for the same months (respectively)
         * across all participations, and this is done by design.
         */
        private val HEALTH_QUERY =
            """
            -- Calculates the buyer health based on the most recently available data for
            -- participations, where said participations:
            -- (1) Have begun the latest period (henceforth T0) in an active state (not repaid or charged-off)
            -- (2) Have the requisite Experian data points to facilitate the calculation
            --
            -- A delta value is also calculated between period T0 and the period 3 months prior (henceforth T3),
            -- though this calculation takes into account only participations that have at least 4 periods
            -- worth of data, therefore the delta is not to the top-line buyer health mentioned above, as the
            -- set of participations may differ.
            --
            -- The ultimate output is the buyer health from T0, the buyer health delta (calculated per above),
            -- the buyer health for T0 and T3 calculated for the purpose of the delta value, and the distinct
            -- count of non charged-off loan IDs that had Experian data (exclusion notwithstanding) and made
            -- up the population used for calculating the top-line number.
            --
            -- NOTE: Priority is given to Loan Street sourced data, i.e. charge-off / delinquency / bankruptcy /
            -- repossession indicators over the Experian data, hence if Experian data indicates a spotless buyer,
            -- we will disregard that in preference over our own indicators regarding the state of a loan.
            --
            -- NOTE 2: We are using our Loan Street sourced indicators on a per-loan basis, hence, if a certain
            -- individual (obligor) has loans with multiple credit unions, and a participation buying credit union
            -- has purchesed into more than one of said loans, the fact that the obligor is not in good standing
            -- with one of those loans, should affect the principal from all participations, thus overriding the
            -- the state of affairs according to Experian.
            with participation_ids as (
                $QUERY_PLACEHOLDER
            ), data as (
                -- This query will grab values pertinent to all the participations that are in scope,
                -- namely values that will be part of calculating the buyer health value in:
                -- (1) The most recent period T0
                -- (2) In a reference period 3 months prior, i.e. T3
                -- Note that the most recent period is relative to each participation's data availability,
                -- i.e. dependent on reporting data from the seller, hence the dates may not be consistent
                -- while we're ingesting data during the beginning of the month.
                select distinct
                    apls.participation_id,
                    apls.loan_id,
                    -- Values for the most recent period (T0)
                    last_value(apl."end") over mywin as period_end_t0,
                    last_value(apls.outstanding_principal_at_open) over mywin as outstanding_principal_at_open_t0,
                    last_value(apls.outstanding_principal_at_close) over mywin as outstanding_principal_at_close_t0,
                    last_value(als.repaid_in_full) over mywin as repaid_in_full_t0,
                    last_value(als.is_chargedoff) over mywin as is_chargedoff_t0,
                    last_value(als.in_repossession) over mywin as in_repossession_t0,
                    last_value(als.in_bankruptcy) over mywin as in_bankruptcy_t0,
                    last_value(als.current_days_delinquent >= 60) over mywin as is_delinquent_t0,
                    -- Values in T1 to help figure out if the loan started T0 in an active state
                    coalesce(nth_value(als.is_chargedoff, 2) over mywin_rev, false) as is_chargedoff_t1,
                    coalesce(nth_value(als.repaid_in_full, 2) over mywin_rev, false) as repaid_in_full_t1,
                    -- Values for period T3
                    nth_value(apl."end", 4) over mywin_rev as period_end_t3,
                    nth_value(apls.outstanding_principal_at_open, 4) over mywin_rev as outstanding_principal_at_open_t3,
                    nth_value(apls.outstanding_principal_at_close, 4) over mywin_rev as outstanding_principal_at_close_t3,
                    nth_value(als.is_chargedoff, 4) over mywin_rev as is_chargedoff_t3,
                    nth_value(als.in_repossession, 4) over mywin_rev as in_repossession_t3,
                    nth_value(als.in_bankruptcy, 4) over mywin_rev as in_bankruptcy_t3,
                    nth_value(als.current_days_delinquent >= 60, 4) over mywin_rev as is_delinquent_t3
                from participation_ids pids
                join analytics_participation_loan_snapshot apls on apls.participation_id = pids.participation_id
                join analytics_loan_snapshot als on als.id = apls.loan_snapshot_id
                join analytics_period_latest apl on apl.id = apls.period_id
                -- The `exists` clause will help limit how much we'll have to read from APLS, ALS, etc
                where exists (
                    select
                        1
                    from obligor_static_ssn_hash ossh_exists
                             join analytics_experian_dataset aed on aed.ssn_sha256 = ossh_exists.ssn_sha256
                    where ossh_exists.loan_id = pids.loan_id and ossh_exists.obligor_id = 0
                )
                window mywin as (partition by apls.participation_id order by apl."end" rows between unbounded preceding and unbounded following),
                       mywin_rev as (partition by apls.participation_id order by apl."end" desc rows between unbounded preceding and unbounded following)
            ), filtered_data as (
                -- We are interested in a data set of participations where said participations:
                -- (1) Have not started their most recent period as charged-off or repaid
                -- (2) Did not pay off in their most recent period
                select
                    participation_id,
                    loan_id,
                    period_end_t0,
                    outstanding_principal_at_open_t0,
                    outstanding_principal_at_close_t0,
                    is_chargedoff_t0,
                    (in_repossession_t0 or data.in_bankruptcy_t0 or is_delinquent_t0) as unhealthy_t0,
                    period_end_t3,
                    outstanding_principal_at_open_t3,
                    outstanding_principal_at_close_t3,
                    is_chargedoff_t3,
                    (in_repossession_t3 or data.in_bankruptcy_t3 or is_delinquent_t3) as unhealthy_t3
                from data
                where
                    -- Making sure the participation was active at the start of period T0
                    not (repaid_in_full_t1 or is_chargedoff_t1)
                    -- Making sure the participation is not paid off at the end of period T0
                    and not repaid_in_full_t0
            ), experian_attributes_per_participation_per_period as (
                -- This query will fetch the relevant Experian attributes for all the participations
                -- that are in scope, specifically identifying whether they match our criteria
                -- of being spotless, or whether they should be excluded from later sums
                -- altogether; we calculate these attributes for both T0 and T3, while
                -- relying on a fallback mechanism to select the most recent applicable
                -- Experian data to employ.
                with participation_and_periods as (
                    -- This query will operate on participations, which may be extra work
                    -- if we have multiple purchases into the same loan, however, this makes
                    -- the ability to join more precise, and provides flexibility for changes
                    -- in logic outside of this query
                    select
                        participation_id,
                        loan_id,
                        period_end_t0,
                        period_end_t3
                    from filtered_data
                ), prelim_data as (
                    -- We assemble all the Experian records, for later filtering for T0 and T3
                    select
                        pap.participation_id,
                        pap.loan_id,
                        pap.period_end_t0 as date_t0,
                        pap.period_end_t3 as date_t3,
                        aed.p13_all7330,
                        aed.p13_all0300,
                        aed.received_at
                    from participation_and_periods pap
                    join obligor_static_ssn_hash ossh on ossh.loan_id = pap.loan_id and ossh.obligor_id = 0
                    left join analytics_experian_dataset aed on aed.ssn_sha256 = ossh.ssn_sha256
                ), period_values_t0 as (
                    select distinct
                        participation_id,
                        loan_id,
                        last_value(p13_all7330) over win_t0 as p13_all7330,
                        last_value(p13_all0300) over win_t0 as p13_all0300,
                        last_value(received_at) over win_t0 as received_at
                    from prelim_data
                    where received_at <= date_t0
                        -- We exclude any null values in order to facilitate a forward propagation of older data
                        and (p13_all7330 is not null and p13_all0300 is not null)
                    window win_t0 as (partition by participation_id order by received_at rows between unbounded preceding and unbounded following)
                ), period_values_t3 as (
                    select distinct
                        participation_id,
                        loan_id,
                        last_value(p13_all7330) over win_t3 as p13_all7330,
                        last_value(p13_all0300) over win_t3 as p13_all0300,
                        last_value(received_at) over win_t3 as received_at
                    from prelim_data
                    where received_at <= date_t3
                        -- We exclude any null values in order to facilitate a forward propagation of older data
                        and (p13_all7330 is not null and p13_all0300 is not null)
                    window win_t3 as (partition by participation_id order by received_at rows between unbounded preceding and unbounded following)
                )
                select
                    pap.participation_id,
                    pap.loan_id,
                    pap.period_end_t0                                  as date_t0,
                    (pvt0.p13_all7330 = 100 and pvt0.p13_all0300 >= 3) as spotless_t0,
                    (pvt0.p13_all7330 = 999 or pvt0.p13_all7330 = 994) as force_exclusion_t0,
                    pap.period_end_t3                                  as date_t3,
                    (pvt3.p13_all7330 = 100 and pvt3.p13_all0300 >= 3) as spotless_t3,
                    (pvt3.p13_all7330 = 999 or pvt3.p13_all7330 = 994) as force_exclusion_t3
                from participation_and_periods pap
                left join period_values_t0 pvt0 on pvt0.participation_id = pap.participation_id
                left join period_values_t3 pvt3 on pvt3.participation_id = pap.participation_id
            ), health_values as (
                -- Now we join the Loan Street known state of loans with the Experian attributes
                -- and calculate the health values for T0 and T3; additionally, we return the distinct
                -- loan count, exclusive of those that charged-off during period T0.
                select
                    count(distinct case when not fd.is_chargedoff_t0 then fd.loan_id end) as loan_count,
                    sum(
                        analytics_calc_buyer_health_numerator(
                            ea4p.force_exclusion_t0,
                            fd.is_chargedoff_t0,
                            fd.unhealthy_t0,
                            ea4p.spotless_t0,
                            fd.outstanding_principal_at_close_t0
                        )
                    ) / sum(
                        analytics_calc_buyer_health_denominator(
                            ea4p.force_exclusion_t0,
                            fd.is_chargedoff_t0,
                            fd.outstanding_principal_at_open_t0,
                            fd.outstanding_principal_at_close_t0
                        )
                    ) as buyer_health_t0,
                    sum(
                        analytics_calc_buyer_health_numerator(
                            ea4p.force_exclusion_t0,
                            fd.is_chargedoff_t0,
                            fd.unhealthy_t0,
                            ea4p.spotless_t0,
                            fd.outstanding_principal_at_close_t0
                        )
                    ) filter (where outstanding_principal_at_close_t3 is not null) /
                    sum(
                        analytics_calc_buyer_health_denominator(
                            ea4p.force_exclusion_t0,
                            fd.is_chargedoff_t0,
                            fd.outstanding_principal_at_open_t0,
                            fd.outstanding_principal_at_close_t0
                        )
                    ) filter (where outstanding_principal_at_close_t3 is not null) as buyer_health_t0_for_delta,
                    sum(
                        analytics_calc_buyer_health_numerator(
                            ea4p.force_exclusion_t3,
                            fd.is_chargedoff_t3,
                            fd.unhealthy_t3,
                            ea4p.spotless_t3,
                            fd.outstanding_principal_at_close_t3
                        )
                    ) filter (where outstanding_principal_at_close_t3 is not null) /
                    sum(
                        analytics_calc_buyer_health_denominator(
                            ea4p.force_exclusion_t3,
                            fd.is_chargedoff_t3,
                            fd.outstanding_principal_at_open_t3,
                            fd.outstanding_principal_at_close_t3
                        )
                    ) filter (where outstanding_principal_at_close_t3 is not null) as buyer_health_t3_for_delta
                from filtered_data fd
                join experian_attributes_per_participation_per_period ea4p on fd.participation_id = ea4p.participation_id
            )
            -- Finally, we return the loan count, buyer health from T0, and calculate the delta to T3 while returning
            -- the delta's components
            select
                loan_count,
                round(buyer_health_t0, 8) as buyer_health,
                round(buyer_health_t0_for_delta - buyer_health_t3_for_delta, 8) as delta,
                -- Delta components provided below for debugging purposes
                round(buyer_health_t0_for_delta, 8) as buyer_health_t0_for_delta,
                round(buyer_health_t3_for_delta, 8) as buyer_health_t3_for_delta
            from health_values;
            """.trimIndent()
    }

    fun getBuyerHealth(queryRec: ParticipationIdsQueryRec): InternalBuyerHealthDto =
        primaryDatabase.transaction {
            logger.debug { "Running query for ${queryRec.description}" }

            val sql = injectParticipationIdsQuery(HEALTH_QUERY, queryRec)
            setLocalWorkingMemory(workMem)
            val result =
                exec(sql, explicitStatementType = StatementType.SELECT) { rs ->
                    check(rs.next()) { "One row is expected for queryRec: ${queryRec.description}" }

                    val buyerHealth: BigDecimal? = rs.getBigDecimal("buyer_health")
                    val delta: BigDecimal? = rs.getBigDecimal("delta")
                    val loanCount: Int = rs.getInt("loan_count")
                    InternalBuyerHealthDto(
                        buyerHealth = buyerHealth?.toPercentageOfOne(),
                        delta = delta?.toPercentageOfOne(),
                        loanCount = loanCount,
                    )
                }
            logger.debug { "Result: $result" }
            checkNotNull(result)
        }
}
