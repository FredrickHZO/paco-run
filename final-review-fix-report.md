# Final review fix report

## Scope completed

1. **Warmup distance boundary:** `RoutineEngine` now credits only the portion of a reliable location delta after the initial-constant boundary to `routineDistanceMeters`. The same input also evaluates a limited-routine finish. Distance-limit priority suppresses the simultaneous `Routine start` cue and emits `Routine end` followed by any direction cue for the final constant range.
2. **GPS freshness:** cached GPS freshness now retains only a mapper-reliable sample's monotonic timestamp and expires immediately after the mapper's shared three-second limit. Callback receipt time no longer affects the timer decision.
3. **Foreground media playback:** the manifest declares `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and `location|mediaPlayback` on the session service for target SDK 37.
4. **Timer/location reconciliation:** one routine timing timestamp is used per one-second bucket, while every location callback still reaches the coordinator to update distance and pace. This is safe in the existing architecture because `RoutineEngine.accept` still receives every distance delta; only its timer timestamp is coalesced.
5. **Dynamic color:** the app now applies Android dynamic light/dark Material 3 schemes when enabled and the standard fixed Material light/dark schemes when disabled.

## Regression coverage

- Warmup-boundary surplus and one-input start/end cue priority/order.
- Stale callback and three-second GPS freshness expiry.
- Timer-first location callback retains distance and pace while reusing that bucket's routine timing timestamp.
- Packaged foreground-service permission/type.
- Dynamic enabled and disabled theme selection.

## TDD and verification evidence

- JVM RED: the new freshness test first failed to compile without `GpsFreshnessTracker`; after that minimal addition, the warmup and timing regressions failed with the pre-fix behavior.
- Android RED: the dynamic-theme instrumentation tests first failed to compile because `PacoRunTheme` did not exist.
- Focused JVM regressions: `./gradlew testDebugUnitTest --tests 'dev.fred.pacorun.routine.RoutineEngineTest.warmup*' --tests 'dev.fred.pacorun.service.SessionCoordinatorTest.timerFirstLocationInSameSecondKeepsDistanceAndPaceWithoutAnotherTimingAdvance' --tests 'dev.fred.pacorun.service.GpsFreshnessTrackerTest' --console=plain` — passed.
- Full JVM, lint, and debug APK: `./gradlew testDebugUnitTest lintDebug assembleDebug --console=plain` — passed.
- Focused Android contracts: `./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.fred.pacorun.service.ForegroundServiceManifestTest,dev.fred.pacorun.PacoRunThemeTest --console=plain` — 3/3 passed.
- Full connected suite on headless `Pixel_9a_API_37`: `./gradlew connectedDebugAndroidTest --console=plain` — 12/12 passed.
