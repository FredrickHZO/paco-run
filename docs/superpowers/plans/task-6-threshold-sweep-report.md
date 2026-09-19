# Task 6 threshold sweep report — G6 robust outlier rejection

**Context (self-contained):** `SpeedEstimator.kt` (in `app/src/main/java/dev/fred/pacorun/service/`)
runs two independent Kalman filters (east/north). G6 is a robust-reweighting mechanism added on
top of them: it computes a joint (2-axis) squared Mahalanobis distance `jointD2` between each new
GPS fix and the filter's own prediction, converts it into a weight in `[0, 1]` via
`robustWeight(jointD2)`, and uses that weight to inflate the effective measurement variance fed to
the Kalman update — so a fix whose *position* looks anomalous (e.g. multipath) gets down-weighted
even when its *declared* GPS accuracy looks fine (the existing G7 accuracy-crash gate cannot catch
this case by construction, since it only looks at declared accuracy). G6 also feeds an OR-combined
exclusion into the existing signed-innovation EMA that drives the qLow/qHigh process-noise switch.

Three constants controlled this behavior and were left provisional pending this task's empirical
tuning:

- `robustWeightLowThresholdSigma` — below this Mahalanobis distance `d = sqrt(jointD2)`, weight is
  1.0 (no suppression).
- `robustWeightHighThresholdSigma` — at or above this `d`, weight is 0.0 (fix fully rejected from
  the Kalman update, though never from the chain itself). Linear interpolation between the two.
- `emaExclusionWeightThreshold` — a fix is excluded from the innovation EMA if its robust weight
  falls below this value, **in addition to** (never instead of) the pre-existing exclusion when
  G7's accuracy-degradation gate is engaged.

This report records the empirical sweep used to pick final values for all three, per the existing
scenario tests in `app/src/test/java/dev/fred/pacorun/service/RobustOutlierRejectionTest.kt`
(written in earlier tasks of the same plan). The sweep itself was run via a throwaway,
uncommitted test class (`ThresholdSweepTemp.kt`, deleted before this task's commit) that
re-implemented each named scenario parametrically; only its printed output is preserved here.

## Step 1 — sweeping `robustWeightLowThresholdSigma` × `robustWeightHighThresholdSigma`

Grid: low ∈ {2.0, 2.45, 3.0} × high ∈ {3.0, 3.71, 4.5} (9 combinations). Two scenarios per
combination, both from `RobustOutlierRejectionTest.kt`:

- **`goodAccuracyButAnomalousPositionIsDampened`** — a single synthetic trajectory (constant 8
  km/h, 3.0 m noise std, seed 7) with fix index 15 shifted 25 m north (declared accuracy left
  normal at 3.0 m). Measures the speed estimate at that fix; the test's own pass bound is
  `|speed − 8.0| < 5.0` km/h.
- **`robustWeightStaysNearOneOnOrdinaryNoise`** — a separate synthetic trajectory (constant 10
  km/h, 3.5 m noise std, seed 42), no injected outlier. Measures the mean `getLastRobustWeight()`
  over all fixes after the first 10 (warm-up); the test's own pass bound is mean weight `> 0.9`.

Both scenarios use a single fixed seed each, exactly as written in the existing test file — this
sweep did not re-run them across additional seeds.

| low | high | speed at outlier fix (km/h) | deviation from true 8 km/h | margin to the 5 km/h fail bound | mean weight, ordinary noise | margin above 0.9 |
|---|---|---|---|---|---|---|
| 2.0  | 3.0  | 8.254293 | 0.254403 | 4.745597 | 0.977525 | 0.077525 |
| 2.0  | 3.71 | 8.123628 | 0.123628 | 4.876372 | 0.986428 | 0.086428 |
| 2.0  | 4.5  | 8.074796 | 0.074796 | 4.925204 | 0.990599 | 0.090599 |
| 2.45 | 3.0  | 7.985293 | 0.014707 | 4.985293 | 0.986728 | 0.086728 |
| 2.45 | 3.71 | 7.985293 | 0.014707 | 4.985293 | 0.994207 | 0.094207 |
| 2.45 | 4.5  | 7.985293 | 0.014707 | 4.985293 | 0.996439 | 0.096439 |
| 3.0  | 3.0  | 7.985293 | 0.014707 | 4.985293 | 1.000000 | 0.100000 |
| 3.0  | 3.71 | 7.985293 | 0.014707 | 4.985293 | 1.000000 | 0.100000 |
| 3.0  | 4.5  | 7.985293 | 0.014707 | 4.985293 | 1.000000 | 0.100000 |

**All 9 combinations pass both scenarios' bounds.** Two observations drove the final choice:

1. Every combination with `low >= 2.45` produces the *identical* dampened speed (7.985293 km/h,
   0.0147 km/h from truth) at the outlier fix, regardless of `high`. This means the injected
   outlier's actual Mahalanobis distance is `>= 4.5` (the widest `high` tested) — it is rejected
   outright (weight 0.0) by every one of these combinations, so `high` has no further effect on
   outlier protection within the tested range. Only `low = 2.0` shows any variation, because at
   that looser low threshold a few *ordinary*-noise fixes earlier in the same trajectory
   occasionally cross it during warm-up and get slightly, spuriously down-weighted before the
   filter ever reaches the outlier — small but real spurious suppression of legitimate fixes,
   visibly reflected in `low = 2.0`'s worse (though still fully passing) numbers on both columns.
2. Among the combinations that fully and identically protect the outlier fix (`low >= 2.45`), a
   wider `high` consistently gives a better ordinary-noise weight margin (less spurious
   suppression of legitimate residuals sitting just above `low`), and `low = 3.0` gives the best
   result of all: a perfect mean weight of 1.0 on ordinary noise, tied for the best possible
   outlier-protection result.

**Chosen: `robustWeightLowThresholdSigma = 3.0`, `robustWeightHighThresholdSigma = 4.5`** — the
widest (least aggressive) combination in the tested grid that still fully protects the
injected-outlier scenario, and empirically the least likely of all 9 to react to ordinary GPS
noise.

**Caveat on generality:** this sweep uses one injected-outlier magnitude (25 m) at one seed. It
shows this specific, fairly extreme multipath-like spike is rejected outright over a fairly wide
range of thresholds — it does not by itself establish where the detection boundary lies for a
smaller or borderline anomaly. That boundary was not swept here because the plan's Step 1 does not
call for it; if a future need arises to tune sensitivity to more moderate outliers, that would
require an additional, dedicated sweep varying the injected offset itself.

## Step 2 — sweeping `emaExclusionWeightThreshold`

Grid: {0.3, 0.5, 0.7}. Two scenarios per value, both from `RobustOutlierRejectionTest.kt`, both
built on the same underlying trajectory (constant 8 km/h, 3.0 m noise std, seed 11, with fixes
20–40 given an inflated *declared* accuracy of 25.0 m vs. a normal 3.0 m — a "degraded GPS burst"
that G7's own accuracy-crash gate is designed to catch):

- **`regimeStaysQLowDuringDegradedBurstEvenWithG6Active`** — checks that every fix in the burst
  (indices 20–40, 21 fixes) stays in the `Q_LOW` process-noise regime.
- **`emaNotContaminatedImmediatelyAfterBurstEnds`** — checks that none of the 10 clean fixes
  immediately after the burst (indices 41–50) spuriously read as `Q_HIGH`.

Measured, per threshold value (identical across all three tested values):

| `emaExclusionWeightThreshold` | Q_HIGH count during burst (of 21) | peak innovation EMA during burst | margin to the 0.60 regime-switch threshold | Q_HIGH count after burst (of 10) | peak innovation EMA after burst | margin to 0.60 |
|---|---|---|---|---|---|---|
| 0.3 | 0/21 | 0.312113 | 0.287887 | 0/10 | 0.506552 | 0.093448 |
| 0.5 | 0/21 | 0.312113 | 0.287887 | 0/10 | 0.506552 | 0.093448 |
| 0.7 | 0/21 | 0.312113 | 0.287887 | 0/10 | 0.506552 | 0.093448 |

**Finding, flagged explicitly rather than papered over:** the three tested values of
`emaExclusionWeightThreshold` produce byte-for-byte identical outcomes on both scenarios. This is
not a rounding coincidence — it is because neither scenario actually exercises this constant's own
decision boundary:

- During the burst (fixes 20–40), G7's own `accuracyDegraded` flag is already `true` (declared
  accuracy 25 m vs. baseline ~3 m, ratio 2.0), which *unconditionally* excludes those fixes from
  the EMA via the pre-existing first OR-disjunct (`accuracyDegraded || jointRobustWeight <
  emaExclusionWeightThreshold`) — regardless of what the second disjunct evaluates to. The reported
  robust weight during this window is effectively irrelevant to the outcome.
- After the burst (fixes 41–50), the first fix (41) is exempt from robust-weight scrutiny entirely
  (the pre-existing post-burst exemption from Task 4), and fixes 42–50 are ordinary, anomaly-free
  fixes whose robust weight never drops meaningfully below 1.0 in the first place (no outlier is
  injected in this scenario) — so the second disjunct never approaches any of the tested threshold
  values from below either.

In short: **Step 2's two specified scenarios cannot empirically discriminate between candidate
values of `emaExclusionWeightThreshold`**, because in both, exclusion is already fully explained by
other mechanisms (G7's own gate, or the absence of any real anomaly). This is a real gap in the
sweep as specified by the plan, not an oversight in how it was run — a scenario that actually
combines *normal* declared accuracy with a *residual* anomaly weak enough to land in the
interpolation zone (as `normalAccuracyButAnomalousResidualStillExcludesFromEma` from Task 3 comes
closer to, though it doesn't vary the threshold either) would be needed to tune this constant on
real evidence.

**Chosen: `emaExclusionWeightThreshold = 0.5`** — unchanged from the provisional value. This is not
a value picked "because the sweep showed it's best" (the sweep showed no difference at all); it is
kept as the literature-neutral midpoint of the `[0, 1]` weight range in the absence of any
discriminating empirical evidence from the scenarios this task was scoped to test against.

## Step 6 — real-run comparison (G6-on vs. G6-off replay)

Per the plan's explicit correction to its own earlier draft: this step replays the same recorded
GPX fixture (`paco-run-2026-09-10-1122.gpx`, 5195 reliable fixes, loaded via
`SpeedEstimatorRealRunTest.loadPacoRunGpxFixture()`) twice — once through a normally-configured
`SpeedEstimator` (G6 active with the final tuned thresholds above), once through a `SpeedEstimator`
constructed with `robustWeightLowThresholdSigma = Double.MAX_VALUE` (which forces
`robustWeight(jointD2)` to always return 1.0, i.e. G6 structurally never engages) — and compares
the two resulting per-fix speed sequences directly. This is **not** a new Strava/MAE comparison:
the original 0.242 km/h MAE baseline for this same run was computed separately, on 2026-09-10, by
an external Python script (never committed to this repository) comparing against a Strava `.fit`
export, and is not recomputed by this task. This step only checks that turning G6 on does not
disturb the behavior that baseline already validated.

**Result:** `./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.SpeedEstimatorRealRunTest"`
— **BUILD SUCCESSFUL**, all 3 tests in the file passed (the 2 pre-existing tests plus the new
`g6ProducesNoRegressionOnTheRecordedRealRun`).

Printed output:

```
g6ProducesNoRegressionOnTheRecordedRealRun: max per-fix divergence between G6-on and G6-off
replays of the same real run = 0.0 km/h (original Strava-validated baseline for this run,
established separately and unaffected by this comparison: MAE 0.242 km/h)
```

**Max per-fix divergence between G6-on and G6-off on this run: 0.0 km/h (exact).** This run's
declared accuracy ranges 3.0–6.604 m throughout (per the existing docstring in
`SpeedEstimatorRealRunTest.kt`) with no injected or observed multipath-scale anomaly, so with the
final tuned thresholds (`low = 3.0`, `high = 4.5`), `jointD2` never crosses `low` on any fix in this
recording — G6 never engages at all on this specific run. This is the honest, expected result for
an anomaly-free recording (per the design doc's own caveat that the user's runs occur in open
countryside and don't exercise the degraded-GPS/multipath conditions G6 targets) — not a result
invented or rounded to look clean; it is exactly what the estimator produced. It confirms G6 does
not disturb the already-validated 0.242 km/h MAE baseline for this run, without re-deriving that
number here.

## Summary of final values

| Constant | Provisional value | Final value | Source |
|---|---|---|---|
| `robustWeightLowThresholdSigma` | 2.45 | **3.0** | Step 1 sweep |
| `robustWeightHighThresholdSigma` | 3.71 | **4.5** | Step 1 sweep |
| `emaExclusionWeightThreshold` | 0.5 | **0.5** (unchanged) | Step 2 sweep (uninformative — see finding above) |

Full test suite touched by this plan so far
(`./gradlew testDebugUnitTest --tests "dev.fred.pacorun.service.*" --tests "dev.fred.pacorun.eventlog.SessionEventWriterTest"`)
passes with these final values.
