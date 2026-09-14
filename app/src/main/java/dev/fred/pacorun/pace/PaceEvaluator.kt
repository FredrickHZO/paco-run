package dev.fred.pacorun.pace

import dev.fred.pacorun.eventlog.PaceCandidateAbandonReason
import dev.fred.pacorun.eventlog.PaceCandidateAbandonedEvent
import dev.fred.pacorun.eventlog.PaceCandidateStartedEvent
import dev.fred.pacorun.eventlog.PaceStateConfirmedEvent
import dev.fred.pacorun.eventlog.SessionEvent
import dev.fred.pacorun.location.LocationSample

class PaceEvaluator(private var config: PaceConfig, private val onEvent: (SessionEvent) -> Unit = {}) {
    var current = PaceResult(PaceState.ACQUIRING_GPS, null, null)
        private set

    private var confirmedPace: PaceState? = null
    private var candidatePace: PaceState? = null
    private var candidateSamples = 0
    private var gpsWeak = false
    private var lastPaceCueMillis: Long? = null
    private val pendingPaceCues = ArrayDeque<Cue>()

    fun updateConfig(config: PaceConfig) {
        this.config = config
        confirmedPace = null
        clearCandidate()
        gpsWeak = false
        lastPaceCueMillis = null
        pendingPaceCues.clear()
        current = PaceResult(PaceState.ACQUIRING_GPS, null, null)
    }

    fun accept(sample: LocationSample): PaceResult {
        if (!sample.reliable) return acceptUnreliable(sample.elapsedRealtimeMillis)

        val gpsCue = if (gpsWeak && config.gpsCuesEnabled) Cue.GOOD_GPS else null
        gpsWeak = false

        val pace = sample.paceState()
        if (pace == confirmedPace) {
            abandonCandidateIfAny(sample.elapsedRealtimeMillis, PaceCandidateAbandonReason.SNAPPED_BACK_TO_CONFIRMED)
            val paceCue = repeatedCue(pace, sample.elapsedRealtimeMillis)
            return update(
                confirmedPace ?: PaceState.ACQUIRING_GPS,
                sample.speedKmh,
                selectCue(gpsCue, paceCue, sample.elapsedRealtimeMillis),
            )
        }

        if (pace == candidatePace) {
            candidateSamples++
        } else {
            val supersededCandidate = candidatePace
            if (supersededCandidate != null) {
                onEvent(
                    PaceCandidateAbandonedEvent(
                        sample.elapsedRealtimeMillis,
                        supersededCandidate,
                        candidateSamples,
                        PaceCandidateAbandonReason.SUPERSEDED_BY_NEW_CANDIDATE,
                    ),
                )
            }
            candidatePace = pace
            candidateSamples = 1
            onEvent(PaceCandidateStartedEvent(sample.elapsedRealtimeMillis, pace))
        }

        if (candidateSamples < config.confirmationSamples) {
            return update(
                confirmedPace ?: PaceState.ACQUIRING_GPS,
                sample.speedKmh,
                selectCue(gpsCue, null, sample.elapsedRealtimeMillis),
            )
        }

        val previous = confirmedPace
        confirmedPace = pace
        clearCandidate()
        val paceCue = when (pace) {
            PaceState.TOO_SLOW -> Cue.TOO_SLOW
            PaceState.TOO_FAST -> Cue.TOO_FAST
            PaceState.GOOD -> if (previous == PaceState.TOO_SLOW || previous == PaceState.TOO_FAST) {
                Cue.GOOD_PACE
            } else {
                null
            }
            else -> null
        }
        onEvent(
            PaceStateConfirmedEvent(
                elapsedRealtimeMillis = sample.elapsedRealtimeMillis,
                fromState = previous,
                toState = pace,
                speedKmh = sample.speedKmh,
                producedAudibleCue = paceCue != null,
            ),
        )
        lastPaceCueMillis = null
        return update(pace, sample.speedKmh, selectCue(gpsCue, paceCue, sample.elapsedRealtimeMillis))
    }

    private fun acceptUnreliable(elapsedRealtimeMillis: Long): PaceResult {
        abandonCandidateIfAny(elapsedRealtimeMillis, PaceCandidateAbandonReason.GPS_BECAME_UNRELIABLE)
        val cue = if (!gpsWeak && config.gpsCuesEnabled) Cue.BAD_GPS else null
        gpsWeak = true
        return update(PaceState.GPS_WEAK, null, cue)
    }

    private fun abandonCandidateIfAny(elapsedRealtimeMillis: Long, reason: PaceCandidateAbandonReason) {
        val abandonedState = candidatePace
        if (abandonedState != null) {
            onEvent(PaceCandidateAbandonedEvent(elapsedRealtimeMillis, abandonedState, candidateSamples, reason))
        }
        clearCandidate()
    }

    private fun repeatedCue(pace: PaceState, elapsedMillis: Long): Cue? {
        val cue = when (pace) {
            PaceState.TOO_SLOW -> Cue.TOO_SLOW
            PaceState.TOO_FAST -> Cue.TOO_FAST
            else -> return null
        }
        if (cue in pendingPaceCues) return null
        val lastCue = lastPaceCueMillis ?: return null
        if (elapsedMillis < lastCue || elapsedMillis - lastCue < config.repeatMillis) return null
        return cue
    }

    private fun selectCue(gpsCue: Cue?, paceCue: Cue?, elapsedMillis: Long): Cue? {
        if (paceCue != null) pendingPaceCues.addLast(paceCue)
        if (gpsCue != null) return gpsCue
        return pendingPaceCues.removeFirstOrNull()?.also {
            if (it == Cue.TOO_SLOW || it == Cue.TOO_FAST) lastPaceCueMillis = elapsedMillis
        }
    }

    private fun LocationSample.paceState() = when {
        speedKmh < config.minKmh -> PaceState.TOO_SLOW
        speedKmh > config.maxKmh -> PaceState.TOO_FAST
        else -> PaceState.GOOD
    }

    private fun clearCandidate() {
        candidatePace = null
        candidateSamples = 0
    }

    private fun update(state: PaceState, speedKmh: Float?, cue: Cue?): PaceResult =
        PaceResult(state, speedKmh, cue).also { current = it }
}
