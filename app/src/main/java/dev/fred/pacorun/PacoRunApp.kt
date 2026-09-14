package dev.fred.pacorun

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.fred.pacorun.location.locationReadiness
import dev.fred.pacorun.service.RunSessionService
import dev.fred.pacorun.service.RunSessionState
import dev.fred.pacorun.service.RunSessionStore
import dev.fred.pacorun.settings.RunPreferences
import dev.fred.pacorun.settings.RunPreferencesStore
import dev.fred.pacorun.ui.ActiveRunScreen
import dev.fred.pacorun.ui.editor.RoutineEditorScreen
import dev.fred.pacorun.ui.gps.GpsAcquisitionScreen
import dev.fred.pacorun.ui.home.HomeScreen
import dev.fred.pacorun.ui.home.HomeUiState
import dev.fred.pacorun.ui.theme.PacoRunTheme
import kotlinx.coroutines.launch

@Composable
fun PacoRunApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember(context.applicationContext) { RunPreferencesStore(context.applicationContext) }
    val preferences by store.preferences.collectAsStateWithLifecycle(initialValue = RunPreferences())
    var editedPreferences by remember { mutableStateOf<RunPreferences?>(null) }
    var editing by remember { mutableStateOf(false) }
    val displayedPreferences = editedPreferences ?: preferences
    LaunchedEffect(preferences) {
        if (editedPreferences == preferences) editedPreferences = null
    }
    val session by RunSessionStore.state.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    var wasAcquiringGps by remember { mutableStateOf(false) }
    LaunchedEffect(session) {
        if (session is RunSessionState.Running && wasAcquiringGps) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        }
        wasAcquiringGps = session == RunSessionState.AcquiringGps
    }
    val scope = rememberCoroutineScope()
    var readinessRefresh by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentSession by rememberUpdatedState(session)
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> readinessRefresh++
                Lifecycle.Event.ON_START -> if (currentSession is RunSessionState.Running) {
                    context.startService(
                        Intent(context, RunSessionService::class.java).setAction(RunSessionService.ACTION_APP_FOREGROUNDED),
                    )
                }
                Lifecycle.Event.ON_STOP -> if (currentSession is RunSessionState.Running) {
                    context.startService(
                        Intent(context, RunSessionService::class.java).setAction(RunSessionService.ACTION_APP_BACKGROUNDED),
                    )
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val readiness = locationReadiness(
        fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        providerEnabled = context.getSystemService(LocationManager::class.java).isLocationEnabled,
    ).also { readinessRefresh }
    val startIfAllowed = {
        if (permissionProblem(context) == null) {
            context.startForegroundService(Intent(context, RunSessionService::class.java).setAction(RunSessionService.ACTION_START))
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        readinessRefresh++
        startIfAllowed()
    }
    val requestStart = {
        val latest = editedPreferences
        scope.launch {
            if (latest != null) store.update { latest }
            if (permissionProblem(context) == null) startIfAllowed()
            else permissionLauncher.launch(requiredPermissions)
        }
        Unit
    }
    val stop = {
        context.startService(Intent(context, RunSessionService::class.java).setAction(RunSessionService.ACTION_STOP))
        Unit
    }

    PacoRunTheme(dynamicColor = displayedPreferences.dynamicColor) {
        when (val current = session) {
            RunSessionState.Idle -> if (editing) {
                RoutineEditorScreen(
                    preferences = displayedPreferences,
                    onPreferencesChange = { updated ->
                        editedPreferences = updated
                        scope.launch { store.update { updated } }
                    },
                    onBack = { editing = false },
                )
            } else {
                HomeScreen(
                    state = HomeUiState(displayedPreferences, readiness),
                    onStart = requestStart,
                    onEdit = { editing = true },
                    onModeChange = { mode ->
                        editedPreferences = displayedPreferences.copy(mode = mode)
                        scope.launch { store.update { it.copy(mode = mode) } }
                    },
                )
            }
            RunSessionState.AcquiringGps -> GpsAcquisitionScreen(onCancel = stop)
            is RunSessionState.Running -> ActiveRunScreen(state = current, onStop = stop)
        }
    }
}

private val requiredPermissions = arrayOf(
    Manifest.permission.ACCESS_COARSE_LOCATION,
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.POST_NOTIFICATIONS,
)

private fun permissionProblem(context: Context): String? = when {
    context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ->
        "Serve la posizione precisa: scegli “Posizione precisa”."
    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED ->
        "Serve l'accesso alla posizione."
    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
        "Consenti le notifiche per avviare la sessione."
    else -> null
}
