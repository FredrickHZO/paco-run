package dev.fred.pacorun.eventlog

import dev.fred.pacorun.pace.PaceState
import dev.fred.pacorun.routine.RoutinePhase
import dev.fred.pacorun.settings.RunPreferences

sealed interface SessionEvent {
    val elapsedRealtimeMillis: Long
}

// --- 5.1: one-time session start ---

data class SessionStarted(
    override val elapsedRealtimeMillis: Long,
    val deviceModel: String,
    val osVersionRelease: String,
    val osApiLevel: Int,
    val appGitCommitHash: String,
    val preferences: RunPreferences,
    val sensors: SensorAvailability,
    val networkTypeAtStart: NetworkType,
) : SessionEvent

data class SensorAvailability(
    val hasBarometer: Boolean,
    val hasMagnetometer: Boolean,
    val hasAccelerometer: Boolean,
    val hasGyroscope: Boolean,
    val hasStepCounter: Boolean,
    val hasStepDetector: Boolean,
)

enum class NetworkType { WIFI, CELLULAR, OFFLINE, OTHER }

// --- 5.2: one per raw GPS fix ---

data class LocationFixEvent(
    override val elapsedRealtimeMillis: Long,
    val sequenceNumber: Int,
    val elapsedSinceStartMillis: Long,
    val provider: String?,
    val reliable: Boolean,
    val rejectionReasons: List<RejectionReason>,
    val satellitesUsedInFix: Int?,
    val satelliteCn0MinDbHz: Float?,
    val satelliteCn0AvgDbHz: Float?,
    val satelliteCn0MaxDbHz: Float?,
    val qualityTier: QualityTier,
    val speedEstimatorDiagnostics: SpeedEstimatorDiagnostics?,
) : SessionEvent

enum class RejectionReason {
    MISSING_OR_INVALID_SPEED,
    MISSING_OR_INVALID_SPEED_ACCURACY,
    SPEED_ACCURACY_TOO_HIGH,
    HORIZONTAL_ACCURACY_TOO_HIGH,
    FIX_TOO_OLD,
}

enum class QualityTier { OTTIMA, BUONA, DEGRADATA, SCARSA }

data class SpeedEstimatorDiagnostics(
    val processNoiseRegime: ProcessNoiseRegime,
    val accuracyGateEngaged: Boolean,
    val innovationEmaMagnitude: Double,
)

enum class ProcessNoiseRegime { Q_LOW, Q_HIGH }

// --- 5.3: reception silence ---

data class GpsSilentGapEvent(
    override val elapsedRealtimeMillis: Long,
    val gapMillis: Long,
) : SessionEvent

// --- 5.4: cue/routine/pace discrete events ---

data class AudioCueEmittedEvent(
    override val elapsedRealtimeMillis: Long,
    val cueType: String,
    val cumulativeDistanceMeters: Double,
    val speedKmh: Float?,
) : SessionEvent

data class RoutinePhaseChangedEvent(
    override val elapsedRealtimeMillis: Long,
    val fromPhase: RoutinePhase,
    val toPhase: RoutinePhase,
    val cumulativeDistanceMeters: Double,
) : SessionEvent

data class PaceStateConfirmedEvent(
    override val elapsedRealtimeMillis: Long,
    val fromState: PaceState?,
    val toState: PaceState,
    val speedKmh: Float?,
    val producedAudibleCue: Boolean,
) : SessionEvent

data class PaceCandidateStartedEvent(
    override val elapsedRealtimeMillis: Long,
    val candidateState: PaceState,
) : SessionEvent

data class PaceCandidateAbandonedEvent(
    override val elapsedRealtimeMillis: Long,
    val candidateState: PaceState,
    val samplesAccumulated: Int,
    val reason: PaceCandidateAbandonReason,
) : SessionEvent

enum class PaceCandidateAbandonReason { SNAPPED_BACK_TO_CONFIRMED, GPS_BECAME_UNRELIABLE, SUPERSEDED_BY_NEW_CANDIDATE }

// --- 5.5: chain-break events ---

data class SpeedChainBrokenEvent(
    override val elapsedRealtimeMillis: Long,
    val reason: SpeedChainBreakReason,
) : SessionEvent

enum class SpeedChainBreakReason {
    UNRELIABLE_OR_INVALID_FIX,
    NON_POSITIVE_TIME_DELTA,
    NON_FINITE_RESULT,
    STALE_NO_RECENT_RELIABLE_FIX,
}

data class DistanceChainBrokenEvent(
    override val elapsedRealtimeMillis: Long,
    val reason: DistanceChainBreakReason,
) : SessionEvent

enum class DistanceChainBreakReason {
    UNRELIABLE_OR_INVALID_FIX,
    IMPLAUSIBLE_JUMP,
    GPS_OUTAGE_ENTERED,
}

// --- 5.6: environment/device events ---

data class AppLifecycleChangedEvent(
    override val elapsedRealtimeMillis: Long,
    val transition: LifecycleTransition,
) : SessionEvent

enum class LifecycleTransition { BACKGROUNDED, FOREGROUNDED, SERVICE_RESTARTED_BY_SYSTEM }

data class DeviceHealthSampledEvent(
    override val elapsedRealtimeMillis: Long,
    val batteryPercent: Int?,
    val thermalStatus: ThermalStatus,
) : SessionEvent

enum class ThermalStatus { NONE, LIGHT, MODERATE, SEVERE, CRITICAL, EMERGENCY, SHUTDOWN, UNKNOWN }
