# SpeedEstimator: accuracy-aware gate + diagnostic GPX export

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. GPS-critical code: TDD + dual review (self + independent fresh subagent) on every task, no exceptions.

**Context:** this is a follow-up to the 12-task plan in `docs/superpowers/plans/2026-09-10-speed-estimator-kalman.md` (already complete — read its final state and `docs/superpowers/specs/2026-09-10-speed-accuracy-design.md`'s status line for the full history). That plan deliberately left two things out of scope, to be done now:

- **Task 1** fixes a real behavioral finding from that plan's Task 8 review: `SpeedEstimator`'s adaptive process-noise switch (`qLow`/`qHigh`) reacts to the *size* of the innovation only, not to *why* it's large. A sudden, severe GPS accuracy degradation (no real motion change) makes the raw position jump just like a real acceleration would, so the switch can mistake one for the other — measured: transient readings up to ~22 km/h against a true 8 km/h, self-correcting within ~5 fixes, but real enough that `PaceEvaluator`'s `confirmationSamples=2` gate could act on it once this is ever wired into production (still out of scope here).
- **Task 2** adds `SpeedEstimator`'s output to the diagnostic GPX export (already used to compare Paco Run against Strava), purely for post-run analysis. Zero production risk: it never reaches `PaceEvaluator`, `SessionCoordinator`, or anything the user sees/hears during a run.

## Global Constraints

- **Do not retune `qLow`, `qHigh`, `innovationThreshold`, or `innovationEmaAlpha`.** They are validated against real-world data (a recorded run replayed against Strava: MAE 0.25-0.27 km/h, correlation 0.94-0.96). Task 1 adds an *additional, independent* gate on top of the existing switch — it does not change the switch's own tuning.
- **Do not modify `DistanceAccumulator` or `RunSessionStore.kt`'s existing classes.** `SpeedEstimator` stays addable/removable independently.
- **Never use `location.speed`/`LocationSample.speedKmh` inside `SpeedEstimator`.**
- **Task 2 is diagnostic-only.** `PendingSessionCoordinator`/`SessionCoordinator`.`tick()`, `PaceEvaluator`, cue playback, and `RunSessionState` must not change. `SpeedEstimator`'s output for Task B reaches only `TrackPoint`/the exported GPX file — nothing the user sees or hears during a run.
- **No new git branches/worktrees.** Everything happens on `feature/paco-run-m3e` in the existing worktree.
- Standing discretion from the prior plan still applies: exact thresholds, EMA smoothing constants, and test tolerances are yours to determine empirically via TDD (not to invent up front) — this plan states the *mechanism*, not the exact numbers.

---

## Task 1: accuracy-aware gate on the adaptive switch

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt`
- Modify: `app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorScenarioTest.kt` (tighten the existing degraded-accuracy-burst scenario from Task 8 of the prior plan)

### Mechanism (agreed, not discretionary — implement this design)

Track a short-memory baseline of `horizontalAccuracyMeters` across fixes (an EMA is one reasonable choice — a single-previous-fix baseline is explicitly a poor choice here and should not be used as-is: see the note below on why). On each fix, before deciding `processNoise`:

- Compute how much worse the current fix's accuracy is than the recent baseline (e.g. a ratio). If it's degraded past a threshold (a starting guess of 1.5-2x is reasonable, but derive the actual value with TDD against the tightened Task 8 scenario, not by assumption):
  - **(a)** Force `processNoise = qLow` for this fix's `predict()` call, regardless of what the innovation-EMA-based decision would otherwise say.
  - **(b)** Do **not** feed this fix's innovation into `eastInnovationEma`/`northInnovationEma` — leave them at their pre-fix values. (The Kalman `update()` call itself still runs normally, using the fix's real, degraded `measurementVariance` — that part of the math is untouched and correctly down-weights the noisy point. Only the EMA-feeding step is skipped, so this fix's necessarily-large innovation doesn't poison the *next* fix's switch decision.)
- Always update the accuracy baseline with the current fix's accuracy, in every case (including during a degraded fix) so it tracks reality rather than freezing on stale "good" data forever.

**Why a single-previous-fix baseline is the wrong choice, and worth stating explicitly so you don't reach for it first:** the Task 8 finding was a *multi-fix* burst (5-10 seconds of degraded accuracy), and the transient peaked partway through it (fix 4-6 of 8), not on the very first degraded fix. A baseline that's just "the last fix's accuracy" would compare fix 2 of the burst against fix 1 (already degraded) — the ratio would only look large on the *first* fix of the burst, then immediately read as "normal" for every fix after, leaving the actual peak (later in the burst) completely unprotected. A baseline with real memory (slow enough that it doesn't itself jump to the degraded value within one or two fixes) is what makes this gate useful across the *whole* burst, not just its first instant. Confirm this holds with your own test — a burst-length synthetic scenario where the gate visibly protects fixes throughout the burst, not just the first one — before considering the mechanism done.

**A risk worth checking empirically, not assuming:** real, non-degraded GPS accuracy already fluctuates fix-to-fix (the real 2026-09-10 recording ranges roughly 3.0-6.6m fix to fix — a jump from 3.0 to 6.6m alone is already a 2.2x ratio). Check your chosen threshold and baseline smoothing against `app/src/test/resources/paco-run-2026-09-10-1122.gpx` (already committed, replayed by `SpeedEstimatorRealRunTest.kt`) to make sure ordinary jitter in real data doesn't spuriously trip the gate on every other fix — that would silently degrade the filter's real-acceleration responsiveness for no reason, on real runs, without any test catching it (the synthetic scenario tests all use *constant* accuracy per segment, so they cannot surface this risk on their own).

### Suggested task breakdown (adjust if a different split reads more cleanly, but keep each step TDD: failing test first)

- [ ] **1.1 — Skeleton + basic gate behavior (unit-level, `SpeedEstimatorTest.kt`).** Write a failing, deterministic test: converge the filter at a constant pace for several fixes, then feed one fix with a sudden large accuracy degradation AND a position consistent with what a real acceleration would also produce (so the innovation alone would otherwise cross `innovationThreshold`). Assert `getLastProcessNoise() == qLow` for that fix (not `qHigh`), proving the accuracy gate overrides the innovation-based decision. Implement the minimal mechanism to pass. Commit.
- [ ] **1.2 — EMA-poisoning prevention (unit-level).** Write a failing test proving the gated fix's innovation does NOT get folded into `eastInnovationEma`/`northInnovationEma` (e.g., compare the EMA value immediately before and after the gated fix — it should be unchanged, or changed only by whatever the *next* normal fix contributes). Implement, commit.
- [ ] **1.3 — Multi-fix burst coverage (unit-level or scenario-level, your call).** Write a failing test with a several-fix-long degraded burst (constant true speed throughout) and confirm the gate keeps firing (forces `qLow`) across the fixes where it matters, not just the first one — this is the test that rules out the single-previous-fix mistake described above. Determine your baseline-smoothing approach and threshold empirically here, via TDD, not by assumption. Commit.
- [ ] **1.4 — Tighten the Task 8 scenario test (`SpeedEstimatorScenarioTest.kt`).** The existing `degradedAccuracyBurstDoesNotProduceFalseSpeedSpikes` test currently only asserts a loose bound (`maxDeviationKmh=8.0`, chosen to accommodate the *unfixed* transient). Now that the gate exists, tighten this bound to reflect what's actually achievable: re-run the same empirical-sweep method used throughout the prior plan (multiple seeds, verify margin at a sample size large enough to trust it — see that file's existing doc comments for the standard this project holds itself to) and pick the tightest tolerance that holds with genuine margin. Do not just leave the old 8.0 km/h bound in place if the fix makes the real behavior much tighter — a stale, loose bound would hide a regression later. Update the doc comment to explain the new number and reference this plan/task. Commit.
- [ ] **1.5 — Real-run sanity check.** Replay `app/src/test/resources/paco-run-2026-09-10-1122.gpx` through the gated `SpeedEstimator` (reuse `SpeedEstimatorRealRunTest.kt`'s parsing approach, or extend it) and confirm: no NaN/Infinity (unconditional), and the gate doesn't fire excessively on ordinary real-world accuracy jitter (report the fraction of fixes it engages on — should be low; if it's firing on a large fraction of ordinary fixes, the threshold/baseline needs revisiting before moving on). This can be a throwaway diagnostic (not necessarily a new permanent test) — your call whether it's worth committing as a permanent regression check.
- [ ] **1.6 — Full regression check.** Run the complete `SpeedEstimator*` test suite (`SpeedEstimatorTest`, `SpeedEstimatorScenarioTest`, `SyntheticTrajectoryTest`, `SpeedEstimatorRealRunTest`) and confirm everything passes, including all tests from the prior 12-task plan — this is an additive gate, it must not break anything already validated.

---

## Task 2: diagnostic GPX export of SpeedEstimator's output

**Files:**
- Modify: `app/src/main/java/dev/fred/pacorun/export/GpxWriter.kt` (the `TrackPoint` data class and the trkpt-rendering logic)
- Modify: `app/src/test/java/dev/fred/pacorun/export/GpxWriterTest.kt`
- Modify: `app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt` (wiring only — construct a `SpeedEstimator`, feed it, attach its output to the `TrackPoint` being built)
- Check whether `app/src/androidTest/java/dev/fred/pacorun/export/GpxExporterTest.kt` needs updating (it's an instrumented/androidTest file — read it first to see if it constructs `TrackPoint` directly and would need the new field; if it's testing something orthogonal like the Downloads-folder write path, it may not need changes at all)

### Design

1. **`TrackPoint`** (in `GpxWriter.kt`) gets one new field, e.g. `speedEstimatorKmh: Double` (not nullable — `SpeedEstimator.accept()` always returns a defined `Double`, unlike the other optional fields on this class which reflect Android location APIs that can genuinely have no value; there's no "unavailable" case here, so don't model one). Render it as a new `<pr:speedEstimatorKmh>` element in the `<extensions>` block, alongside the existing `pr:speedMps`/`pr:horizontalAccuracyMeters`/etc. Follow this file's existing formatting conventions exactly (see how `speedMps` is currently rendered).
2. **`RunSessionService`**: in `onLocation()` (or `beginTracking()` for initialization), construct a `SpeedEstimator()` instance as session-scoped state (same lifecycle as `gpsFreshness = GpsFreshnessTracker()` — freshly created in `beginTracking()`, so each session starts clean). On every location fix — the exact same `LocationPosition` and `reliable` flag already computed for `coordinator?.tick(...)` — also call `speedEstimator.accept(position, reliable = sample.reliable)`, and pass its return value into the `TrackPoint` being constructed for that fix (the `trackPoints +=` block already in `onLocation()`).
3. **Absolutely do not** pass `SpeedEstimator`'s output anywhere else — not to `coordinator?.tick(...)`, not to `publish(...)`, not to any `RunSessionState`. It exists in this task only as one more field on `TrackPoint`, which only `GpxWriter`/`GpxExporter` ever reads.

### Suggested task breakdown (TDD: failing test first, for the parts that are unit-testable)

- [ ] **2.1 — `TrackPoint`/`GpxWriter` field (unit-testable, `GpxWriterTest.kt`).** Write a failing test asserting a `TrackPoint` with a given `speedEstimatorKmh` value renders `<pr:speedEstimatorKmh>VALUE</pr:speedEstimatorKmh>` in the output (follow the existing `pointExtensionsCarrySpeedAccuracyAndReliability` test's shape). Since the field isn't nullable, there's no "omitted when null" case to test for it specifically (unlike the other optional fields) — but do update any existing test that constructs a `TrackPoint` positionally (the file has a few) so they still compile, and add the new field with a sensible explicit value in each. Implement the minimal `TrackPoint`/`GpxWriter.kt` change to pass. Commit.
- [ ] **2.2 — Wire `SpeedEstimator` into `RunSessionService`.** This part is not easily unit-testable in isolation (the existing `SessionServiceSupportTest.kt` only tests small pure-logic helpers extracted from the service, not `onLocation()`'s Android-API-bound flow, and the project doesn't use a mocking library) — implement it carefully by inspection, matching the existing code's style exactly, and rely on the dual code review (below) plus a clear self-review checklist rather than an automated test for this specific wiring step. Confirm: `SpeedEstimator` is constructed fresh per session (in `beginTracking()`, alongside `gpsFreshness`'s reset), fed the exact same `position`/`reliable` inputs `coordinator?.tick(...)` receives, and its output reaches only the `TrackPoint` being appended — nothing else changes in `onLocation()`, `coordinator?.tick(...)`'s call itself is untouched. Commit.
- [ ] **2.3 — Check `GpxExporterTest.kt` (androidTest).** Read it. If it constructs `TrackPoint`s or otherwise depends on the data class's shape, update it to compile/pass with the new field. If it's testing something orthogonal (e.g. the `MediaStore`/Downloads write path) and doesn't touch `TrackPoint` construction, no change needed — say so explicitly in your report rather than silently skipping it. This is an instrumented test (requires a device/emulator) — if you cannot run it in your environment, say so and let the controller decide whether it needs to be run manually before this task is considered fully verified (per this project's standing note: don't drive an emulator yourself for a visual check; report what should be checked instead).
- [ ] **2.4 — Full regression check.** Run `./gradlew testDebugUnitTest` (all unit tests, not just the ones touched) and confirm everything passes. `GpxExporterTest.kt` (androidTest) is out of scope for this command; note its status separately per B3.

---

## After both tasks

- [ ] Update `docs/superpowers/specs/2026-09-10-speed-accuracy-design.md`'s status section with the outcome of both tasks (the gate's final threshold/mechanism and the tightened Task 8 bound; confirmation the diagnostic export exists and remains isolated from production behavior).
- [ ] Final verification: `./gradlew testDebugUnitTest` and `./gradlew lintDebug` both clean, fresh (no stale cache) runs.
- [ ] Report a summary back to the user: what changed, the final gate threshold/mechanism chosen and why (with the empirical evidence), the tightened Task 8 bound (old vs. new, with margin), confirmation Task B never touches anything the user sees/hears during a run, and the status of the androidTest (`GpxExporterTest.kt`) check.
