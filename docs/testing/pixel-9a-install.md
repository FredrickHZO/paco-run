# Pixel 9a debug installation

The verified debug APK is at:

`app/build/outputs/apk/debug/app-debug.apk`

Install or replace it from the repository root:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch, grant precise location and notifications. Paco Run will not start a location foreground service without precise location. Keep normal battery optimization enabled for the initial check, then, if a real outdoor screen-off session is interrupted by the device, exempt Paco Run from battery optimization and repeat the check; this is device policy, not a substitute for foreground-service verification.

Before relying on the build outside, run a short outdoor check on the Pixel 9a:

1. Start a constant session and verify a live GPS fix.
2. Run the positive-warm-up/limited Walk–Run route and verify start/end cues and distance completion.
3. Run Paco Run beside the usual fitness app and music player, lock the display, and confirm tracking continuity plus brief music ducking and recovery.
4. Stop from the notification and confirm the location indicator disappears.
