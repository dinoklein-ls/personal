# Comparison: original `BuyerHealthAggregateServiceTest.kt` vs Kimi K3 version

Evaluates whether each test case in `original/BuyerHealthAggregateServiceTest.kt` has a comparable test case in `with-reference/kimi-k3/BuyerHealthAggregateServiceTest.kt`. Only those two files were used as the source of knowledge.

**Overall:** 14 of the 18 original tests have a direct counterpart in the Kimi file with the same seeding and the same expected values. 2 more are covered in intent but through a different mechanism. 2 original tests have no counterpart at all.

## Original tests and their Kimi counterparts

| # | Original test | Kimi counterpart | Verdict |
|---|---|---|---|
| 1 | healthy spotless portfolio scores 100 percent with zero delta | clean spotless portfolio scores full health with zero delta | Direct match, same seed and expectations |
| 2 | loan reported delinquent within T0 drops from numerator at close principal | loan reported 60 plus days delinquent within the T0 period leaves the numerator but stays in the denominator at its close principal | Direct match |
| 3 | loan entering repossession within T0 at close principal | loan entering repossession within the T0 period leaves the numerator | Direct match, same post-state payment |
| 4 | loan entering bankruptcy within T0 at close principal | loan entering bankruptcy within the T0 period leaves the numerator | Direct match |
| 5 | loan charged off before its most recent period is excluded | loan charged off before T0 is excluded from the calculation entirely | Direct match |
| 6 | loan charging off within T0 drops from numerator at open principal | loan charging off within the T0 period enters the denominator at its open principal and leaves the loan count | Direct match, loan count 1 asserted in both |
| 7 | loan force-excluded from T1 and charged off within T0 weighs in the denominator at its open principal | none | **Missing** |
| 8 | loan repaid in full before its most recent period is excluded | loan repaid in full before T0 is excluded from the calculation entirely | Direct match |
| 9 | single loan repaid in full within T0 yields nulls | single loan repaid in full within the T0 period yields nulls instead of dividing by zero | Direct match |
| 10 | loans bought at a later closing contribute at T0 but not to the delta | loans bought at a later closing contribute to the headline but not to the delta | Comparable, different mechanism |
| 11 | double dip on loan A, loan B unhealthy at T2 | double dip on loan A, with both participations contributing, and loan B repossessed at T2 | Direct match, same expected values |
| 12 | deal without four months of history yields buyer health but no delta | deal without four months of history yields a health value but no delta | Direct match |
| 13 | deal with no matching participations yields nulls | deal with no matching participations yields nulls | Direct match |
| 14 | blemished loans contribute to the denominator only | loan clean per Loan Street data but not spotless per Experian leaves the numerator, plus the parameterized spotless classification boundary values | Covered, Kimi is more precise on the boundary |
| 15 | force-excluded loans vanish from numerator and denominator | force-excluded loans vanish from the sums but not the loan count, parameterized over 999 and 994 | Covered, both sentinels and the count assertion present |
| 16 | loans invisible to Experian are omitted | loans invisible to Experian are omitted from the calculation | Direct match |
| 17 | each period uses the latest data row received on or before its end | exclusion first received after T3 removes the loan from the T0 side only | Comparable, different mechanism |
| 18 | portfolio with only force-excluded loans yields nulls | none | **Missing** |

## The two missing cases

- **Charge-off precedence over force exclusion (original test 7).** The original seeds a late 999 row within T1 and a charge-off within T0, and asserts the charged-off loan still weighs in at open principal, giving 0.75 rather than 1. Kimi never combines an exclusion sentinel with a charge-off. Its closest tests are the late-exclusion test on loan E, which has no charge-off, and the charge-off with no applicable Experian row, which tests the STRICT null path rather than exclusion precedence. The precedence rule is stated in Kimi's class comment but never exercised.
- **All loans force-excluded yields nulls with a nonzero loan count (original test 18).** The original seeds a single 994 loan and asserts null health, null delta, and a count of 1. Kimi's null cases arise only from an empty population, so the case where exclusion empties the sums while the loan count stays positive is untested.

## The two comparable-but-different cases

- **Later closings (test 10).** The original makes loan B bankrupt within T0 and expects 0.87572816 with a delta of -0.25. Kimi makes loan B non-spotless per Experian and expects 0.89662028 with a delta of 0.09457755. Both assert the headline includes loans C and D, the delta excludes them, and the count is 4. The original additionally mixes in a T0 state change, which Kimi's variant does not.
- **Per-period data row selection (test 17).** The original uses one loan with a late blemished row and expects health to fall to zero with a delta of -1. Kimi proves the same row-selection rule with a late exclusion sentinel across three loans, showing the T3 side still includes the loan while the T0 side drops it. The rule is covered, but the health-to-zero path with a non-sentinel late row is not.

## Extras in Kimi not in the original

These do not affect the verdict above but are worth noting. Kimi adds:

- The parameterized spotless boundary test at exactly ALL7330 100 versus 99 and ALL0300 3 versus 2.
- The late-exclusion test (exclusion first received after T3 removes the loan from the T0 side only).
- Two tests of the STRICT function behaviour when no Experian classification applies to a period: a T0 charge-off with a row dated after T0, and a T3 delinquency with the row arriving after the T3 period end.

## Structural notes

- Kimi asserts one whole data transfer object per test, while the original uses per-field assertions grouped with `assertAll`. A failure in Kimi reports the full object rather than the individual field.
- Kimi expects full health as `Percentage.ONE_HUNDRED` where the original builds it from `Percentage.ofOne("1")`. Whether those are equal cannot be confirmed from these two files alone.
- Kimi calls an `insertSsnHash` helper to insert the SSN hash, while the original inserts into `ObligorStaticSsnHashTable` directly. This is a fixture difference only.
