package dev.fred.pacorun

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fred.pacorun.service.RunSessionService
import dev.fred.pacorun.service.RunSessionState
import dev.fred.pacorun.service.RunSessionStore
import dev.fred.pacorun.settings.RunPreferences
import dev.fred.pacorun.settings.RunPreferencesStore
import dev.fred.pacorun.ui.AcquiringGpsScreen
import dev.fred.pacorun.ui.ActiveRunScreen
import dev.fred.pacorun.ui.SetupScreen
import dev.fred.pacorun.ui.theme.PacoRunTheme
import kotlinx.coroutines.launch

@Composable
fun PacoRunApp() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember(context.applicationContext) { RunPreferencesStore(context.applicationContext) }
    val preferences by store.preferences.collectAsStateWithLifecycle(initialValue = RunPreferences())
    var editedPreferences by remember { mutableStateOf<RunPreferences?>(null) }
    val displayedPreferences = editedPreferences ?: preferences
    LaunchedEffect(preferences) {
        if (editedPreferences == preferences) editedPreferences = null
    }
    val session by RunSessionStore.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var permissionMessage by remember { mutableStateOf<String?>(null) }
    val startIfAllowed = {
        when (permissionProblem(context)) {
            null -> {
                permissionMessage = null
                context.startForegroundService(Intent(context, RunSessionService::class.java).setAction(RunSessionService.ACTION_START))
            }
            else -> permissionMessage = permissionProblem(context)
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
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
            RunSessionState.Idle -> SetupScreen(
                preferences = displayedPreferences,
                onPreferencesChange = { updated ->
                    editedPreferences = updated
                    scope.launch { store.update { updated } }
                },
                onStart = requestStart,
                permissionMessage = permissionMessage,
            )
            RunSessionState.AcquiringGps -> AcquiringGpsScreen(onStop = stop)
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
