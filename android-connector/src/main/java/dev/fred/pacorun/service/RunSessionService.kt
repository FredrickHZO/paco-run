package dev.fred.pacorun.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.fred.pacorun.audio.CuePlayer
import dev.fred.pacorun.audio.toAudioCue
import dev.fred.pacorun.connector.R
import dev.fred.pacorun.eventlog.AppLifecycleChangedEvent
import dev.fred.pacorun.eventlog.LifecycleTransition
import dev.fred.pacorun.eventlog.NetworkType
import dev.fred.pacorun.eventlog.SatelliteGeometry
import dev.fred.pacorun.eventlog.SensorAvailability
import dev.fred.pacorun.eventlog.SessionEvent
import dev.fred.pacorun.eventlog.SessionStarted
import dev.fred.pacorun.eventlog.ThermalStatus
import dev.fred.pacorun.export.GpxExporter
import dev.fred.pacorun.export.GpxWriter
import dev.fred.pacorun.export.TrackPoint
import dev.fred.pacorun.location.LocationSample
import dev.fred.pacorun.location.LocationSampleMapper
import dev.fred.pacorun.settings.RunPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.TimeUnit

class RunSessionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var notificationManager: NotificationManager
    private lateinit var hostConfig: RunSessionHostConfig
    private var cuePlayer: CuePlayer? = null
    private var sequencer: RunSessionEventSequencer? = null
    private var locationRequested = false
    private var starting = false
    private var sessionToken = 0L
    private var lastLocationElapsedRealtimeNanos = Long.MIN_VALUE
    private val notificationTracker = SessionNotificationTracker()
    private val trackPoints = mutableListOf<TrackPoint>()

    private var sessionStartedAtElapsedMillis: Long = 0L
    private var sessionStartedAtInstant: Instant = Instant.EPOCH
    private var eventLogger: SessionEventLogger? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private var lastGnssSnapshot: GnssSnapshot? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach(::onLocation)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            tickTimer(SystemClock.elapsedRealtime())
            handler.postDelayed(this, RunSessionEventSequencer.TICK_MILLIS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        hostConfig = application as RunSessionHostConfig
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        notificationManager = getSystemService(NotificationManager::class.java)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSession()
            ACTION_START -> if (!starting && sequencer == null) startSession()
            ACTION_APP_FOREGROUNDED ->
                dispatch(AppLifecycleChangedEvent(SystemClock.elapsedRealtime(), LifecycleTransition.FOREGROUNDED))
            ACTION_APP_BACKGROUNDED ->
                dispatch(AppLifecycleChangedEvent(SystemClock.elapsedRealtime(), LifecycleTransition.BACKGROUNDED))
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
            val preferences = hostConfig.runPreferencesSource().current()
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
        sessionStartedAtElapsedMillis = SystemClock.elapsedRealtime()
        sessionStartedAtInstant = Instant.now()
        lastGnssSnapshot = null

        eventLogger = SessionEventLogger(applicationContext)
        eventLogger?.start(GpxExporter.eventLogFileNameFor(sessionStartedAtInstant))

        val marker = restartMarkerPrefs()
        if (marker.getBoolean(KEY_SESSION_OPEN, false)) {
            dispatch(AppLifecycleChangedEvent(sessionStartedAtElapsedMillis, LifecycleTransition.SERVICE_RESTARTED_BY_SYSTEM))
        }
        marker.edit().putBoolean(KEY_SESSION_OPEN, true).apply()

        dispatch(
            SessionStarted(
                elapsedRealtimeMillis = sessionStartedAtElapsedMillis,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                osVersionRelease = Build.VERSION.RELEASE,
                osApiLevel = Build.VERSION.SDK_INT,
                appGitCommitHash = hostConfig.gitCommitHash,
                preferences = preferences,
                sensors = sensorAvailability(),
                networkTypeAtStart = currentNetworkType(),
            ),
        )

        sequencer = RunSessionEventSequencer(
            preferences = preferences,
            onCues = { cues -> cuePlayer?.enqueue(cues.map { it.toAudioCue() }, preferences.cueVolume) },
            publish = ::publish,
            sessionStartedAtElapsedMillis = sessionStartedAtElapsedMillis,
        )
        lastLocationElapsedRealtimeNanos = Long.MIN_VALUE
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
        registerGnssStatusCallback()
        locationRequested = true
        handler.post(ticker)
        starting = false
    }

    private fun registerGnssStatusCallback() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val locationManager = getSystemService(LocationManager::class.java) ?: return
        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var used = 0
                var min = Float.MAX_VALUE
                var max = -Float.MAX_VALUE
                var sum = 0f
                val geometry = mutableListOf<SatelliteGeometry>()
                for (i in 0 until status.satelliteCount) {
                    geometry += SatelliteGeometry(
                        svid = status.getSvid(i),
                        constellationType = status.getConstellationType(i),
                        elevationDegrees = status.getElevationDegrees(i),
                        azimuthDegrees = status.getAzimuthDegrees(i),
                        usedInFix = status.usedInFix(i),
                    )
                    if (!status.usedInFix(i)) continue
                    used++
                    val cn0 = status.getCn0DbHz(i)
                    if (cn0 < min) min = cn0
                    if (cn0 > max) max = cn0
                    sum += cn0
                }
                lastGnssSnapshot = GnssSnapshot(
                    satellitesUsedInFix = used,
                    cn0MinDbHz = if (used > 0) min else null,
                    cn0AvgDbHz = if (used > 0) sum / used else null,
                    cn0MaxDbHz = if (used > 0) max else null,
                    geometry = geometry,
                )
            }
        }
        try {
            locationManager.registerGnssStatusCallback(callback, handler)
            gnssStatusCallback = callback
        } catch (_: SecurityException) {
        }
    }

    private fun sensorAvailability(): SensorAvailability {
        val sensorManager = getSystemService(SensorManager::class.java)
        fun has(type: Int) = sensorManager?.getDefaultSensor(type) != null
        return SensorAvailability(
            hasBarometer = has(Sensor.TYPE_PRESSURE),
            hasMagnetometer = has(Sensor.TYPE_MAGNETIC_FIELD),
            hasAccelerometer = has(Sensor.TYPE_ACCELEROMETER),
            hasGyroscope = has(Sensor.TYPE_GYROSCOPE),
            hasStepCounter = has(Sensor.TYPE_STEP_COUNTER),
            hasStepDetector = has(Sensor.TYPE_STEP_DETECTOR),
        )
    }

    private fun currentNetworkType(): NetworkType {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return NetworkType.OFFLINE
        val network = connectivityManager.activeNetwork ?: return NetworkType.OFFLINE
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.OFFLINE
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            else -> NetworkType.OTHER
        }
    }

    private fun restartMarkerPrefs(): SharedPreferences =
        getSharedPreferences("session_event_logging", MODE_PRIVATE)

    private fun onLocation(location: Location) {
        if (location.elapsedRealtimeNanos <= lastLocationElapsedRealtimeNanos) return
        lastLocationElapsedRealtimeNanos = location.elapsedRealtimeNanos
        val now = SystemClock.elapsedRealtime()

        val mapper = LocationSampleMapper()
        val elapsedRealtimeMillis = TimeUnit.NANOSECONDS.toMillis(location.elapsedRealtimeNanos)
        val speedMps = location.takeIf { it.hasSpeed() }?.speed
        val accuracyMps = location.takeIf { it.hasSpeedAccuracy() }?.speedAccuracyMetersPerSecond
        val horizontalAccuracyMeters = location.takeIf { it.hasAccuracy() }?.accuracy
        val ageMillis = now - elapsedRealtimeMillis

        // Fusa: una sola chiamata a evaluate() (oggi ne esistono due, una dentro mapper.map(),
        // una esplicita) -- vedi spec, Data Flow Percorso A.
        val reliability = mapper.evaluate(speedMps, accuracyMps, ageMillis, horizontalAccuracyMeters)
        val sample = LocationSample(
            speedKmh = (speedMps ?: 0f) * 3.6f,
            speedAccuracyKmh = accuracyMps?.times(3.6f),
            reliable = reliability.reliable,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
        )
        val qualityTier = mapper.qualityTier(reliability, accuracyMps, horizontalAccuracyMeters)

        val position = LocationPosition(
            latitude = location.latitude,
            longitude = location.longitude,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
            horizontalAccuracyMeters = horizontalAccuracyMeters,
        )

        val result = sequencer?.onLocationFix(
            sample = sample,
            position = position,
            snapshot = lastGnssSnapshot,
            provider = location.provider,
            reliability = reliability,
            qualityTier = qualityTier,
            now = now,
        ) ?: return

        trackPoints += TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timeEpochMillis = location.time,
            speedMps = speedMps,
            horizontalAccuracyMeters = horizontalAccuracyMeters,
            speedAccuracyMps = accuracyMps,
            reliable = sample.reliable,
            speedEstimatorKmh = result.engineSpeedKmh,
            elapsedRealtimeMillis = position.elapsedRealtimeMillis,
        )
        result.events.forEach(::dispatch)
    }

    private fun tickTimer(elapsedRealtimeMillis: Long) {
        val result = sequencer?.onTimerTick(elapsedRealtimeMillis) ?: return
        val events = if (result.deviceHealthSampleDue) {
            val healthEvent = sequencer?.recordDeviceHealthSample(
                batteryPercent = currentBatteryPercent(),
                thermalStatus = currentThermalStatus(),
                now = elapsedRealtimeMillis,
            )
            listOfNotNull(healthEvent) + result.events
        } else {
            result.events
        }
        events.forEach(::dispatch)
    }

    private fun currentBatteryPercent(): Int? {
        val batteryManager = getSystemService(BatteryManager::class.java) ?: return null
        val percent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return percent.takeIf { it in 0..100 }
    }

    private fun currentThermalStatus(): ThermalStatus {
        val powerManager = getSystemService(PowerManager::class.java) ?: return ThermalStatus.UNKNOWN
        return when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
            else -> ThermalStatus.UNKNOWN
        }
    }

    private fun dispatch(event: SessionEvent) {
        eventLogger?.log(event)
    }

    private fun publish(state: RunSessionState) {
        RunSessionStore.publish(state)
        if (notificationTracker.shouldUpdate(state)) {
            notificationManager.notify(NOTIFICATION_ID, notification(notificationTracker.textFor(state)))
        }
    }

    private fun stopSession() {
        exportTrackAsGpx()
        gnssStatusCallback?.let { getSystemService(LocationManager::class.java)?.unregisterGnssStatusCallback(it) }
        gnssStatusCallback = null
        lastGnssSnapshot = null
        restartMarkerPrefs().edit().putBoolean(KEY_SESSION_OPEN, false).apply()
        eventLogger?.stop()
        eventLogger = null
        sessionToken++
        handler.removeCallbacks(ticker)
        if (locationRequested) {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            locationRequested = false
        }
        sequencer = null
        starting = false
        cuePlayer?.close()
        cuePlayer = null
        RunSessionStore.publish(RunSessionState.Idle)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun exportTrackAsGpx() {
        if (trackPoints.isEmpty()) return
        val points = trackPoints.toList()
        val context = applicationContext
        val fileName = GpxExporter.fileNameFor(sessionStartedAtInstant)
        Thread {
            try {
                val gpx = GpxWriter.build(points, activityName = "Paco Run")
                GpxExporter.writeToDownloads(context, fileName, gpx)
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun locationRequest(): LocationRequest =
        LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, RunSessionEventSequencer.TICK_MILLIS)
            .setMinUpdateIntervalMillis(RunSessionEventSequencer.TICK_MILLIS)
            .setMaxUpdateDelayMillis(RunSessionEventSequencer.TICK_MILLIS)
            .setWaitForAccurateLocation(true)
            .build()

    private fun notification(text: String): Notification = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_run)
        .setContentTitle(hostConfig.notificationTitle)
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

    companion object {
        const val ACTION_START = "dev.fred.pacorun.action.START_RUN_SESSION"
        const val ACTION_STOP = "dev.fred.pacorun.action.STOP_RUN_SESSION"
        const val ACTION_APP_FOREGROUNDED = "dev.fred.pacorun.action.APP_FOREGROUNDED"
        const val ACTION_APP_BACKGROUNDED = "dev.fred.pacorun.action.APP_BACKGROUNDED"
        private const val CHANNEL_ID = "run_session"
        private const val NOTIFICATION_ID = 1
        private const val KEY_SESSION_OPEN = "session_open"
    }
}
