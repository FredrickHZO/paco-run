# Wear OS Core Port (v0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port Paco Run's existing GPS/pace-coaching engine into a new, standalone Wear OS module (`:wear`) with a deliberately rudimentary UI, so the owner can run a real workout wearing only a watch (TicWatch Pro 3 GPS, Xiaomi Watch 2) and retrieve the same event-log/GPX output the phone app already produces.

**Architecture:** New Android application module `:wear`, same `dev.fred.pacorun` Gradle namespace as `:app` (different `applicationId` so both can be installed side by side), standalone (`com.google.android.wearable.standalone = true`). The engine files (`service/`, `pace/`, `routine/`, `location/LocationSampleMapper.kt`, `eventlog/`, `export/`, `settings/RunPreferences.kt`, `audio/CuePlayer.kt` + its `.wav` assets) are copied verbatim via `cp` — a deliberate temporary duplication, not a shared-module refactor. `RunSessionService.kt`'s only functional change is replacing its `RunPreferencesStore`-backed settings read with a hardcoded `RunPreferences(cueVolume = 0f)` — every other line, including `CuePlayer` construction and the `"location|mediaPlayback"` foreground-service type, is untouched. A brand-new, minimal Wear Compose Activity (Start/Stop button, nothing else) replaces `:app`'s whole UI layer, which is not ported at all.

**Tech Stack:** Kotlin 2.3.20, AGP 9.1.1, Wear Compose (`androidx.wear.compose:compose-material`), Gradle (`./gradlew :wear:testDebugUnitTest`, `:wear:assembleDebug`).

**Spec:** `docs/superpowers/specs/2026-09-18-wear-os-core-port-design.md` (commit `c6ad604` on `main`). Every file path, line number, and current-code snippet in this plan was read directly from `main` @ `8f26e47`/`c6ad604`, not trusted from the spec's own citations.

## Branch

`feature/wear-os`, already created from `main` @ `8f26e47` (worktree `.worktrees/wear-os`) — this plan does not create it.

## Global Constraints

- **`:wear`'s files are a deliberate, temporary copy, not a shared module.** Do not create a `:core` module or make `:wear` depend on `:app`. That extraction is a separate, later decision (spec, non-goals).
- **No audio changes beyond `cueVolume = 0f`.** `CuePlayer.kt` and the `AudioCue` enum are copied byte-for-byte, including their `@RawRes` references — do not trim, stub, or no-op them. The manifest's `foregroundServiceType="location|mediaPlayback"` and `FOREGROUND_SERVICE_MEDIA_PLAYBACK` permission are copied unchanged.
- **No settings UI, no routine editor, no DataStore.** `RunPreferencesStore` is not copied. The only owner-observable behavior difference from `:app` is that `:wear` always runs with `RunPreferences(cueVolume = 0f)` (i.e. `CONSTANT` mode, 7.5–8.5 km/h, unlimited distance, `gpsCuesEnabled = false` — the class's own existing defaults, plus the volume override).
- **No phone-watch communication/sync, no Health Services, no sensor diagnostics, no adaptive engine.** All out of scope for this plan.
- **Copy files with `cp`, not by retyping them.** Every "copy" step below is a shell operation on real files that already exist and are already tested on `:app` — retyping risks silent transcription drift from already-validated code.
- **UI has zero polish budget.** One screen, a Start/Stop button. No theming, no animation, no status readout beyond what's specified in Task 3.
- **Stop at the final task.** No APK install on real hardware by the agent (Task 4 is the owner's own manual step, per this project's standing rule against driving devices) — see `Task 4`.

---

## File Structure

| File | Role | Task |
|---|---|---|
| `settings.gradle.kts` | Modify: add `include(":wear")` | 1 |
| `wear/build.gradle.kts` | Create: `:wear` module build config | 1 |
| `wear/src/main/AndroidManifest.xml` | Create (Task 1, skeleton only), Modify (Task 3, add `RunSessionService` + permissions) | 1, 3 |
| `wear/src/main/java/dev/fred/pacorun/MainActivity.kt` | Create (Task 1, placeholder), Modify (Task 3, real Start/Stop UI) | 1, 3 |
| `wear/src/main/res/values/strings.xml`, `wear/src/main/res/mipmap*/...` | Create: minimal app name + launcher icon (Task 1) | 1 |
| `wear/src/main/java/dev/fred/pacorun/{service,pace,routine,location,eventlog,export,settings,audio}/*.kt` | Create: `cp`-copied engine files | 2 |
| `wear/src/main/res/raw/*.wav` | Create: `cp`-copied audio assets | 2 |
| `wear/src/test/java/dev/fred/pacorun/**/*.kt` | Create: `cp`-copied test files | 2 |
| `wear/src/main/java/dev/fred/pacorun/service/RunSessionService.kt` | Modify (Task 3): hardcode `RunPreferences`, drop `RunPreferencesStore` import | 3 |
| `wear/src/main/res/drawable/ic_run.xml`, `wear/src/main/res/values/strings.xml` | Modify (Task 3): add notification icon/string `RunSessionService.notification()` reads | 3 |

---

### Task 1: `:wear` module skeleton — buildable, installable, empty

**Files:**
- Modify: `settings.gradle.kts`
- Create: `wear/build.gradle.kts`
- Create: `wear/src/main/AndroidManifest.xml`
- Create: `wear/src/main/java/dev/fred/pacorun/MainActivity.kt`
- Create: `wear/src/main/res/values/strings.xml`
- Create: `wear/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` (+ referenced drawables)

**Interfaces:**
- Consumes: nothing — this task has no dependency on `:app`.
- Produces: a buildable, installable `:wear` module. Task 2 adds engine files into the same module; Task 3 replaces `MainActivity.kt`'s body.

This task's only deliverable is "the module builds and installs a trivial screen" — there is no business logic yet, so there is no unit test to write. Verification is `./gradlew :wear:assembleDebug` succeeding.

- [ ] **Step 1: Add the module to `settings.gradle.kts`**

Current (`settings.gradle.kts`, full file):

```kotlin
rootProject.name = "Paco Run"
include(":app")
```

New:

```kotlin
rootProject.name = "Paco Run"
include(":app")
include(":wear")
```

- [ ] **Step 2: Create `wear/build.gradle.kts`**

```kotlin
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// RunSessionService.kt (copied verbatim in Task 3) reads BuildConfig.GIT_COMMIT_HASH at line 204
// -- this is a custom field, not a stock BuildConfig member, so it must be defined here the same
// way :app's own build.gradle.kts defines it (copied verbatim from there, lines 12-32).
abstract class GitCommitHashValueSource : ValueSource<String, ValueSourceParameters.None> {
    @get:Inject
    abstract val execOperations: ExecOperations

    override fun obtain(): String {
        val output = ByteArrayOutputStream()
        return try {
            val result = execOperations.exec {
                commandLine("git", "rev-parse", "--short=12", "HEAD")
                standardOutput = output
                isIgnoreExitValue = true
            }
            if (result.exitValue != 0) return "unknown"
            output.toString(Charsets.UTF_8.name()).trim().ifEmpty { "unknown" }
        } catch (e: Exception) {
            "unknown"
        }
    }
}

val gitCommitHash: String = providers.of(GitCommitHashValueSource::class) {}.get()

android {
    namespace = "dev.fred.pacorun"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.fred.pacorun.wear"
        // 29 matches :app's current minSdk; confirm it's <= the TicWatch Pro 3's actual
        // Wear OS 3.5 API level before relying on it for real-hardware install (spec's
        // own open item -- not locked in as a verified fact here).
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "GIT_COMMIT_HASH", "\"$gitCommitHash\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = false
        buildConfig = true
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    // Verified current stable release (2026-05-19) at plan-writing time. Google now recommends
    // wear-compose-material3 instead, but this is throwaway v0 UI (one Button) -- not worth
    // chasing the newer API surface for something this small.
    implementation("androidx.wear.compose:compose-material:1.6.2")
    implementation(libs.androidx.activity.compose)
    implementation("androidx.media3:media3-exoplayer:1.9.3")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}
```

Note: no `proguard-rules.pro` exists yet in `wear/` — `getDefaultProguardFile(...)` alone is fine, matching `:app`'s own release block; this repo builds debug only so far, so this path isn't exercised yet.

- [ ] **Step 3: Create the manifest skeleton**

`wear/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-feature android:name="android.hardware.type.watch" android:required="true" />

    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:theme="@android:style/Theme.DeviceDefault">

        <meta-data
            android:name="com.google.android.wearable.standalone"
            android:value="true" />

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

    </application>

</manifest>
```

- [ ] **Step 4: Create a placeholder `MainActivity.kt`**

```kotlin
package dev.fred.pacorun

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.wear.compose.material.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Text("Paco Run Wear")
        }
    }
}
```

- [ ] **Step 5: Add `strings.xml`**

`wear/src/main/res/values/strings.xml`:

```xml
<resources>
    <string name="app_name">Paco Run Wear</string>
</resources>
```

- [ ] **Step 6: Add a launcher icon**

Reuse `:app`'s existing launcher icon rather than designing a new one (zero UI-polish budget, per Global Constraints):

```bash
mkdir -p wear/src/main/res/mipmap-anydpi-v26
cp app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml wear/src/main/res/mipmap-anydpi-v26/
for density in hdpi mdpi xhdpi xxhdpi xxxhdpi; do
  mkdir -p "wear/src/main/res/mipmap-$density"
  cp "app/src/main/res/mipmap-$density/ic_launcher_background.png" "wear/src/main/res/mipmap-$density/"
  cp "app/src/main/res/mipmap-$density/ic_launcher_foreground.png" "wear/src/main/res/mipmap-$density/"
  cp "app/src/main/res/mipmap-$density/ic_launcher_monochrome.png" "wear/src/main/res/mipmap-$density/"
done
```

- [ ] **Step 7: Build and verify**

Run: `./gradlew :wear:assembleDebug`
Expected: BUILD SUCCESSFUL, produces `wear/build/outputs/apk/debug/wear-debug.apk`.

- [ ] **Step 8: Commit**

```bash
git add settings.gradle.kts wear/build.gradle.kts wear/src/main/AndroidManifest.xml \
  wear/src/main/java/dev/fred/pacorun/MainActivity.kt wear/src/main/res/
git commit -m "feat: scaffold the :wear module skeleton, no engine code yet"
```

---

### Task 2: Copy the engine files into `:wear`

**Files:**
- Create (via `cp`): `wear/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt`, `SessionEventLogger.kt`, `SpeedEstimator.kt`
- Create (via `cp`): `wear/src/main/java/dev/fred/pacorun/pace/PaceEvaluator.kt`, `PaceModels.kt`
- Create (via `cp`): `wear/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt`, `RoutineModels.kt`
- Create (via `cp`): `wear/src/main/java/dev/fred/pacorun/location/LocationSampleMapper.kt`
- Create (via `cp`): `wear/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt`, `SessionEventWriter.kt`
- Create (via `cp`): `wear/src/main/java/dev/fred/pacorun/export/GpxWriter.kt`, `GpxExporter.kt`
- Create (via `cp`): `wear/src/main/java/dev/fred/pacorun/settings/RunPreferences.kt`
- Create (via `cp`): `wear/src/main/java/dev/fred/pacorun/audio/CuePlayer.kt`
- Create (via `cp`): 14 files under `wear/src/main/res/raw/*.wav`
- Create (via `cp`): the matching test file for every Kotlin file above that has one in `app/src/test/...`

**Interfaces:**
- Consumes: nothing new — these files' own internal imports resolve entirely within this same file set (verified during spec review: every `dev.fred.pacorun.*` import in `RunSessionService.kt`/`RunSessionStore.kt` resolves to a file in this list; `CuePlayer.kt` only needs `R`, `pace.Cue`, `routine.RoutineCue`, all present).
- Produces: `RunSessionStore.kt`'s `SessionCoordinator`, `PendingSessionCoordinator`, `SessionSpeedEngine`, `KalmanSpeedEngine`, `EmaPaceSmoother`, etc. — consumed by Task 3's trimmed `RunSessionService.kt`.

This task does not touch `RunSessionService.kt` (Task 3) or `MainActivity.kt` (Task 3) — it only populates the engine package set. `:wear:assembleDebug` will still succeed after this task even though nothing calls this code yet (dead code compiles fine); the real verification is the copied test suite passing.

- [ ] **Step 1: Copy the Kotlin source files**

```bash
mkdir -p wear/src/main/java/dev/fred/pacorun/{service,pace,routine,location,eventlog,export,settings,audio}

cp app/src/main/java/dev/fred/pacorun/service/RunSessionStore.kt wear/src/main/java/dev/fred/pacorun/service/
cp app/src/main/java/dev/fred/pacorun/service/SessionEventLogger.kt wear/src/main/java/dev/fred/pacorun/service/
cp app/src/main/java/dev/fred/pacorun/service/SpeedEstimator.kt wear/src/main/java/dev/fred/pacorun/service/

cp app/src/main/java/dev/fred/pacorun/pace/PaceEvaluator.kt wear/src/main/java/dev/fred/pacorun/pace/
cp app/src/main/java/dev/fred/pacorun/pace/PaceModels.kt wear/src/main/java/dev/fred/pacorun/pace/

cp app/src/main/java/dev/fred/pacorun/routine/RoutineEngine.kt wear/src/main/java/dev/fred/pacorun/routine/
cp app/src/main/java/dev/fred/pacorun/routine/RoutineModels.kt wear/src/main/java/dev/fred/pacorun/routine/

cp app/src/main/java/dev/fred/pacorun/location/LocationSampleMapper.kt wear/src/main/java/dev/fred/pacorun/location/

cp app/src/main/java/dev/fred/pacorun/eventlog/SessionEvent.kt wear/src/main/java/dev/fred/pacorun/eventlog/
cp app/src/main/java/dev/fred/pacorun/eventlog/SessionEventWriter.kt wear/src/main/java/dev/fred/pacorun/eventlog/

cp app/src/main/java/dev/fred/pacorun/export/GpxWriter.kt wear/src/main/java/dev/fred/pacorun/export/
cp app/src/main/java/dev/fred/pacorun/export/GpxExporter.kt wear/src/main/java/dev/fred/pacorun/export/

cp app/src/main/java/dev/fred/pacorun/settings/RunPreferences.kt wear/src/main/java/dev/fred/pacorun/settings/

cp app/src/main/java/dev/fred/pacorun/audio/CuePlayer.kt wear/src/main/java/dev/fred/pacorun/audio/
```

- [ ] **Step 2: Copy the audio assets**

```bash
mkdir -p wear/src/main/res/raw
cp app/src/main/res/raw/*.wav wear/src/main/res/raw/
```

- [ ] **Step 3: Copy the matching test files**

```bash
mkdir -p wear/src/test/java/dev/fred/pacorun/{service,pace,routine,location,eventlog,export}

cp app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorTest.kt wear/src/test/java/dev/fred/pacorun/service/
cp app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorRealRunTest.kt wear/src/test/java/dev/fred/pacorun/service/
cp app/src/test/java/dev/fred/pacorun/service/SpeedEstimatorScenarioTest.kt wear/src/test/java/dev/fred/pacorun/service/
cp app/src/test/java/dev/fred/pacorun/service/SyntheticTrajectory.kt wear/src/test/java/dev/fred/pacorun/service/
cp app/src/test/java/dev/fred/pacorun/service/SyntheticTrajectoryTest.kt wear/src/test/java/dev/fred/pacorun/service/
cp app/src/test/java/dev/fred/pacorun/service/EmaPaceSmootherTest.kt wear/src/test/java/dev/fred/pacorun/service/
cp app/src/test/java/dev/fred/pacorun/service/SessionSpeedEngineTest.kt wear/src/test/java/dev/fred/pacorun/service/
cp app/src/test/java/dev/fred/pacorun/service/GpsSilentGapTrackerTest.kt wear/src/test/java/dev/fred/pacorun/service/
cp app/src/test/java/dev/fred/pacorun/service/GpsFreshnessTrackerTest.kt wear/src/test/java/dev/fred/pacorun/service/
cp app/src/test/java/dev/fred/pacorun/service/DistanceAccumulatorTest.kt wear/src/test/java/dev/fred/pacorun/service/
cp app/src/test/java/dev/fred/pacorun/service/SessionServiceSupportTest.kt wear/src/test/java/dev/fred/pacorun/service/
cp app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorTest.kt wear/src/test/java/dev/fred/pacorun/service/
cp app/src/test/java/dev/fred/pacorun/service/SessionCoordinatorRealRunTest.kt wear/src/test/java/dev/fred/pacorun/service/
cp app/src/test/resources -r wear/src/test/resources 2>/dev/null || true

cp app/src/test/java/dev/fred/pacorun/pace/PaceEvaluatorTest.kt wear/src/test/java/dev/fred/pacorun/pace/

cp app/src/test/java/dev/fred/pacorun/routine/RoutineEngineTest.kt wear/src/test/java/dev/fred/pacorun/routine/

cp app/src/test/java/dev/fred/pacorun/location/LocationSampleMapperTest.kt wear/src/test/java/dev/fred/pacorun/location/

cp app/src/test/java/dev/fred/pacorun/eventlog/SessionEventWriterTest.kt wear/src/test/java/dev/fred/pacorun/eventlog/

cp app/src/test/java/dev/fred/pacorun/export/GpxWriterTest.kt wear/src/test/java/dev/fred/pacorun/export/
```

If any of the above `cp` commands fail because the source file's name has drifted since this plan was written (`main` may have moved), `ls app/src/test/java/dev/fred/pacorun/<package>/` and copy whatever `*Test.kt`/support files actually exist for that package — the intent (every test file for the copied packages) matters more than this exact file list.

- [ ] **Step 4: Run the copied test suite**

Run: `./gradlew :wear:testDebugUnitTest`
Expected: PASS — every test, because this is already-tested code, unmodified. A failure here means a copy went wrong (missing file, wrong path), not a logic bug to fix.

- [ ] **Step 5: Full `:wear` build**

Run: `./gradlew :wear:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add wear/src/main/java/dev/fred/pacorun/service wear/src/main/java/dev/fred/pacorun/pace \
  wear/src/main/java/dev/fred/pacorun/routine wear/src/main/java/dev/fred/pacorun/location \
  wear/src/main/java/dev/fred/pacorun/eventlog wear/src/main/java/dev/fred/pacorun/export \
  wear/src/main/java/dev/fred/pacorun/settings wear/src/main/java/dev/fred/pacorun/audio \
  wear/src/main/res/raw wear/src/test
git commit -m "feat: copy the engine (service/pace/routine/location/eventlog/export/audio) into :wear"
```

---

### Task 3: Wire the service into `:wear`, replace the placeholder UI

**Files:**
- Create (via `cp`, then modify): `wear/src/main/java/dev/fred/pacorun/service/RunSessionService.kt`
- Modify: `wear/src/main/AndroidManifest.xml`
- Modify: `wear/src/main/java/dev/fred/pacorun/MainActivity.kt`
- Create: `wear/src/main/res/drawable/ic_run.xml`

**Interfaces:**
- Consumes: `SessionCoordinator`/`PendingSessionCoordinator`/`RunSessionStore` object (Task 2), `RunSessionService.ACTION_START`/`ACTION_STOP` (defined in this task's copy of `RunSessionService.kt`, same constant names as `:app`'s).
- Produces: a running foreground service reachable from the UI. Nothing later depends on this beyond Task 4's manual verification.

- [ ] **Step 1: Copy `RunSessionService.kt`**

```bash
cp app/src/main/java/dev/fred/pacorun/service/RunSessionService.kt wear/src/main/java/dev/fred/pacorun/service/
```

- [ ] **Step 2: Trim the settings read**

Current (`wear/src/main/java/dev/fred/pacorun/service/RunSessionService.kt`, line 165):

```kotlin
            val preferences = RunPreferencesStore(this@RunSessionService).preferences.first()
```

New:

```kotlin
            val preferences = RunPreferences(cueVolume = 0f)
```

- [ ] **Step 3: Remove the now-unused imports**

Delete these two lines (the file's `import dev.fred.pacorun.settings.RunPreferences` line stays — it's now the only settings import needed):

```kotlin
import dev.fred.pacorun.settings.RunPreferencesStore
```

```kotlin
import kotlinx.coroutines.flow.first
```

(Confirmed both are unused elsewhere in this file after the Step 2 change — `RunPreferencesStore` had exactly one call site, `.first()` exactly one call site, both at the line just replaced.)

- [ ] **Step 4: Build to confirm the trim compiles**

Run: `./gradlew :wear:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (No test to run here — this file has no existing unit test on `:app` either; `RunSessionService` is an Android `Service`, verified today only by running the real app, which Task 4 does on real hardware.)

- [ ] **Step 5: Add the notification icon resource**

`RunSessionService.notification()` reads `R.drawable.ic_run` and `R.string.app_name` (already present from Task 1). It's a single vector drawable, confirmed at `app/src/main/res/drawable/ic_run.xml` (not density-qualified, no other variants exist). Copy it:

```bash
mkdir -p wear/src/main/res/drawable
cp app/src/main/res/drawable/ic_run.xml wear/src/main/res/drawable/
```

- [ ] **Step 6: Add `RunSessionService` to the manifest**

`app/src/main/AndroidManifest.xml`'s permission block, read directly for this plan (not reconstructed from memory), is exactly:

```xml
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
```

(`ACCESS_NETWORK_STATE` is there because `RunSessionService.kt` uses `ConnectivityManager`/`NetworkCapabilities` for its `NetworkType` event-log field.) Add this exact block to `wear/src/main/AndroidManifest.xml`, right after the opening `<manifest ...>` tag and before `<uses-feature .../>`, and add this inside `<application>`, alongside the existing `<activity>`:

```xml
        <service
            android:name=".service.RunSessionService"
            android:exported="false"
            android:foregroundServiceType="location|mediaPlayback" />
```

- [ ] **Step 7: Replace the placeholder `MainActivity.kt` with a real Start/Stop screen**

```kotlin
package dev.fred.pacorun

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.fred.pacorun.service.RunSessionService

class MainActivity : ComponentActivity() {

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* no-op: the service checks the permission itself before starting */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)

        setContent {
            var isRunning by remember { mutableStateOf(false) }

            MaterialTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Button(onClick = {
                        if (isRunning) {
                            startService(
                                Intent(this@MainActivity, RunSessionService::class.java)
                                    .setAction(RunSessionService.ACTION_STOP),
                            )
                        } else {
                            startForegroundService(
                                Intent(this@MainActivity, RunSessionService::class.java)
                                    .setAction(RunSessionService.ACTION_START),
                            )
                        }
                        isRunning = !isRunning
                    }) {
                        Text(if (isRunning) "Stop" else "Start")
                    }
                }
            }
        }
    }
}
```

This deliberately does not observe `RunSessionStore`'s published `RunSessionState` — the button's label reflects local click-state only, not the service's real status. Per the spec, on-screen status was explicitly optional; the real validation signal is the event log retrieved afterward (Task 4), not a live on-screen readout. `ACTION_START`/`ACTION_STOP` are the same constant names `:app`'s `PacoRunApp.kt` already uses, copied into `:wear`'s own `RunSessionService.kt` in Step 1.

- [ ] **Step 8: Build**

Run: `./gradlew :wear:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Full `:wear` test suite (regression check)**

Run: `./gradlew :wear:testDebugUnitTest`
Expected: PASS (unchanged from Task 2 — this task touched no tested logic, only `RunSessionService.kt`, which has no unit test, and `MainActivity.kt`, which is new UI code with no test planned per the spec).

- [ ] **Step 10: Commit**

```bash
git add wear/src/main/java/dev/fred/pacorun/service/RunSessionService.kt \
  wear/src/main/java/dev/fred/pacorun/MainActivity.kt wear/src/main/AndroidManifest.xml \
  wear/src/main/res/drawable
git commit -m "feat: wire RunSessionService into :wear with a minimal Start/Stop UI"
```

---

### Task 4: Real-hardware verification (owner's own step)

**Files:** none — checkpoint, no code changes.

Per this project's standing rule, an agent does not drive a physical device or emulator for visual/functional verification — this task is a checklist for the owner to run themselves, not something to automate.

- [ ] **Step 1: Install on the TicWatch Pro 3 GPS**

```bash
adb connect <ticwatch-ip>:5555
adb -s <ticwatch-ip>:5555 install -r wear/build/outputs/apk/debug/wear-debug.apk
```

- [ ] **Step 2: Run a real session**

On the watch: open "Paco Run Wear", grant the location permission prompt, tap Start, go for an actual walk/run outdoors (GPS needs sky visibility), tap Stop when done.

- [ ] **Step 3: Retrieve the log**

```bash
adb -s <ticwatch-ip>:5555 shell find /sdcard -iname "*.jsonl" -o -iname "*.gpx" 2>/dev/null
adb -s <ticwatch-ip>:5555 pull <path-found-above>
```

If the files aren't where expected, this is exactly the spec's flagged open assumption (`SessionEventLogger`/`GpxExporter`'s `MediaStore` write behaving the same on Wear OS as on phone) turning out false — report back rather than guessing a fix; it may need a real (small) code change, which is new work outside this plan's scope, not a step to silently improvise here.

- [ ] **Step 4: Repeat on the Xiaomi Watch 2**

Same three steps, different `adb connect` target — confirms the port isn't accidentally TicWatch-specific.

- [ ] **Step 5: Report back**

Report to the owner: did both watches install and run, did a real event log/GPX come out, and anything that didn't match this plan's assumptions (storage path, permission prompts, GPS acquisition time, battery/notification behavior) — feeding back into whether the shared-module extraction (deferred per spec) is worth doing next.

**Do not merge `feature/wear-os`, delete the branch, or start any further Wear OS work (Health Services diagnostics, the adaptive engine, phone-watch sync) — all out of scope here and pending a separate go-ahead.**
