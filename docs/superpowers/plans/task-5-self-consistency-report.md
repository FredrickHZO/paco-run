# Task 5 — G6 self-consistency verification (hard rule 12)

Confirms, by direct inspection of the implemented code (not by argument alone), that
`AxisKalman.apply()` never lets the filter's covariance state imply more confidence than
the actual state correction justifies.

## The invariant

For every call to `apply(measurement, effectiveMeasurementVariance, rawSignedNormalizedInnovation)`:

1. `innovationVariance = p00 + effectiveMeasurementVariance` — the SAME `effectiveMeasurementVariance`
   passed in by the caller, never a different value recomputed partway through.
2. `k0 = p00 / innovationVariance`, `k1 = p10 / innovationVariance` — both gains derived from that
   one `innovationVariance`.
3. `position`/`velocity` are corrected using `k0`/`k1`.
4. `p00`/`p01`/`p10`/`p11` are updated using the SAME `k0`/`k1` from step 2 — not a second,
   independently-computed pair.

There is exactly one place in `apply()` where `effectiveMeasurementVariance` is consumed (the
`innovationVariance` line), and every one of `k0`, `k1`, and the four covariance updates derives
from that single `innovationVariance` value. No code path scales `position += k0 * innovation` /
`velocity += k1 * innovation` by anything other than the `k0`/`k1` also used for the covariance
update in the same call. Confirmed by reading `SpeedEstimator.kt`'s `AxisKalman.apply()` top to
bottom, current as of this task's commit — see the exact line range in the file's own history for
this task.

## What this rules out

The specific failure mode hard rule 12 exists to prevent: scaling `k0 * innovation` /
`k1 * innovation` by a weight directly, while leaving the covariance update computed from the
UN-weighted `k0`/`k1` — which would let the filter believe it had fully trusted a fix it had
actually mostly ignored. That pattern does not appear anywhere in this implementation.

## What this does NOT verify

This confirms internal self-consistency (the covariance always matches what the correction actually
did), not correctness of the weight VALUES themselves (that's Task 6's empirical tuning) or of the
post-burst exemption's own correctness (verified separately in Task 4's tests, specifically the
regression test for the read-after-overwrite trap).

## Characterization results

Two tests in `RobustOutlierRejectionTest.kt` measure (do not gate on) two accepted trade-offs named
in the design doc. Verified by running
`./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.RobustOutlierRejectionTest" --info`
directly, independently of the numbers a subagent implementer reported, before recording them here.

**`oscillatingAccuracyExemptionFrequencyIsCharacterized`**: with declared GPS accuracy oscillating
between 3.0m and 25.0m on a 20-fix period (matching the baseline EMA's own time constant,
`accuracyBaselineAlpha = 0.05`), the post-burst exemption fired on **5 out of 200 fixes** (2.5%).
That is roughly one exemption per oscillation cycle, as expected structurally — every accuracy
transition from degraded back to normal is, by construction, an "immediately after a burst" fix.

**`costOfPostBurstExemptionOnAnIndependentAnomalyIsCharacterized`**: with a genuine, burst-unrelated
20m spatial outlier injected exactly at the fix the exemption rule protects, the reported speed at
that fix was **15.27 km/h** against a true speed of 8 km/h (exemption applied: true) — a real,
measurable miss the design doc's trade-off already names: this one fix gets zero G6 residual
scrutiny by design, so an independent anomaly landing there gets a diluted response instead of the
full dampening G6 would otherwise apply (compare: `goodAccuracyButAnomalousPositionIsDampened` in
Task 2, an equivalent-magnitude outlier with no exemption in play, stays within 5 km/h of the true
8 km/h). 15.27 km/h is well short of the ~30+ km/h an unweighted 20m/1s jump would otherwise imply,
so the exemption does not mean "fully accepted" — the filter's own covariance state (still elevated
from the burst) still absorbs some of it — but it is a real, quantified gap versus the non-exempt
case, accepted as the cost of not guessing a magnitude threshold for legitimate catch-up residuals.

### A bug found and fixed during this task, not by the design doc's five review rounds

The plan's original text for `costOfPostBurstExemptionOnAnIndependentAnomalyIsCharacterized` read
`estimator.getLastPostBurstExemptionApplied()` **after** the `forEachIndexed` loop over all 30
fixes had finished, instead of at fix index 20 where the outlier was injected. By the time the loop
ends, the flag reflects the *last* `accept()` call (index 29), which is never the exempt fix — so
the assertion was structurally guaranteed to see `false` regardless of whether the exemption
actually fired at index 20. Independent verification (a temporary debug trace on
`SpeedEstimator.accept()`, added and reverted for this investigation only) confirmed the *production*
logic was correct all along: `isFirstFixAfterBurst` was `true` exactly once, precisely at fix 20, in
line with hand-derived expectations from the accuracy-baseline EMA's own recursion. The fix captures
`exemptionAppliedAtOutlier` inside the `if (i == 20)` block, alongside `speedAtExemptOutlier`, the
same point where the speed itself was already (correctly) being captured. This is the same class of
mistake as Task 4's read-after-overwrite trap: checking real-time state after time has moved on,
not a flaw in `SpeedEstimator.kt` itself.
