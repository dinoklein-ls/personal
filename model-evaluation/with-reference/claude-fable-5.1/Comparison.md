# Comparison: original BuyerHealthAggregateServiceTest vs. claude-fable-5.1 output

Source of truth: `original/BuyerHealthAggregateServiceTest.kt` (18 tests) and
`with-reference/claude-fable-5.1/BuyerHealthAggregateServiceTest.kt` (19 test methods, 21 cases
once the two parameterized tests are expanded).

Verdict key: **Match** = same scenario and same expected values; **Comparable** = same behaviour
proven by a differently constructed scenario; **Missing** = no test in the Fable file exercises
the behaviour.

## Original tests mapped to the Fable file

| # | Original test | Fable counterpart | Verdict |
|---|---|---|---|
| 1 | `healthy spotless portfolio scores 100 percent with zero delta` | `spotless portfolio scores full health with zero delta` | Match. Same loans (A, B), same expectations (1, 0, 2). |
| 2 | `loan reported delinquent within the T0 period drops from the numerator at its close principal` | `loan reported 60 plus days delinquent within the T0 period leaves the numerator at its close principal` | Match. Identical staging (75 days delinquent on Dec 15, extra 8,000 payment) and expectations (0.8, -0.2, 2). |
| 3 | `loan entering repossession within the T0 period drops from the numerator at its close principal` | `loan entering repossession within the T0 period leaves the numerator` | Match. Identical staging and expectations. |
| 4 | `loan entering bankruptcy within the T0 period drops from the numerator at its close principal` | `loan entering bankruptcy within the T0 period leaves the numerator` | Match. Identical staging and expectations. |
| 5 | `loan charged off before its most recent period is excluded from the calculation` | `loan charged off before T0 is excluded from both the headline and the delta populations` | Match. Charge-off on Nov 15, payments stop after October, expectations (1, 0, 1). |
| 6 | `loan charging off within the T0 period drops from the numerator at its open principal` | `loan charging off within the T0 period enters the denominator at its open principal and leaves the loan count` | Match. Expectations (0.75, -0.25, 1), including the loan-count exclusion. |
| 7 | `loan force-excluded from T1 and charged off within T0 weighs in the denominator at its open principal` | none | **Missing.** No Fable test combines a force-exclusion row with a charge-off, so the precedence of charge-off over force exclusion in the T0 denominator is untested. |
| 8 | `loan repaid in full before its most recent period is excluded from the calculation` | `loan repaid in full before T0 is excluded from both the headline and the delta populations` | Match. Same lump-sum repayment on Nov 15, expectations (1, 0, 1). |
| 9 | `single loan repaid in full within the T0 period yields nulls instead of dividing by zero` | same name | Match. Identical staging and expectations (null, null, 0). |
| 10 | `loans bought at a later closing contribute to buyer health at T0 but not to the delta` | `loans bought at a later closing contribute to the headline but not to the delta` | Match. Same four loans, loan B bankrupt in T0, expectations (0.87572816, -0.25, 4). |
| 11 | `double dip on loan A, with both participations contributing, and loan B goes unhealthy at T2` | `double dip on loan A, with both participations contributing, and loan B repossessed at T2` | Match. Identical staging and expectations (0.87962963, -0.12037037, 2). |
| 12 | `deal without four months of history yields buyer health but no delta` | `deal without four months of history yields a health value but no delta` | Match. Loans C and D only, expectations (1, null, 2). |
| 13 | `deal with no matching participations yields nulls` | same name | Match. Deal 9999, expectations (null, null, 0). |
| 14 | `blemished loans contribute to the denominator only` | `loan short of spotless per Experian enters the denominator only` (parameterized: ALL7330 = 80; ALL0300 = 2) | Comparable. The original uses four loans with mirrored balances so health is exactly 0.5 with zero delta. Fable runs the two blemish routes as separate cases on loan B against spotless loan A, with a non-zero delta (0.78688525, 0.09457755, 2). Both routes to "not spotless" are covered. |
| 15 | `force-excluded loans vanish from numerator and denominator` | `force-excluded loan vanishes from the sums but not from the loan count` (parameterized: 999, 994) | Comparable. Both sentinels covered and both assert the excluded loan still counts. Fable pairs the excluded loan with a blemished loan so the expected value differs from both the "treated as spotless" and "treated as blemished" outcomes; the original only distinguishes exclusion from blemished treatment. |
| 16 | `loans invisible to Experian are omitted from the calculation` | same name | Match. Same NONE / HASH_ONLY presences, expectations (1, 0, 1). |
| 17 | `each period uses the latest data row received on or before its end` | `exclusion first received after T3 removes the loan from the T0 side only` and `blemished value received within T3 is superseded by a spotless value received within T0` | Comparable. Per-period data row selection is proven twice in Fable, but the original's exact scenario (a single loan going spotless to blemished via a row received in T1, yielding health 0 and delta -1) is not replicated. Fable never asserts a zero-but-non-null health. |
| 18 | `portfolio with only force-excluded loans yields nulls` | none | **Missing.** No Fable test produces null health with a non-zero loan count. Fable proves "exclusion does not reduce the count" only in test 15 above, where other loans keep the sums non-empty. |

## Totals

| Outcome | Count |
|---|---|
| Match | 13 |
| Comparable | 3 |
| Missing | 2 |

## Tests in the Fable file with no original counterpart

- `loans repaid or charged off within T3 are excluded from both populations`: loans sold at the August closing that terminate within September, proving five-period participations that end before T0 are filtered from both sides.
- `blemished value received within T3 stays in effect when a row missing an attribute arrives within T0`: a later row with a null ALL0300 is skipped, so the earlier blemished row carries forward.
- The thin-file (ALL0300 = 2) case is isolated as its own parameterized case rather than folded into a four-loan scenario.

## Other differences worth knowing

- **Constants.** The original uses ALL7330 = 95 for blemished and ALL0300 = 5 for spotless. Fable uses 80 and exactly 3, so its spotless loans sit on the ALL0300 >= 3 boundary.
- **Loan defaults.** The original bakes blemishes into LOAN_E (insufficient trades) and LOAN_F (blemished). Fable keeps every loan spotless by default and overrides per test.
- **SSN hash seeding.** The original inserts into `ObligorStaticSsnHashTable` directly with `ssnHashFor`. Fable calls an `insertSsnHash(loanId)` helper. Whether that helper exists cannot be verified from these two files.
- **Assertion style.** The original uses `assertAll` with three labeled assertions. Fable asserts equality against a whole `InternalBuyerHealthDto`. Both check health, delta, and loan count on every test; Fable's style reports only the first mismatch per test.
- **Class documentation.** Fable's class comment additionally spells out the spotless rule, the numerator and denominator rules, the two populations, and the rounding, none of which the original documents at class level.
