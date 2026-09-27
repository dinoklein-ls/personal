# Comparison: original `BuyerHealthAggregateServiceTest.kt` vs DeepSeek V3.2 output

Question answered: for each test in `original/BuyerHealthAggregateServiceTest.kt`, does
`with-reference/deepseek-v3.2/BuyerHealthAggregateServiceTest.kt` contain a comparable test?
"Comparable" means the same seeded scenario and the same asserted values. Only the two files were used.

Both files share the same fixture: identical `LoanSpec`, loans A–F, dates, `seedDatabase`, and
`buyerHealthForDeal`. The differences are in which scenarios are staged and what is asserted.

Test counts: original 18, DeepSeek 15.

## Summary

| Verdict | Count | Original tests |
|---|---|---|
| Equivalent (same scenario, same assertions) | 5 | 1, 8, 9, 12, 13 |
| Same scenario, different expected values | 5 | 2, 3, 4, 5, 6 |
| Partial (weaker or subset scenario) | 3 | 10, 11, 15 |
| No comparable test | 5 | 7, 14, 16, 17, 18 |

## Test-by-test

| # | Original test | DeepSeek counterpart | Verdict | Notes |
|---|---|---|---|---|
| 1 | `healthy spotless portfolio scores 100 percent with zero delta` | `spotless portfolio scores perfect buyer health with zero delta` | Equivalent | Same seed (A, B); 1 / 0 / 2 in both. |
| 2 | `loan reported delinquent within the T0 period drops from the numerator at its close principal` | `loan reported 60 plus days delinquent within the T0 period is unhealthy and reduces buyer health` | Same scenario, different values | Original: 0.8 / -0.2 / 2 (loan B at close, 24,000). DeepSeek: 0.75 / 0 / 2 (loan B at open, 32,000). The original's comment explicitly names 0.75 as the open-principal answer it is guarding against. DeepSeek's delta of 0 also contradicts its own comment (T0 = 0.75, T3 = 1 implies a non-zero delta). |
| 3 | `loan entering repossession within the T0 period drops from the numerator at its close principal` | `loan entering repossession within the T0 period counts as unhealthy` | Same scenario, different values | Original 0.8 / -0.2 / 2; DeepSeek 0.75 / 0 / 2. Same open-vs-close and delta disagreement as #2. |
| 4 | `loan entering bankruptcy within the T0 period drops from the numerator at its close principal` | `loan entering bankruptcy within the T0 period counts as unhealthy` | Same scenario, different values | Original 0.8 / -0.2 / 2; DeepSeek 0.75 / 0 / 2. Same disagreement as #2. |
| 5 | `loan charged off before its most recent period is excluded from the calculation` | `loan charged off before T0 is excluded from the headline but enters the delta at its charge-off principal` | Same scenario, different values | Both 1 / ? / 1. Original delta 0 (loan B fully filtered out). DeepSeek delta -0.28358209 (loan B kept in the delta population at 38,000). |
| 6 | `loan charging off within the T0 period drops from the numerator at its open principal` | `loan charging off within the T0 period is excluded from numerator but included in denominator` | Same scenario, different values | Both 0.75 / -0.25. Original loanCount 1 (charged-off loan excluded from count); DeepSeek loanCount 2. |
| 7 | `loan force-excluded from T1 and charged off within T0 weighs in the denominator at its open principal` | none | Missing | DeepSeek never stages a force-exclusion row at `EXPERIAN_LATE_DATA_DATE` (constant declared, unused). Its nearest test, `LS charge-off within T0 counts even when the Experian row arrives after the T0 period end`, is a different scenario (HASH_ONLY loan plus a spotless row dated after T0) and asserts 0.75 / -0.25 / 2, which conflicts with original #16 (HASH_ONLY loans are omitted) and original #6 (loanCount 1). |
| 8 | `loan repaid in full before its most recent period is excluded from the calculation` | `loan repaid in full before T0 is excluded from both the headline and the delta populations` | Equivalent | Same staging; 1 / 0 / 1 in both. |
| 9 | `single loan repaid in full within the T0 period yields nulls instead of dividing by zero` | same name | Equivalent | Same staging; null / null / 0 in both. |
| 10 | `loans bought at a later closing contribute to buyer health at T0 but not to the delta` | `loans bought at a later closing contribute to the headline rates but not to the delta` | Partial | Original makes loan B bankrupt in T0 so the headline (0.87572816) and delta (-0.25) differ, proving C and D are excluded from the delta. DeepSeek seeds all four loans healthy and asserts 1 / 0 / 4, which would hold whether or not C and D entered the delta, so it cannot prove the claim in its name. |
| 11 | `double dip on loan A, with both participations contributing, and loan B goes unhealthy at T2` | `loan repossessed at T2 remains unhealthy through T0` | Partial | DeepSeek covers the T2 repossession persisting to T0 (0.78512397 / -0.21487603 / 2, consistent with the original's arithmetic for a single participation). The double participation on loan A via `additionalSaleDates` is never exercised in DeepSeek. |
| 12 | `deal without four months of history yields buyer health but no delta` | `deal without four months of history yields headline buyer health but no delta` | Equivalent | Same seed (C, D); 1 / null / 2 in both. |
| 13 | `deal with no matching participations yields nulls` | same name | Equivalent | Same; null / null / 0 in both. |
| 14 | `blemished loans contribute to the denominator only` | none (nearest: `non-spotless Experian classification overridden by clean LS data`) | Missing, and contradicted | Original seeds E (ALL0300 = 2) and F (ALL7330 = 95) and asserts 0.5 / 0 / 4, i.e. blemished loans stay in the denominator. DeepSeek's nearest test seeds B with p13All0300 = 2 and asserts 1 / 0 / 2, the opposite behaviour. DeepSeek never tests ALL7330 < 100 at all (no blemished-7330 constant). |
| 15 | `force-excluded loans vanish from numerator and denominator` | `loan with Experian force exclusion is omitted from calculation` | Partial, conflicting count | Original: A, B, E(999), F(994) with 1 / 0 / loanCount 4 (excluded loans still count). DeepSeek: A, B(999) with 1 / 0 / loanCount 1. Sentinel 994 is declared in DeepSeek but never used. |
| 16 | `loans invisible to Experian are omitted from the calculation` | none | Missing | `ExperianPresence.NONE` is never used in DeepSeek. HASH_ONLY appears only in the after-T0 test, where the loan is asserted to still weigh in and count, contrary to this original test. |
| 17 | `each period uses the latest data row received on or before its end` | none | Missing | No DeepSeek test stages a second Experian row between T3 and T0. |
| 18 | `portfolio with only force-excluded loans yields nulls` | none | Missing | No DeepSeek test covers the all-excluded case (null / null / loanCount 1). |

## DeepSeek tests with no original counterpart

- `LS charge-off within T0 counts even when the Experian row arrives after the T0 period end`: new scenario. Its expectations (loan B weighs in and counts) conflict with original #16 and #6.
- `non-spotless Experian classification overridden by clean LS data`: asserts the opposite of original #14.

## Other observations

- DeepSeek declares but never uses `EXPERIAN_LATE_DATA_DATE`, `FORCE_EXCLUDE_7330_2`, `LOAN_E`, `LOAN_F`, and `additionalSaleDates`, so the per-period row selection, the 994 sentinel, the blemished loans, and the double-dip case are all unreachable by its suite.
- DeepSeek asserts via a single `assertEquals` on the whole DTO; the original uses `assertAll` with labelled per-field assertions. This is stylistic and does not affect coverage.
- Where the two files disagree on expected values, the disagreements cluster on three rules the original states explicitly: delinquent/repossessed/bankrupt loans enter the denominator at close principal (not open), a loan charging off within T0 is excluded from the loan count, and force-excluded loans still count while blemished loans stay in the denominator.
