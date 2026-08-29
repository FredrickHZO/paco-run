package dev.fred.pacorun.service

import dev.fred.pacorun.audio.AudioCue
import dev.fred.pacorun.location.LocationSample
import dev.fred.pacorun.pace.PaceConfig
import dev.fred.pacorun.pace.PaceEvaluator
import dev.fred.pacorun.pace.PaceResult
import dev.fred.pacorun.pace.PaceState
import dev.fred.pacorun.routine.RoutineEngine
import dev.fred.pacorun.routine.RoutineInput
import dev.fred.pacorun.routine.RoutinePhase
import dev.fred.pacorun.routine.RoutineResult
import dev.fred.pacorun.settings.RunPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

sealed interface RunSessionState {
    data object Idle : RunSessionState
    data object AcquiringGps : RunSessionState
    data class Running(
        val phase: RoutinePhase,
        val pace: PaceResult,
        val speedKmh: Float?,
        val gpsReliable: Boolean,
        val startedAtElapsedMillis: Long,
        val elapsedMillis: Long,
        val intervalRemainingMillis: Long?,
        val totalDistanceMeters: Double,
        val routineDistanceMeters: Double,
    ) : RunSessionState
}

object RunSessionStore {
    private val mutableState = MutableStateFlow<RunSessionState>(RunSessionState.Idle)
    val state: StateFlow<RunSessionState> = mutableState.asStateFlow()

    internal fun publish(state: RunSessionState) {
        mutableState.value = state
    }
}

data class SessionStartPrerequisites(val hasFineLocation: Boolean) {
    val canStartLocationForegroundService: Boolean get() = hasFineLocation
}

class SessionNotificationTracker {
    private var displayedText: String? = null

    fun textFor(state: RunSessionState): String = when (state) {
        RunSessionState.Idle -> "Sessione terminata"
        RunSessionState.AcquiringGps -> "Acquisizione GPS"
        is RunSessionState.Running -> state.phase.name.replace('_', ' ')
    }

    fun markDisplayed(state: RunSessionState) {
        displayedText = textFor(state)
    }

    fun shouldUpdate(state: RunSessionState): Boolean {
        val nextText = textFor(state)
        if (nextText == displayedText) return false
        displayedText = nextText
        return true
    }
}

data class LocationPosition(
    val latitude: Double,
    val longitude: Double,
    val elapsedRealtimeMillis: Long,
)

class DistanceAccumulator(
    private val maxSpeedMetersPerSecond: Double = 15.0,
) {
    private var anchor: LocationPosition? = null

    fun accept(position: LocationPosition, reliable: Boolean): Double {
        if (!reliable || !position.isValid()) return breakChain()
        val previous = anchor ?: return 0.0.also { anchor = position }
        val elapsedMillis = position.elapsedRealtimeMillis - previous.elapsedRealtimeMillis
        val distance = haversineMeters(previous, position)
        if (elapsedMillis <= 0L || !distance.isFinite() || distance > maxSpeedMetersPerSecond * elapsedMillis / 1_000.0) {
            anchor = null
            return 0.0
        }
        anchor = position
        return distance
    }

    fun breakChain(): Double = 0.0.also { anchor = null }

    private fun LocationPosition.isValid() = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0

    private fun haversineMeters(first: LocationPosition, second: LocationPosition): Double {
        val latitudeDelta = Math.toRadians(second.latitude - first.latitude)
        val longitudeDelta = Math.toRadians(second.longitude - first.longitude)
        val a = sin(latitudeDelta / 2).pow(2) +
            cos(Math.toRadians(first.latitude)) * cos(Math.toRadians(second.latitude)) * sin(longitudeDelta / 2).pow(2)
        return 6_371_000.0 * 2.0 * asin(sqrt(a))
    }
}

interface SessionRoutineEngine {
    fun accept(input: RoutineInput): RoutineResult
}

interface SessionPaceEvaluator {
    fun updateConfig(config: PaceConfig)
    fun accept(sample: LocationSample): PaceResult
}

class SessionCoordinator(
    private val preferences: RunPreferences,
    private val routineEngine: SessionRoutineEngine,
    private val paceEvaluator: SessionPaceEvaluator,
    private val onCues: (List<AudioCue>, Float) -> Unit,
    private val publish: (RunSessionState) -> Unit,
    private val startedAtElapsedMillis: Long = 0L,
    private val distanceAccumulator: DistanceAccumulator = DistanceAccumulator(),
) {
    fun tick(elapsedRealtimeMillis: Long, sample: LocationSample?, position: LocationPosition?) {
        process(elapsedRealtimeMillis, sample, position, signalGpsOutage = false)
    }

    fun advanceTimer(elapsedRealtimeMillis: Long, signalGpsOutage: Boolean) {
        process(elapsedRealtimeMillis, sample = null, position = null, signalGpsOutage = signalGpsOutage)
    }

    private var latestPace = PaceResult(PaceState.ACQUIRING_GPS, null, null)
    private var latestReliableSample: LocationSample? = null
    private var outageActive = false

    private fun process(
        elapsedRealtimeMillis: Long,
        sample: LocationSample?,
        position: LocationPosition?,
        signalGpsOutage: Boolean,
    ) {
        val wasOutage = outageActive
        val actualReliable = sample?.reliable == true && position != null
        val timerRetainsFreshGps = sample == null && !signalGpsOutage && latestReliableSample != null
        val gpsReliable = actualReliable || timerRetainsFreshGps
        val paceSample = when {
            actualReliable -> requireNotNull(sample)
            timerRetainsFreshGps -> requireNotNull(latestReliableSample)
            else -> sample ?: LocationSample(0f, null, false, elapsedRealtimeMillis)
        }
        val enteredOutage = !gpsReliable && !outageActive && (sample != null || signalGpsOutage)
        if (actualReliable) {
            latestReliableSample = paceSample
            outageActive = false
        } else if (enteredOutage) {
            latestReliableSample = null
            outageActive = true
            distanceAccumulator.breakChain()
        }
        val distanceDelta = if (actualReliable) {
            distanceAccumulator.accept(requireNotNull(position), reliable = true)
        } else {
            0.0
        }
        val routine = routineEngine.accept(
            RoutineInput(
                elapsedRealtimeMillis = elapsedRealtimeMillis,
                reliableDistanceDeltaMeters = distanceDelta,
                currentReliableSpeedKmh = paceSample.speedKmh.takeIf { gpsReliable },
                gpsReliable = gpsReliable,
            ),
        )
        if (routine.profileChanged) {
            paceEvaluator.updateConfig(
                PaceConfig(
                    minKmh = routine.activeRange.minKmh,
                    maxKmh = routine.activeRange.maxKmh,
                    confirmationSamples = preferences.confirmationSamples,
                    repeatMillis = preferences.repeatMillis,
                    gpsCuesEnabled = preferences.gpsCuesEnabled,
                ),
            )
            latestPace = PaceResult(PaceState.ACQUIRING_GPS, null, null)
        }
        if (routine.profileChanged && wasOutage) {
            latestPace = paceEvaluator.accept(LocationSample(0f, null, false, elapsedRealtimeMillis)).copy(cue = null)
        }
        val pace = when {
            sample != null -> paceEvaluator.accept(paceSample)
            signalGpsOutage && enteredOutage -> paceEvaluator.accept(paceSample)
            else -> latestPace
        }
        latestPace = pace
        val cues = routine.cues.map { AudioCue.valueOf(it.name) } + listOfNotNull(pace.cue?.let { AudioCue.valueOf(it.name) })
        if (cues.isNotEmpty()) onCues(cues, preferences.cueVolume)
        publish(
            RunSessionState.Running(
                phase = routine.phase,
                pace = pace,
                speedKmh = paceSample.speedKmh.takeIf { gpsReliable },
                gpsReliable = gpsReliable,
                startedAtElapsedMillis = startedAtElapsedMillis,
                elapsedMillis = (elapsedRealtimeMillis - startedAtElapsedMillis).coerceAtLeast(0L),
                intervalRemainingMillis = routine.intervalRemainingMillis,
                totalDistanceMeters = routine.totalDistanceMeters,
                routineDistanceMeters = routine.routineDistanceMeters,
            ),
        )
    }
}

class RoutineEngineAdapter(private val engine: RoutineEngine) : SessionRoutineEngine {
    override fun accept(input: RoutineInput) = engine.accept(input)
}

class PaceEvaluatorAdapter(private val evaluator: PaceEvaluator) : SessionPaceEvaluator {
    override fun updateConfig(config: PaceConfig) = evaluator.updateConfig(config)
    override fun accept(sample: LocationSample) = evaluator.accept(sample)
}
