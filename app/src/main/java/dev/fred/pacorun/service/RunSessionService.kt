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
import dev.fred.pacorun.BuildConfig
import dev.fred.pacorun.R
import dev.fred.pacorun.audio.CuePlayer
import dev.fred.pacorun.eventlog.AppLifecycleChangedEvent
import dev.fred.pacorun.eventlog.DeviceHealthSampledEvent
import dev.fred.pacorun.eventlog.GpsSilentGapEvent
import dev.fred.pacorun.eventlog.LifecycleTransition
import dev.fred.pacorun.eventlog.LocationFixEvent
import dev.fred.pacorun.eventlog.NetworkType
import dev.fred.pacorun.eventlog.SensorAvailability
import dev.fred.pacorun.eventlog.SessionEvent
import dev.fred.pacorun.eventlog.SessionStarted
import dev.fred.pacorun.eventlog.SpeedEstimatorDiagnostics
import dev.fred.pacorun.eventlog.ThermalStatus
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
import java.time.Instant
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
    private var gpsSilentGap = GpsSilentGapTracker()
    private var lastDeviceHealthBucket = Long.MIN_VALUE
    // Diagnostic-only: captures the shared production engine's output for the current fix, so
    // the exported TrackPoint can reuse it -- see onLocation() and the onSpeedComputed wiring.
    private var lastEngineSpeedKmh: Double? = null
    private val notificationTracker = SessionNotificationTracker()
    private val trackPoints = mutableListOf<TrackPoint>()

    // -- P1 session event logging state --
    private var sessionStartedAtElapsedMillis: Long = 0L
    private var sessionStartedAtInstant: Instant = Instant.EPOCH
    private var eventLogger: SessionEventLogger? = null
    private var onEvent: (SessionEvent) -> Unit = {}
    // Causal ordering inside one onLocation() callback: coordinator.tick() synchronously fires
    // the events a fix *causes* (cues, phase changes, chain breaks), but LocationFixEvent can
    // only be built after tick() has run, because its speedEstimatorDiagnostics are only correct
    // once SpeedEstimator.accept() has processed this fix. Buffering tick()'s events lets the fix
    // event be written first and its consequences after it.
    private var bufferingLocationEvents = false
    private val pendingEventsDuringTick = mutableListOf<SessionEvent>()
    private var locationSequenceNumber = 0
    private var speedEstimatorForDiagnostics: SpeedEstimator? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private var lastGnssSnapshot: GnssSnapshot? = null

    private data class GnssSnapshot(
        val satellitesUsedInFix: Int,
        val cn0MinDbHz: Float,
        val cn0AvgDbHz: Float,
        val cn0MaxDbHz: Float,
    )

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
            ACTION_APP_FOREGROUNDED ->
                onEvent(AppLifecycleChangedEvent(SystemClock.elapsedRealtime(), LifecycleTransition.FOREGROUNDED))
            ACTION_APP_BACKGROUNDED ->
                onEvent(AppLifecycleChangedEvent(SystemClock.elapsedRealtime(), LifecycleTransition.BACKGROUNDED))
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
        sessionStartedAtElapsedMillis = SystemClock.elapsedRealtime()
        sessionStartedAtInstant = Instant.now()
        locationSequenceNumber = 0
        lastGnssSnapshot = null
        lastDeviceHealthBucket = Long.MIN_VALUE

        eventLogger = SessionEventLogger(applicationContext)
        eventLogger?.start(GpxExporter.eventLogFileNameFor(sessionStartedAtInstant))
        // Every domain object below captures THIS lambda instance once; reassigning the field
        // later would not redirect them, so the buffering switch lives inside the lambda body,
        // reading the service's live properties on each invocation.
        onEvent = { event ->
            if (bufferingLocationEvents) pendingEventsDuringTick += event else eventLogger?.log(event)
        }

        val marker = restartMarkerPrefs()
        if (marker.getBoolean(KEY_SESSION_OPEN, false)) {
            onEvent(AppLifecycleChangedEvent(sessionStartedAtElapsedMillis, LifecycleTransition.SERVICE_RESTARTED_BY_SYSTEM))
        }
        marker.edit().putBoolean(KEY_SESSION_OPEN, true).apply()

        onEvent(
            SessionStarted(
                elapsedRealtimeMillis = sessionStartedAtElapsedMillis,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                osVersionRelease = Build.VERSION.RELEASE,
                osApiLevel = Build.VERSION.SDK_INT,
                appGitCommitHash = BuildConfig.GIT_COMMIT_HASH,
                preferences = preferences,
                sensors = sensorAvailability(),
                networkTypeAtStart = currentNetworkType(),
            ),
        )

        val speedEstimator = SpeedEstimator(onEvent = onEvent)
        speedEstimatorForDiagnostics = speedEstimator

        coordinator = PendingSessionCoordinator(
            preferences = preferences,
            routineEngine = RoutineEngineAdapter(RoutineEngine(preferences, onEvent = onEvent)),
            paceEvaluator = PaceEvaluatorAdapter(PaceEvaluator(preferences.paceConfig(), onEvent = onEvent)),
            onCues = { cues, volume -> cuePlayer?.enqueue(cues, volume) },
            publish = ::publish,
            distanceEngine = DistanceAccumulator(onEvent = onEvent),
            speedEngine = KalmanSpeedEngine(speedEstimator),
            onSpeedComputed = { lastEngineSpeedKmh = it },
            onEvent = onEvent,
        )
        lastTimerSecond = Long.MIN_VALUE
        lastLocationElapsedRealtimeNanos = Long.MIN_VALUE
        gpsFreshness = GpsFreshnessTracker()
        gpsSilentGap = GpsSilentGapTracker().also { it.reset(sessionStartedAtElapsedMillis) }
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
                for (i in 0 until status.satelliteCount) {
                    if (!status.usedInFix(i)) continue
                    used++
                    val cn0 = status.getCn0DbHz(i)
                    if (cn0 < min) min = cn0
                    if (cn0 > max) max = cn0
                    sum += cn0
                }
                lastGnssSnapshot = if (used > 0) GnssSnapshot(used, min, sum / used, max) else null
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
        val sample = mapper.map(location, now)
        gpsFreshness.record(sample)
        val silentGap = gpsSilentGap.recordCallback(now)
        if (silentGap != null) {
            onEvent(GpsSilentGapEvent(now, silentGap))
        }
        val position = LocationPosition(
            latitude = location.latitude,
            longitude = location.longitude,
            elapsedRealtimeMillis = TimeUnit.NANOSECONDS.toMillis(location.elapsedRealtimeNanos),
            horizontalAccuracyMeters = location.takeIf { it.hasAccuracy() }?.accuracy,
        )
        lastEngineSpeedKmh = null
        bufferingLocationEvents = true
        coordinator?.tick(elapsedRealtimeMillis = now, sample = sample, position = position)
        bufferingLocationEvents = false
        trackPoints += TrackPoint(
            latitude = location.latitude,
            longitude = location.longitude,
            timeEpochMillis = location.time,
            speedMps = location.takeIf { it.hasSpeed() }?.speed,
            horizontalAccuracyMeters = location.takeIf { it.hasAccuracy() }?.accuracy,
            speedAccuracyMps = location.takeIf { it.hasSpeedAccuracy() }?.speedAccuracyMetersPerSecond,
            reliable = sample.reliable,
            speedEstimatorKmh = lastEngineSpeedKmh,
            elapsedRealtimeMillis = position.elapsedRealtimeMillis,
        )

        val speedAccuracyMps = location.takeIf { it.hasSpeedAccuracy() }?.speedAccuracyMetersPerSecond
        val horizontalAccuracyMeters = location.takeIf { it.hasAccuracy() }?.accuracy
        val reliability = mapper.evaluate(
            speedMps = location.takeIf { it.hasSpeed() }?.speed,
            accuracyMps = speedAccuracyMps,
            ageMillis = now - position.elapsedRealtimeMillis,
            horizontalAccuracyMeters = horizontalAccuracyMeters,
        )
        val snapshot = lastGnssSnapshot
        val estimator = speedEstimatorForDiagnostics
        onEvent(
            LocationFixEvent(
                elapsedRealtimeMillis = now,
                sequenceNumber = locationSequenceNumber++,
                elapsedSinceStartMillis = now - sessionStartedAtElapsedMillis,
                provider = location.provider,
                reliable = reliability.reliable,
                rejectionReasons = reliability.reasons,
                satellitesUsedInFix = snapshot?.satellitesUsedInFix,
                satelliteCn0MinDbHz = snapshot?.cn0MinDbHz,
                satelliteCn0AvgDbHz = snapshot?.cn0AvgDbHz,
                satelliteCn0MaxDbHz = snapshot?.cn0MaxDbHz,
                qualityTier = mapper.qualityTier(reliability, speedAccuracyMps, horizontalAccuracyMeters),
                speedEstimatorDiagnostics = if (reliability.reliable && estimator != null && estimator.getLastAcceptWasMeasurement()) {
                    SpeedEstimatorDiagnostics(
                        processNoiseRegime = estimator.getLastProcessNoiseRegime(),
                        accuracyGateEngaged = estimator.getLastAccuracyGateEngaged(),
                        innovationEmaMagnitude = estimator.getLastInnovationEma(),
                        robustWeight = estimator.getLastRobustWeight(),
                    )
                } else {
                    null
                },
            ),
        )
        val buffered = pendingEventsDuringTick.toList()
        pendingEventsDuringTick.clear()
        buffered.forEach { onEvent(it) }
    }

    private fun tickTimer(elapsedRealtimeMillis: Long) {
        val second = elapsedRealtimeMillis / TICK_MILLIS
        if (second == lastTimerSecond) return
        lastTimerSecond = second
        val gpsOutage = gpsFreshness.isOutage(elapsedRealtimeMillis)
        sampleDeviceHealthIfDue(elapsedRealtimeMillis)
        coordinator?.advanceTimer(elapsedRealtimeMillis, signalGpsOutage = gpsOutage)
    }

    private fun sampleDeviceHealthIfDue(elapsedRealtimeMillis: Long) {
        val bucket = elapsedRealtimeMillis / DEVICE_HEALTH_BUCKET_MILLIS
        if (bucket == lastDeviceHealthBucket) return
        lastDeviceHealthBucket = bucket
        onEvent(
            DeviceHealthSampledEvent(
                elapsedRealtimeMillis = elapsedRealtimeMillis,
                batteryPercent = currentBatteryPercent(),
                thermalStatus = currentThermalStatus(),
            ),
        )
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
        onEvent = {}
        speedEstimatorForDiagnostics = null
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
        const val ACTION_APP_FOREGROUNDED = "dev.fred.pacorun.action.APP_FOREGROUNDED"
        const val ACTION_APP_BACKGROUNDED = "dev.fred.pacorun.action.APP_BACKGROUNDED"
        private const val CHANNEL_ID = "run_session"
        private const val NOTIFICATION_ID = 1
        private const val TICK_MILLIS = 1_000L
        private const val KEY_SESSION_OPEN = "session_open"
        private const val DEVICE_HEALTH_BUCKET_MILLIS = 30_000L
    }
}
