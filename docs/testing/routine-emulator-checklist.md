# Routine emulator acceptance checklist

Device: visible `Pixel_9a_API_37` (`emulator-5554`), API 37.

## Recorded results

| Check | Result | Evidence |
| --- | --- | --- |
| Clean verification | Passed | `./gradlew clean testDebugUnitTest connectedDebugAndroidTest lintDebug assembleDebug` completed successfully; connected suite: 9/9. |
| Accelerated unlimited Walk–Run | Passed | UI configured for zero warm-up, Run 12 s, Walk 15 s, unlimited. The live screen showed `RUN` before, and `WALK` after a screen-off interval. |
| Screen-off foreground continuity | Passed | `dumpsys activity services` while the display was off recorded `RunSessionService` with `isForeground=true`; its ongoing notification exposed `Termina`. |
| GPS/service ownership | Passed for the emulator session | Current location state showed one Paco Run high-accuracy request at 1 s, through the fused provider. |
| Cue playback plumbing | Evidence only | Logcat recorded `AudioTrack` frames/stops repeatedly during the timed transitions. This does not establish perceived audio quality. |
| Notification Stop and cleanup | Passed | The actual expanded-notification `Termina` action returned to setup. Current dumpsys state contained no Paco Run service, live Paco Run location request, or active Paco Run notification. |
| Positive warm-up + limited-distance ending | Not completed on this emulator | `geo fix` and NMEA route injection both report speed accuracy `sAcc=0.5 m/s`; Paco Run correctly treats this as weak because its accepted maximum is `0.35 m/s`. Thus the state remained `CONSTANT BEFORE` and distance stayed at 0.00 km. Do not weaken the production reliability gate for this emulator limitation. |
| Music ducking/coexistence | Deferred to physical Pixel | No independent music playback session was available on the emulator. AudioTrack activity proves playback attempts only, not ducking or user-perceived coexistence. |

Evidence is retained under the ignored SDD directory:
`.superpowers/sdd/2026-08-29-paco-run-routines-mvp/evidence/`.

## Physical-Pixel completion items

Run the following outdoors on the Pixel 9a with accurate GPS:

1. Use a positive warm-up and limited routine distance; verify `Routine start`, distance accumulation, and `Routine end` at the limit.
2. With headphones, verify the spoken/beep cue sequence and intelligibility.
3. Run Paco Run with the usual fitness tracker and a music player; verify both tracking sessions continue, cues duck music briefly, and music resumes.
4. Lock the phone during both timed and distance-driven scenarios; stop from the notification and confirm no lingering location indicator/service.
