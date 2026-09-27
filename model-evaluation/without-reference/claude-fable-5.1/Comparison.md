# Comparison: original vs. Claude Fable 5.1 (without reference)

Source of truth: `original/BuyerHealthAggregateServiceTest.kt` (18 tests) and
`without-reference/claude-fable-5.1/BuyerHealthAggregateServiceTest.kt` (15 test methods, 26 cases including parameterized variants).

## Structural difference

The original drives the real snapshot pipeline (`generateWholeDealSnapshots`) from loans, participations, payments and
reported state changes inside a Spring test context, and almost every test spans four periods so that `delta` is asserted.
The Fable file hand-inserts snapshot rows (period, deal snapshot, loan snapshot, participation loan snapshot) and mostly
uses a single period, so `delta` is only exercised by three tests. Consequences:

- Fable cannot verify anything the pipeline computes (e.g. that a charged-off loan's close principal is zeroed, or that
  repayment is derived from payments). It sets open/close principal and flags directly.
- Every original test that asserts a non-null `delta` alongside an unhealthy state has, at best, only its `buyerHealth`
  counterpart in Fable.

## Test-by-test

| # | Original test | Fable counterpart | Verdict |
|---|---|---|---|
| 1 | healthy spotless portfolio scores 100 percent with zero delta | `should report full health and no delta for a single spotless participation with one period` | **Partial.** Health = 1 is covered, but with one period and `delta = null`. No Fable test asserts `delta = 0` for a stable healthy portfolio. |
| 2 | loan reported delinquent within the T0 period drops from the numerator at its close principal | `should let Loan Street indicators override a spotless Experian record` (case "60 days delinquent") | **Partial.** Delinquency removes the loan from the numerator and health = 0.6 is checked, but `principalAtClose` defaults to `principalAtOpen`, so the open-vs-close distinction the original pins down is not tested. No delta. Fable adds a 59-day boundary case the original lacks. |
| 3 | loan entering repossession within the T0 period drops from the numerator at its close principal | same test, case "in repossession" | **Partial.** Same caveats as #2 (open == close, no delta). |
| 4 | loan entering bankruptcy within the T0 period drops from the numerator at its close principal | same test, case "in bankruptcy" | **Partial.** Same caveats as #2. |
| 5 | loan charged off before its most recent period is excluded from the calculation | `should exclude a participation that was not active throughout the latest period` (case "charged off in the prior period") | **Comparable.** Fable gives the excluded loan a blemished record and non-zero balance so the exclusion is observable in health and count. No delta. |
| 6 | loan charging off within the T0 period drops from the numerator at its open principal | `should weigh a loan charged off in the latest period by its opening principal and not count it` | **Comparable.** Open = 400, close = 0, health = 0.6, count excludes the loan. No delta. |
| 7 | loan force-excluded from T1 and charged off within T0 weighs in the denominator at its open principal | none | **Missing.** No Fable test combines force exclusion with charge-off, so the precedence of charge-off over force exclusion in the denominator is untested. |
| 8 | loan repaid in full before its most recent period is excluded from the calculation | `should exclude a participation that was not active throughout the latest period` (case "repaid in the prior period") | **Comparable.** No delta. |
| 9 | single loan repaid in full within the T0 period yields nulls instead of dividing by zero | same test, case "repaid in the latest period" | **Partial.** Fable shows the loan is excluded, but a second active loan is always present, so the zero-denominator / null-degrade path is never reached. The only null-result test in Fable is the empty-deal one. |
| 10 | loans bought at a later closing contribute to buyer health at T0 but not to the delta | `should compute the delta over participations with four periods while the health covers all` | **Comparable.** A two-period participation enters health (0.8) but not delta (-0.4); count includes all three. |
| 11 | double dip on loan A, with both participations contributing, and loan B goes unhealthy at T2 | `should count a loan once while weighing every purchase into it` | **Mostly comparable.** Two participations in one loan are both weighed and the loan counted once. Not covered: an unhealthy state entered in an earlier period persisting to T0, five-period history, or delta. |
| 12 | deal without four months of history yields buyer health but no delta | `should omit the delta when no participation has four periods of data` | **Comparable.** Three periods, health = 1, delta = null. |
| 13 | deal with no matching participations yields nulls | `should return no health and no loans when the buyer holds nothing in the deal` | **Comparable.** Fable seeds no data at all rather than data in another deal, so it does not prove the deal filter itself, but the null/0 result is asserted. |
| 14 | blemished loans contribute to the denominator only | `should classify a borrower as spotless only on the exact Experian criteria` (cases `100,2` and `99,3`) | **Comparable.** Both blemish reasons (insufficient trades, ALL7330 < 100) are covered, plus the exact spotless boundary `100,3`. No delta. |
| 15 | force-excluded loans vanish from numerator and denominator | `should exclude a force-excluded borrower from the weighing but not from the loan count` (999 and 994) | **Comparable.** Both sentinels; health stays 1 and count includes the loan. |
| 16 | loans invisible to Experian are omitted from the calculation | `should leave a loan without any Experian record out of scope entirely` (HASH_ONLY and NONE) | **Comparable.** Loan drops from both health and count. |
| 17 | each period uses the latest data row received on or before its end | `should apply the latest complete Experian record received on or before the period end`; also `should compute the delta over participations with four periods…` (late blemish drives delta -0.4) | **Comparable, and broader.** Fable also checks a record received exactly on the period end, a record received after it, and a record with null attributes falling back to older data. |
| 18 | portfolio with only force-excluded loans yields nulls | none | **Missing.** Every Fable force-exclusion scenario keeps a spotless loan in the mix, so the all-excluded null result is untested. |

## Summary

| Verdict | Count | Original tests |
|---|---|---|
| Comparable | 10 | 5, 6, 8, 10, 12, 13, 14, 15, 16, 17 (17 broader) |
| Mostly comparable | 1 | 11 |
| Partial | 5 | 1, 2, 3, 4, 9 |
| Missing | 2 | 7, 18 |

Recurring gaps in the Fable file:

- `delta` is never asserted for any unhealthy state (delinquent, repossession, bankruptcy, charge-off) and never asserted as exactly zero.
- Open-vs-close principal selection is only pinned down for charge-off; for delinquent/repossessed/bankrupt loans open equals close.
- Neither null-degrade case with data present (single loan repaid in T0; only force-excluded loans) is covered.
- The force-exclusion + charge-off precedence rule is absent.

Cases the Fable file adds that the original does not have:

- 59-day delinquency boundary (still healthy).
- An Experian record that postdates the period: loan is counted but not weighed.
- Experian record received on the period end date, after the period end, and with null attributes (fallback to older record).
- Each participation evaluated as of its own latest period when data lags.
- A cured delinquency producing a positive delta.
- Repaid-in-latest-period alongside an active loan (health and count unaffected).
