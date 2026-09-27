# Comparison: original vs. claude-opus-5.5 (without reference)

Question: does each test case in `original/BuyerHealthAggregateServiceTest.kt` have a comparable test case in `without-reference/claude-opus-5.5/BuyerHealthAggregateServiceTest.kt`?

## Structural difference

The original seeds loans, participations, payments and reported state changes, then runs the real snapshot pipeline (`generateWholeDealSnapshots`) and asserts on the pipeline's output. Every test spans four periods (T3..T0) and asserts buyer health, delta and loan count together.

The opus file hand-inserts loan snapshot and participation loan snapshot rows with the state flags (`currentDaysDelinquent`, `inBankruptcy`, `inRepossession`, `isChargedOff`, `repaidInFull`) and principals set directly. Most tests use a single period, so the delta is `null` in all but one test and is never asserted as `Percentage.ZERO`. The opus tests therefore verify the SQL over snapshot rows but not that the pipeline produces those rows from loan events.

## Test-by-test

| # | Original test | Opus equivalent | Verdict |
|---|---|---|---|
| 1 | `healthy spotless portfolio scores 100 percent with zero delta` | `experianClassifications` case (100, 3 -> 100%) and `should not produce a delta when no loan has four periods of data` | **Partial**. 100% health is covered, but no opus test asserts a zero delta over four periods of history. |
| 2 | `loan reported delinquent within the T0 period drops from the numerator at its close principal` | `unhealthyLoanStreetStates` "60 days delinquent" | **Partial**. The delinquent override is covered (and opus adds the 60/59-day boundary). The close-vs-open principal distinction is only proven for a thin-file loan in `should weigh spotless loans by their outstanding principal at close`, not for a delinquent loan, since the unhealthy-state test uses equal open and close principals. Delta not asserted. |
| 3 | `loan entering repossession within the T0 period drops from the numerator at its close principal` | `unhealthyLoanStreetStates` "in repossession" | **Partial**. Same reasoning as #2. |
| 4 | `loan entering bankruptcy within the T0 period drops from the numerator at its close principal` | `unhealthyLoanStreetStates` "in bankruptcy" | **Partial**. Same reasoning as #2. |
| 5 | `loan charged off before its most recent period is excluded from the calculation` | `inactiveAtStartOfLatestPeriod` "charged off in T1" | **Covered**. Loan is dropped and count is 1. |
| 6 | `loan charging off within the T0 period drops from the numerator at its open principal` | `should weigh a loan charged off in the latest period by its principal at open and exclude it from the count` | **Covered**. Open principal 300, close 0, health 0.25, count 1. Delta not asserted. |
| 7 | `loan force-excluded from T1 and charged off within T0 weighs in the denominator at its open principal` | none | **Not covered**. No opus test combines force exclusion with a charge-off, so the precedence of charge-off over force exclusion is untested. |
| 8 | `loan repaid in full before its most recent period is excluded from the calculation` | `inactiveAtStartOfLatestPeriod` "repaid in T1" | **Covered**. |
| 9 | `single loan repaid in full within the T0 period yields nulls instead of dividing by zero` | `inactiveAtStartOfLatestPeriod` "repaid in T0" | **Partial**. Exclusion of a loan repaid in T0 is covered, but a second spotless loan is always present, so the single-loan division-by-zero degradation to nulls is not tested. The only nulls test in opus (`should return no values when no loan has Experian data`) reaches nulls by a different route. |
| 10 | `loans bought at a later closing contribute to buyer health at T0 but not to the delta` | `should calculate the delta against T3 over the loans with four periods of data` | **Covered**. Loan C with only the latest period weighs on health (2 of 3) but is excluded from the delta; count 3. Opus drives the T0 change with a new Experian row rather than a bankruptcy. |
| 11 | `double dip on loan A, with both participations contributing, and loan B goes unhealthy at T2` | none | **Not covered**. The opus fixture inserts exactly one participation per loan, so multi-participation weighting and counting a loan once are untested. An unhealthy state persisting from an earlier period (rather than set in T0) is also untested. |
| 12 | `deal without four months of history yields buyer health but no delta` | `should not produce a delta when no loan has four periods of data` | **Covered**. |
| 13 | `deal with no matching participations yields nulls` | none | **Not covered**. No opus test queries a deal or buyer that matches nothing. |
| 14 | `blemished loans contribute to the denominator only` | `experianClassifications` (100, 2 -> 0%; 99, 3 -> 0%) plus `should weigh spotless loans by their outstanding principal at close` | **Covered** across tests. Both blemish criteria are classified, and the thin-file loan in a mixed portfolio contributes to the denominator only. Zero delta not asserted. |
| 15 | `force-excluded loans vanish from numerator and denominator` | `should exclude a loan from the health when Experian forces its exclusion` (999, 994) | **Covered**. Excluded loan carries larger principal to prove it is out of the denominator; still counted. |
| 16 | `loans invisible to Experian are omitted from the calculation` | `should leave out a loan without Experian data` (HASH_ONLY, NONE) | **Covered**. Opus additionally tests a non-primary obligor's data being ignored. |
| 17 | `each period uses the latest data row received on or before its end` | `should use the latest complete Experian record received by the end of the period` and the delta test (#10 row) | **Covered** across tests. The cutoff test proves rows received after the period end are ignored; the delta test proves T3 and T0 each pick their own row (thin in January, spotless in April, delta 0.5). Opus also tests that an incomplete row is skipped and that a loan whose only row arrives after the period is counted but not weighed. |
| 18 | `portfolio with only force-excluded loans yields nulls` | none | **Not covered**. Every opus force-exclusion test keeps a spotless loan alongside, so the all-excluded case (nulls with count 1) is untested. |

## Summary

| Verdict | Count | Tests |
|---|---|---|
| Covered | 9 | 5, 6, 8, 10, 12, 14, 15, 16, 17 |
| Partial | 5 | 1, 2, 3, 4, 9 |
| Not covered | 4 | 7, 11, 13, 18 |

Recurring gaps in the opus file:

- Delta is asserted only once (as 0.5); the original asserts it in every test, including zero and negative values.
- Close-versus-open principal is proven only for a blemished loan, not for delinquent, repossessed or bankrupt loans.
- No multi-participation, no-match, all-excluded, or force-exclusion-plus-charge-off scenarios.
- Snapshot flags are set by hand, so pipeline-derived states (delinquency from loan performance, repayment computed from payments, status records) are not exercised.

Cases in the opus file with no original counterpart: 59 days delinquent not unhealthy, incomplete Experian row skipped, Experian data received after the period counted but not weighed, non-primary obligor data ignored, no loan with Experian data yields nulls with count 0.
