package dev.fred.pacorun.service

import dev.fred.pacorun.eventlog.DeviceHealthSampledEvent
import dev.fred.pacorun.eventlog.GpsSilentGapEvent
import dev.fred.pacorun.eventlog.LocationFixEvent
import dev.fred.pacorun.eventlog.LocationReliability
import dev.fred.pacorun.eventlog.QualityTier
import dev.fred.pacorun.eventlog.SessionEvent
import dev.fred.pacorun.eventlog.ThermalStatus
import dev.fred.pacorun.location.LocationSample
import dev.fred.pacorun.pace.PaceEvaluator
import dev.fred.pacorun.pace.paceConfig
import dev.fred.pacorun.routine.RoutineEngine
import dev.fred.pacorun.settings.RunPreferences

class RunSessionEventSequencer(
    preferences: RunPreferences,
    onCues: (List<CueSignal>) -> Unit,
    publish: (RunSessionState) -> Unit,
    private val sessionStartedAtElapsedMillis: Long,
) {
    private val pendingEvents = mutableListOf<SessionEvent>()
    private var locationSequenceNumber = 0
    private var lastTimerSecond = Long.MIN_VALUE
    private var lastDeviceHealthBucket = Long.MIN_VALUE
    private var engineSpeedKmh: Double? = null

    private val gpsFreshness = GpsFreshnessTracker()
    private val gpsSilentGap = GpsSilentGapTracker().also { it.reset(sessionStartedAtElapsedMillis) }

    private val onEvent: (SessionEvent) -> Unit = { pendingEvents += it }

    private val speedEstimator = SpeedEstimator(onEvent = onEvent)
    private val speedEngine = KalmanSpeedEngine(speedEstimator)
    private val coordinator = PendingSessionCoordinator(
        preferences = preferences,
        routineEngine = RoutineEngineAdapter(RoutineEngine(preferences, onEvent = onEvent)),
        paceEvaluator = PaceEvaluatorAdapter(PaceEvaluator(preferences.paceConfig(), onEvent = onEvent)),
        onCues = onCues,
        publish = publish,
        distanceEngine = DistanceAccumulator(onEvent = onEvent),
        speedEngine = speedEngine,
        onSpeedComputed = { engineSpeedKmh = it },
        onEvent = onEvent,
    )

    fun onLocationFix(
        sample: LocationSample,
        position: LocationPosition,
        snapshot: GnssSnapshot?,
        provider: String?,
        reliability: LocationReliability,
        qualityTier: QualityTier,
        now: Long,
    ): LocationFixResult {
        gpsFreshness.record(sample)
        val silentGapEvent = gpsSilentGap.recordCallback(now)?.let { GpsSilentGapEvent(now, it) }

        engineSpeedKmh = null
        try {
            coordinator.tick(elapsedRealtimeMillis = now, sample = sample, position = position)
        } catch (e: Throwable) {
            pendingEvents.clear()
            throw e
        }

        val diagnostics = if (reliability.reliable) {
            (speedEngine as? SpeedEngineDiagnostics)?.let { if (it.lastAcceptWasMeasurement()) it.diagnostics() else null }
        } else {
            null
        }
        val fixEvent = LocationFixEvent(
            elapsedRealtimeMillis = now,
            sequenceNumber = locationSequenceNumber++,
            elapsedSinceStartMillis = now - sessionStartedAtElapsedMillis,
            provider = provider,
            reliable = reliability.reliable,
            rejectionReasons = reliability.reasons,
            satellitesUsedInFix = snapshot?.satellitesUsedInFix,
            satelliteCn0MinDbHz = snapshot?.cn0MinDbHz,
            satelliteCn0AvgDbHz = snapshot?.cn0AvgDbHz,
            satelliteCn0MaxDbHz = snapshot?.cn0MaxDbHz,
            qualityTier = qualityTier,
            speedEstimatorDiagnostics = diagnostics,
            satelliteGeometry = snapshot?.geometry ?: emptyList(),
        )

        val buffered = pendingEvents.toList()
        pendingEvents.clear()
        val events = buildList {
            if (silentGapEvent != null) add(silentGapEvent)
            add(fixEvent)
            addAll(buffered)
        }
        return LocationFixResult(events, engineSpeedKmh)
    }

    fun onTimerTick(now: Long): TimerTickResult {
        val second = now / TICK_MILLIS
        if (second == lastTimerSecond) return TimerTickResult(emptyList(), false)
        lastTimerSecond = second

        val gpsOutage = gpsFreshness.isOutage(now)
        try {
            coordinator.advanceTimer(now, signalGpsOutage = gpsOutage)
        } catch (e: Throwable) {
            pendingEvents.clear()
            throw e
        }

        val buffered = pendingEvents.toList()
        pendingEvents.clear()

        val bucket = now / DEVICE_HEALTH_BUCKET_MILLIS
        val due = bucket != lastDeviceHealthBucket
        if (due) lastDeviceHealthBucket = bucket

        return TimerTickResult(buffered, due)
    }

    fun recordDeviceHealthSample(batteryPercent: Int?, thermalStatus: ThermalStatus, now: Long): SessionEvent =
        DeviceHealthSampledEvent(elapsedRealtimeMillis = now, batteryPercent = batteryPercent, thermalStatus = thermalStatus)

    companion object {
        const val TICK_MILLIS = 1_000L
        private const val DEVICE_HEALTH_BUCKET_MILLIS = 30_000L
    }
}

data class LocationFixResult(val events: List<SessionEvent>, val engineSpeedKmh: Double?)
data class TimerTickResult(val events: List<SessionEvent>, val deviceHealthSampleDue: Boolean)
