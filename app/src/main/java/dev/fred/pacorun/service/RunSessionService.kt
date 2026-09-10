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
import dev.fred.pacorun.export.GpxExporter
import dev.fred.pacorun.export.GpxWriter
import dev.fred.pacorun.export.TrackPoint
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
    private var coordinator: PendingSessionCoordinator? = null
    private var locationRequested = false
    private var starting = false
    private var sessionToken = 0L
    private var lastTimerSecond = Long.MIN_VALUE
    private var lastLocationElapsedRealtimeNanos = Long.MIN_VALUE
    private var gpsFreshness = GpsFreshnessTracker()
    private val notificationTracker = SessionNotificationTracker()
    private val trackPoints = mutableListOf<TrackPoint>()

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::onLocation)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tickTimer(SystemClock.elapsedRealtime())
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
        if (!SessionStartPrerequisites(
                hasFineLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
            ).canStartLocationForegroundService
        ) {
            stopSession()
            return
        }
        starting = true
        val token = ++sessionToken
        val acquiringGps = RunSessionState.AcquiringGps
        RunSessionStore.publish(acquiringGps)
        startForeground(NOTIFICATION_ID, notification(notificationTracker.textFor(acquiringGps)))
        notificationTracker.markDisplayed(acquiringGps)
        scope.launch {
            val preferences = RunPreferencesStore(this@RunSessionService).preferences.first()
            if (token != sessionToken) return@launch
            if (!preferences.isValid) {
                stopSession()
                return@launch
            }
            beginTracking(preferences)
        }
    }

    private fun beginTracking(preferences: RunPreferences) {
        cuePlayer = CuePlayer(this)
        coordinator = PendingSessionCoordinator(
            preferences = preferences,
            routineEngine = RoutineEngineAdapter(RoutineEngine(preferences)),
            paceEvaluator = PaceEvaluatorAdapter(PaceEvaluator(preferences.paceConfig())),
            onCues = { cues, volume -> cuePlayer?.enqueue(cues, volume) },
            publish = ::publish,
        )
        lastTimerSecond = Long.MIN_VALUE
        lastLocationElapsedRealtimeNanos = Long.MIN_VALUE
        gpsFreshness = GpsFreshnessTracker()
        trackPoints.clear()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSession()
            return
        }
        try {
            fusedLocationClient.requestLocationUpdates(locationRequest(), locationCallback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            stopSession()
            return
        }
        locationRequested = true
        handler.post(ticker)
        starting = false
    }

    private fun onLocation(location: Location) {
        if (location.elapsedRealtimeNanos <= lastLocationElapsedRealtimeNanos) return
        lastLocationElapsedRealtimeNanos = location.elapsedRealtimeNanos
        val now = SystemClock.elapsedRealtime()
        val sample = LocationSampleMapper().map(location, now)
        gpsFreshness.record(sample)
        // Every raw fix, reliable or not -- so the exported track shows exactly what was
        // (and wasn't) trusted, which is the whole point of comparing it against another app.
        trackPoints += TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timeEpochMillis = location.time,
            speedMps = location.takeIf { it.hasSpeed() }?.speed,
            horizontalAccuracyMeters = location.takeIf { it.hasAccuracy() }?.accuracy,
            speedAccuracyMps = location.takeIf { it.hasSpeedAccuracy() }?.speedAccuracyMetersPerSecond,
            reliable = sample.reliable,
        )
        coordinator?.tick(
            elapsedRealtimeMillis = now,
            sample = sample,
            position = LocationPosition(
                latitude = location.latitude,
                longitude = location.longitude,
                elapsedRealtimeMillis = TimeUnit.NANOSECONDS.toMillis(location.elapsedRealtimeNanos),
                horizontalAccuracyMeters = location.takeIf { it.hasAccuracy() }?.accuracy,
            ),
        )
    }

    private fun tickTimer(elapsedRealtimeMillis: Long) {
        val second = elapsedRealtimeMillis / TICK_MILLIS
        if (second == lastTimerSecond) return
        lastTimerSecond = second
        val gpsOutage = gpsFreshness.isOutage(elapsedRealtimeMillis)
        coordinator?.advanceTimer(elapsedRealtimeMillis, signalGpsOutage = gpsOutage)
    }

    private fun publish(state: RunSessionState) {
        RunSessionStore.publish(state)
        if (notificationTracker.shouldUpdate(state)) {
            notificationManager.notify(NOTIFICATION_ID, notification(notificationTracker.textFor(state)))
        }
    }

    private fun stopSession() {
        exportTrackAsGpx()
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

    // A plain Thread, not the service's own coroutine `scope` -- that scope is cancelled in
    // onDestroy(), which stopSession() triggers via stopSelf() moments later, and would race
    // the write. The Thread only touches an application Context (safe past the service's own
    // lifecycle) and a snapshot of the point list, so it can finish safely on its own.
    private fun exportTrackAsGpx() {
        if (trackPoints.isEmpty()) return
        val points = trackPoints.toList()
        val context = applicationContext
        Thread {
            // An uncaught exception on any thread kills the whole process by default -- this
            // write (MediaStore insert/open/write) can throw for reasons outside our control
            // (storage full, restrictive OEM scoped-storage behavior), and the export is a
            // best-effort extra, not worth taking the app down over.
            try {
                val fileName = GpxExporter.fileNameFor(java.time.Instant.ofEpochMilli(points.first().timeEpochMillis))
                val gpx = GpxWriter.build(points, activityName = "Paco Run")
                GpxExporter.writeToDownloads(context, fileName, gpx)
            } catch (_: Exception) {
            }
        }.start()
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
