package dev.fred.pacorun.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.fred.pacorun.R
import dev.fred.pacorun.audio.CuePlayer
import dev.fred.pacorun.location.LocationSampleMapper
import dev.fred.pacorun.pace.PaceConfig
import dev.fred.pacorun.pace.PaceEvaluator
import dev.fred.pacorun.routine.RoutineEngine
import dev.fred.pacorun.settings.RunPreferences
import dev.fred.pacorun.settings.RunPreferencesStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class RunSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var notificationManager: NotificationManager
    private var cuePlayer: CuePlayer? = null
    private var coordinator: SessionCoordinator? = null
    private var locationRequested = false
    private var starting = false
    private var sessionToken = 0L
    private var lastTickSecond = Long.MIN_VALUE

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let(::onLocation)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tick(SystemClock.elapsedRealtime(), sample = null, position = null)
            handler.postDelayed(this, TICK_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_START -> if (!starting && coordinator == null) startSession()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopSession()
        scope.cancel()
        super.onDestroy()
    }

    private fun startSession() {
        starting = true
        val token = ++sessionToken
        RunSessionStore.publish(RunSessionState.AcquiringGps)
        startForeground(NOTIFICATION_ID, notification("Acquisizione GPS"))
        scope.launch {
            val preferences = RunPreferencesStore(this@RunSessionService).preferences.first()
            if (token != sessionToken) return@launch
            if (!preferences.isValid || checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                stopSession()
                return@launch
            }
            beginTracking(preferences)
        }
    }

    private fun beginTracking(preferences: RunPreferences) {
        val startedAt = SystemClock.elapsedRealtime()
        cuePlayer = CuePlayer(this)
        coordinator = SessionCoordinator(
            preferences = preferences,
            routineEngine = RoutineEngineAdapter(RoutineEngine(preferences)),
            paceEvaluator = PaceEvaluatorAdapter(PaceEvaluator(preferences.paceConfig())),
            onCues = { cues, volume -> cuePlayer?.enqueue(cues, volume) },
            publish = ::publish,
            startedAtElapsedMillis = startedAt,
        )
        lastTickSecond = Long.MIN_VALUE
        fusedLocationClient.requestLocationUpdates(locationRequest(), locationCallback, Looper.getMainLooper())
        locationRequested = true
        handler.post(ticker)
        starting = false
    }

    private fun onLocation(location: Location) {
        val now = SystemClock.elapsedRealtime()
        val sample = LocationSampleMapper().map(location, now)
        tick(
            elapsedRealtimeMillis = now,
            sample = sample,
            position = LocationPosition(
                latitude = location.latitude,
                longitude = location.longitude,
                elapsedRealtimeMillis = TimeUnit.NANOSECONDS.toMillis(location.elapsedRealtimeNanos),
            ),
        )
    }

    private fun tick(elapsedRealtimeMillis: Long, sample: dev.fred.pacorun.location.LocationSample?, position: LocationPosition?) {
        val second = elapsedRealtimeMillis / TICK_MILLIS
        if (second == lastTickSecond) return
        lastTickSecond = second
        coordinator?.tick(elapsedRealtimeMillis, sample, position)
    }

    private fun publish(state: RunSessionState) {
        RunSessionStore.publish(state)
        val text = when (state) {
            RunSessionState.Idle -> "Sessione terminata"
            RunSessionState.AcquiringGps -> "Acquisizione GPS"
            is RunSessionState.Running -> state.phase.name.replace('_', ' ')
        }
        notificationManager.notify(NOTIFICATION_ID, notification(text))
    }

    private fun stopSession() {
        sessionToken++
        handler.removeCallbacks(ticker)
        if (locationRequested) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            locationRequested = false
        }
        coordinator = null
        starting = false
        cuePlayer?.close()
        cuePlayer = null
        RunSessionStore.publish(RunSessionState.Idle)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun locationRequest(): LocationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, TICK_MILLIS)
        .setMinUpdateIntervalMillis(TICK_MILLIS)
        .setMaxUpdateDelayMillis(TICK_MILLIS)
        .setWaitForAccurateLocation(true)
        .build()

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_run)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .addAction(0, "Termina", stopPendingIntent())
        .build()

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        0,
        Intent(this, RunSessionService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createNotificationChannel() {
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sessione di corsa", NotificationManager.IMPORTANCE_LOW),
        )
    }

    private fun RunPreferences.paceConfig() = PaceConfig(
        minKmh = constantRange.minKmh,
        maxKmh = constantRange.maxKmh,
        confirmationSamples = confirmationSamples,
        repeatMillis = repeatMillis,
        gpsCuesEnabled = gpsCuesEnabled,
    )

    companion object {
        const val ACTION_START = "dev.fred.pacorun.action.START_RUN_SESSION"
        const val ACTION_STOP = "dev.fred.pacorun.action.STOP_RUN_SESSION"
        private const val CHANNEL_ID = "run_session"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MILLIS = 1_000L
    }
}
