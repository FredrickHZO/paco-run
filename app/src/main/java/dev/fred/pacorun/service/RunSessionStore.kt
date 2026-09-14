package dev.fred.pacorun.service

import dev.fred.pacorun.audio.AudioCue
import dev.fred.pacorun.eventlog.DistanceChainBreakReason
import dev.fred.pacorun.eventlog.DistanceChainBrokenEvent
import dev.fred.pacorun.eventlog.SessionEvent
import dev.fred.pacorun.location.LocationSample
import dev.fred.pacorun.location.LocationSampleMapper
import dev.fred.pacorun.pace.PaceConfig
import dev.fred.pacorun.pace.PaceEvaluator
import dev.fred.pacorun.pace.PaceResult
import dev.fred.pacorun.pace.PaceState
import dev.fred.pacorun.routine.RoutineEngine
import dev.fred.pacorun.routine.RoutineInput
import dev.fred.pacorun.routine.RoutinePhase
import dev.fred.pacorun.routine.RoutineResult
import dev.fred.pacorun.settings.RunPreferences
import dev.fred.pacorun.settings.SpeedRange
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
        val activeRange: SpeedRange,
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

class GpsFreshnessTracker(
    private val maxAgeMillis: Long = LocationSampleMapper.DEFAULT_MAX_AGE_MILLIS,
) {
    private var latestReliableSampleElapsedRealtimeMillis: Long? = null

    fun record(sample: LocationSample) {
        if (sample.reliable) latestReliableSampleElapsedRealtimeMillis = sample.elapsedRealtimeMillis
    }

    fun isOutage(nowElapsedRealtimeMillis: Long): Boolean = latestReliableSampleElapsedRealtimeMillis?.let {
        nowElapsedRealtimeMillis - it > maxAgeMillis
    } ?: true
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
    val horizontalAccuracyMeters: Float? = null,
)

interface SessionDistanceEngine {
    fun accept(position: LocationPosition, reliable: Boolean): Double
    fun breakChain(): Double
}

class DistanceAccumulator(
    private val maxSpeedMetersPerSecond: Double = 15.0,
    // Floor: even with excellent reported accuracy, don't trust movement below this as real
    // (coordinate rounding / residual noise). 2m sits just under the ~3-5m combined-accuracy
    // threshold the dynamic term below already produces in good GPS conditions.
    private val minMovementMeters: Double = 2.0,
    // Dynamic component: GPS jitter scales with the fix's own reported accuracy, so a fixed
    // threshold either lets noise through in poor conditions or is overly strict in good ones.
    // Half the combined accuracy of the two points is a standard "more likely real than noise"
    // heuristic for roughly-Gaussian position error.
    private val jitterAccuracyFraction: Double = 0.5,
    private val onEvent: (SessionEvent) -> Unit = {},
) : SessionDistanceEngine {
    private var anchor: LocationPosition? = null
    // Last accepted raw fix, tracked separately from `anchor`: the anchor can stay frozen for
    // many seconds while jitter-suppressed movement accrues, which would otherwise widen the
    // impossible-jump budget below (proportional to elapsed time) far past what's physically
    // sane for a single fix-to-fix gap. The sanity check always measures against this instead.
    private var lastFix: LocationPosition? = null

    override fun accept(position: LocationPosition, reliable: Boolean): Double {
        if (!reliable || !position.isValid()) {
            onEvent(DistanceChainBrokenEvent(position.elapsedRealtimeMillis, DistanceChainBreakReason.UNRELIABLE_OR_INVALID_FIX))
            return breakChain()
        }
        val previousAnchor = anchor
        val previousFix = lastFix
        if (previousAnchor == null || previousFix == null) {
            anchor = position
            lastFix = position
            return 0.0
        }
        val fixElapsedMillis = position.elapsedRealtimeMillis - previousFix.elapsedRealtimeMillis
        val fixDistance = haversineMeters(previousFix, position)
        if (fixElapsedMillis <= 0L || !fixDistance.isFinite() || fixDistance > maxSpeedMetersPerSecond * fixElapsedMillis / 1_000.0) {
            onEvent(DistanceChainBrokenEvent(position.elapsedRealtimeMillis, DistanceChainBreakReason.IMPLAUSIBLE_JUMP))
            return breakChain()
        }
        lastFix = position
        // Below the jitter threshold: leave the anchor where it is, so noise oscillating
        // around a stationary point never accumulates, while genuine slow movement still
        // counts in full as soon as it clears the threshold measured from that stable anchor.
        val distance = haversineMeters(previousAnchor, position)
        if (distance < jitterThreshold(previousAnchor, position)) return 0.0
        anchor = position
        return distance
    }

    private fun jitterThreshold(previous: LocationPosition, current: LocationPosition): Double {
        val combinedAccuracy = (previous.horizontalAccuracyMeters ?: 0f) + (current.horizontalAccuracyMeters ?: 0f)
        return maxOf(minMovementMeters, combinedAccuracy * jitterAccuracyFraction)
    }

    override fun breakChain(): Double = 0.0.also { anchor = null; lastFix = null }

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

interface SessionSpeedEngine {
    fun accept(sample: LocationSample, position: LocationPosition, reliable: Boolean): Double
    fun breakChain(): Double
}

class DopplerSpeedEngine : SessionSpeedEngine {
    override fun accept(sample: LocationSample, position: LocationPosition, reliable: Boolean): Double =
        sample.speedKmh.toDouble()

    // No chain to break for an instantaneous Doppler reading -- no-op, only here to satisfy the interface.
    override fun breakChain(): Double = 0.0
}

class SessionCoordinator(
    private val preferences: RunPreferences,
    private val routineEngine: SessionRoutineEngine,
    private val paceEvaluator: SessionPaceEvaluator,
    private val onCues: (List<AudioCue>, Float) -> Unit,
    private val publish: (RunSessionState) -> Unit,
    private val startedAtElapsedMillis: Long = 0L,
    private val distanceEngine: SessionDistanceEngine = DistanceAccumulator(),
    private val speedEngine: SessionSpeedEngine = KalmanSpeedEngine(),
    private val onSpeedComputed: (Double?) -> Unit = {},
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
    private var lastReliableFixElapsedMillis: Long = startedAtElapsedMillis
    private var lastRoutineTimingBucket = Long.MIN_VALUE
    private var lastRoutineTimingElapsedRealtimeMillis = Long.MIN_VALUE

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
            actualReliable -> {
                val engineSpeedKmh = speedEngine.accept(requireNotNull(sample), requireNotNull(position), reliable = true)
                onSpeedComputed(engineSpeedKmh)
                requireNotNull(sample).copy(speedKmh = engineSpeedKmh.toFloat())
            }
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
            distanceEngine.breakChain()
        }
        if (actualReliable) {
            lastReliableFixElapsedMillis = elapsedRealtimeMillis
        } else if (
            !gpsReliable &&
            elapsedRealtimeMillis - lastReliableFixElapsedMillis >= LocationSampleMapper.DEFAULT_MAX_AGE_MILLIS
        ) {
            // Tempo REALE trascorso dall'ultimo fix affidabile, non "che tipo di chiamata ha
            // innescato la transizione" (il gate del Task 9 confondeva le due cose: un flusso
            // continuo di fix inaffidabili non passa mai da qui con signalGpsOutage=true, quindi
            // non si resettava mai, non importa quanto durasse -- vedi review Task 9).
            // Chiamare breakChain() più volte nello stesso episodio è innocuo (idempotente),
            // quindi non serve un flag "già fatto per questo episodio". Stessa soglia usata
            // altrove nel progetto per lo stesso concetto (non una soglia nuova). Vedi roadmap
            // G14 per il perché NON si allenta oltre questo (interruzioni lunghe + spostamento
            // reale nel frattempo restano un problema di Fase 1, deliberatamente non affrontato
            // qui).
            speedEngine.breakChain()
        }
        val distanceDelta = if (actualReliable) {
            distanceEngine.accept(requireNotNull(position), reliable = true)
        } else {
            0.0
        }
        val routine = routineEngine.accept(
            RoutineInput(
                elapsedRealtimeMillis = routineTimingElapsedRealtimeMillis(elapsedRealtimeMillis),
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
                activeRange = routine.activeRange,
            ),
        )
    }

    private fun routineTimingElapsedRealtimeMillis(elapsedRealtimeMillis: Long): Long {
        val bucket = elapsedRealtimeMillis / TIMER_BUCKET_MILLIS
        if (bucket == lastRoutineTimingBucket) return lastRoutineTimingElapsedRealtimeMillis
        lastRoutineTimingBucket = bucket
        lastRoutineTimingElapsedRealtimeMillis = elapsedRealtimeMillis
        return elapsedRealtimeMillis
    }

    private companion object {
        const val TIMER_BUCKET_MILLIS = 1_000L
    }
}

class PendingSessionCoordinator(
    private val preferences: RunPreferences,
    private val routineEngine: SessionRoutineEngine,
    private val paceEvaluator: SessionPaceEvaluator,
    private val onCues: (List<AudioCue>, Float) -> Unit,
    private val publish: (RunSessionState) -> Unit,
    private val speedEngine: SessionSpeedEngine = KalmanSpeedEngine(),
    private val onSpeedComputed: (Double?) -> Unit = {},
) {
    private var activeCoordinator: SessionCoordinator? = null

    val isActivated: Boolean get() = activeCoordinator != null

    fun tick(elapsedRealtimeMillis: Long, sample: LocationSample?, position: LocationPosition?) {
        val coordinator = activeCoordinator
        if (coordinator != null) {
            coordinator.tick(elapsedRealtimeMillis, sample, position)
            return
        }
        if (sample?.reliable == true && position != null) {
            activate(sample.elapsedRealtimeMillis).tick(sample.elapsedRealtimeMillis, sample, position)
        }
    }

    fun advanceTimer(elapsedRealtimeMillis: Long, signalGpsOutage: Boolean) {
        activeCoordinator?.advanceTimer(elapsedRealtimeMillis, signalGpsOutage)
    }

    private fun activate(startedAtElapsedMillis: Long): SessionCoordinator =
        SessionCoordinator(
            preferences = preferences,
            routineEngine = routineEngine,
            paceEvaluator = paceEvaluator,
            onCues = onCues,
            publish = publish,
            startedAtElapsedMillis = startedAtElapsedMillis,
            speedEngine = speedEngine,
            onSpeedComputed = onSpeedComputed,
        ).also { activeCoordinator = it }
}

class RoutineEngineAdapter(private val engine: RoutineEngine) : SessionRoutineEngine {
    override fun accept(input: RoutineInput) = engine.accept(input)
}

class PaceEvaluatorAdapter(private val evaluator: PaceEvaluator) : SessionPaceEvaluator {
    override fun updateConfig(config: PaceConfig) = evaluator.updateConfig(config)
    override fun accept(sample: LocationSample) = evaluator.accept(sample)
}

class KalmanSpeedEngine(private val speedEstimator: SpeedEstimator = SpeedEstimator()) : SessionSpeedEngine {
    override fun accept(sample: LocationSample, position: LocationPosition, reliable: Boolean): Double =
        speedEstimator.accept(position, reliable)

    override fun breakChain(): Double = speedEstimator.breakChain()
}
