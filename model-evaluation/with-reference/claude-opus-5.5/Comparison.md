# Comparison: original `BuyerHealthAggregateServiceTest.kt` vs Claude Opus 5.5 version

Question answered: for each test in the original, does the Opus 5.5 file contain a comparable test case?
Only the two test files were used as the source of knowledge.

**Result: 18 of 19 original test cases have a comparable case in the Opus file. One is missing.**

## Original tests and their Opus counterparts

| # | Original test | Opus counterpart | Verdict |
|---|---|---|---|
| 1 | `healthy spotless portfolio scores 100 percent with zero delta` | `spotless portfolio scores full health with zero delta` | Identical: loans A and B, expects 1 / 0 / 2. Opus seeds ALL0300 = 3 (the boundary) rather than 5. |
| 2 | `loan reported delinquent within the T0 period drops from the numerator at its close principal` | `LS delinquency within T0 overrides a spotless Experian classification from 60 days` (parameterized 59 and 60 days) | Covered and extended. Same staging (payments stop after November, delinquency dated Dec 15, extra 8,000 payment), same expectation 0.8 / -0.2 / 2 for the unhealthy case. Opus additionally pins the 60-day threshold with a 59-day case that stays healthy. |
| 3 | `loan entering repossession within the T0 period drops from the numerator at its close principal` | `LS repossession or bankruptcy within T0 overrides a spotless Experian classification` (EnumSource REPOSSESSION) | Identical staging and expectation 0.8 / -0.2 / 2. |
| 4 | `loan entering bankruptcy within the T0 period drops from the numerator at its close principal` | Same Opus test, BANKRUPTCY branch | Identical staging and expectation. |
| 5 | `loan charged off before its most recent period is excluded from the calculation` | `loan charged off before T0 is excluded from both the headline and the delta` | Identical: payments through October, charge-off Nov 15, expects 1 / 0 / 1. |
| 6 | `loan charging off within the T0 period drops from the numerator at its open principal` | `loan charging off within the T0 period enters the denominator at its open principal` | Identical: expects 0.75 / -0.25 / 1. |
| 7 | `loan force-excluded from T1 and charged off within T0 weighs in the denominator at its open principal` | `LS charge-off within T0 overrides an Experian force exclusion` | Comparable with a timing difference (see below). Same expectation 0.75 / -0.25 / 1. |
| 8 | `loan repaid in full before its most recent period is excluded from the calculation` | `loan repaid in full before T0 is excluded from the calculation` | Covered and strengthened: same staging, but Opus makes loan B blemished so a failure to filter it would visibly lower the health. Expects 1 / 0 / 1. |
| 9 | `single loan repaid in full within the T0 period yields nulls instead of dividing by zero` | Same name | Identical: expects null / null / 0. |
| 10 | `loans bought at a later closing contribute to buyer health at T0 but not to the delta` | `loans bought at a later closing contribute to the headline health but not to the delta` | Identical: A, B (bankrupt in T0), C, D; expects 0.87572816 / -0.25 / 4. |
| 11 | `double dip on loan A, with both participations contributing, and loan B goes unhealthy at T2` | `double dip on loan A, with both participations contributing, and loan B repossessed at T2` | Identical: expects 0.87962963 / -0.12037037 / 2. |
| 12 | `deal without four months of history yields buyer health but no delta` | `deal without four months of history yields headline health but no delta` | Identical: C and D only; expects 1 / null / 2. |
| 13 | `deal with no matching participations yields nulls` | Same name | Identical: deal 9999; expects null / null / 0. |
| 14 | `blemished loans contribute to the denominator only` | `loan short of spotless per Experian enters the denominator only` (CsvSource: ALL7330 = 90 with 5 trades; ALL7330 = 100 with 2 trades) | Comparable with a different design (see below). Both blemish criteria are exercised. |
| 15 | `force-excluded loans vanish from numerator and denominator` | `force-excluded loan vanishes from the sums but not the loan count` (ValueSource 999, 994) | Covered: both sentinel values, health 1 / delta 0, and the assertion that excluded loans still count. Opus uses a single excluded loan per run rather than two in one portfolio. |
| 16 | `loans invisible to Experian are omitted from the calculation` | Same name | Identical: A plus E with no hash and F with hash only; expects 1 / 0 / 1. |
| 17 | `each period uses the latest data row received on or before its end` | `spotless Experian row received after T3 supersedes the baseline for the T0 side` | Comparable in intent, reversed direction, and one edge lost (see below). |
| 18 | `portfolio with only force-excluded loans yields nulls` | None | **Missing.** |

## The missing case

**Original #18: `portfolio with only force-excluded loans yields nulls`.** The original seeds a single loan carrying ALL7330 = 994 and expects buyer health null, delta null, and loan count 1. This is the only case that proves an empty denominator caused by force exclusion degrades to nulls while the loan still counts. The Opus file has two null-result tests (single loan repaid in T0, and no matching participations), but both expect a loan count of 0, so neither covers the "null health with a nonzero count" combination.

## The comparable-but-different cases

**#7, charge-off over force exclusion.** The original keeps loan B spotless at T3 and has the force-exclusion row arrive on Nov 10 (within T1), so the exclusion applies to T0 only. Opus puts the exclusion value in the baseline row dated Sep 1, so it governs every period, including T3. The T0 assertion (charge-off outranks exclusion, loan B enters the denominator at open principal) is the same, and T3 evaluates to 1 in both files. What the original additionally exercises is per-period selection of an exclusion indicator; Opus covers per-period row selection in separate tests but not for an exclusion value.

**#14, blemished loans.** The original seeds four loans in one portfolio, with the blemished pair's balances mirroring the spotless pair's, so the expected health is exactly one half with zero delta. Opus runs one blemished loan per parameterized case against one spotless loan, yielding 0.78688525 with a positive delta of 0.09457755. Both blemish criteria (ALL7330 below 100, ALL0300 below 3) are tested in each file. The Opus version is arithmetically more involved but also proves a nonzero delta from paydown alone.

**#17, latest data row per period.** The original uses one loan whose baseline row is spotless and whose Nov 10 row is blemished, giving health 0 and delta -1. Opus reverses it: loan B's baseline is blemished and the Nov 10 row is spotless, giving health 1 and delta 0.30769231 alongside spotless loan A. Both prove that T3 uses the baseline and T0 uses the later row. What Opus loses is the edge where the numerator is zero and the denominator is not, so buyer health is exactly zero rather than null. No Opus test asserts a health of zero.

## Extras in Opus not in the original

- `LS delinquency within T0 overrides ... from 60 days`, 59-day branch: a loan just under the 60-day threshold stays healthy.
- `LS delinquency within T0 does not override an Experian force exclusion`: only charge-off outranks exclusion; a delinquent excluded loan stays out of both sums.
- `LS charge-off within T0 drops out when the Experian row arrives after the T0 period end`: a charge-off with no applicable Experian classification leaves no trace in the sums.
- `Experian row received after the T3 period end applies to the T0 side only`: a hash-only loan whose sole data row arrives between T3 and T0 is classified at T0 but absent at T3.
- `Experian row with a null attribute does not supersede the baseline`: a later row missing ALL0300 is skipped when picking the row in effect.
- `LS delinquency from T3 makes the loan unhealthy on both sides of the delta`: an early-sold loan delinquent since September is unhealthy at both T3 and T0.

## Structural notes

- Both files share the same class-level setup: Testcontainers, Spring imports, the `LoanSpec` data class, the six loan constants, `seedDatabase`, and `buyerHealthForDeal`. Loan constants differ only in that the original bakes blemishes into loans E and F while Opus keeps them spotless and applies blemishes via `copy`.
- The original inserts the SSN hash directly into `ObligorStaticSsnHashTable` using `ssnHashFor`. Opus calls an `insertSsnHash` helper imported from the experian test package. Whether that helper exists cannot be determined from these two files.
- The original asserts each field separately through `assertAll` with a label per field. Opus asserts a single `InternalBuyerHealthDto` equality per test, which reports one combined diff on failure.
- Opus uses JUnit parameterized tests (`CsvSource`, `ValueSource`, `EnumSource`) to fold the original's separate repossession and bankruptcy tests, and the two blemish criteria, into single methods. The original uses plain `@Test` throughout.
- Opus's class documentation adds a paragraph on the shared headline and delta population. Otherwise the documentation is near-identical.
