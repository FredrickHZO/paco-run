package dev.fred.pacorun.pace

import dev.fred.pacorun.location.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaceEvaluatorTest {
    private val config = PaceConfig(7.5f, 8.5f, 2, 5_000L, gpsCuesEnabled = true)

    @Test
    fun startsByAcquiringGps() {
        val evaluator = PaceEvaluator(config)

        assertEquals(PaceResult(PaceState.ACQUIRING_GPS, null, null), evaluator.current)
        assertEquals(
            PaceResult(PaceState.ACQUIRING_GPS, 8f, null),
            evaluator.accept(sample(8f, 0L)),
        )
        assertEquals(PaceState.GOOD, evaluator.accept(sample(8f, 1_000L)).state)
    }

    @Test
    fun fastRequiresTwoReliableSamples() {
        val evaluator = PaceEvaluator(config)

        assertNull(evaluator.accept(sample(8.7f, 0L)).cue)
        assertEquals(Cue.TOO_FAST, evaluator.accept(sample(8.8f, 1_000L)).cue)
    }

    @Test
    fun slowRequiresTwoReliableSamples() {
        val evaluator = PaceEvaluator(config)

        assertNull(evaluator.accept(sample(7.4f, 0L)).cue)
        assertEquals(Cue.TOO_SLOW, evaluator.accept(sample(7.3f, 1_000L)).cue)
    }

    @Test
    fun paceConfirmationRequiresConsecutiveCandidates() {
        val evaluator = PaceEvaluator(config)

        evaluator.accept(sample(8.7f, 0L))
        evaluator.accept(sample(8f, 1_000L))
        assertNull(evaluator.accept(sample(8.8f, 2_000L)).cue)
        assertEquals(Cue.TOO_FAST, evaluator.accept(sample(8.9f, 3_000L)).cue)
    }

    @Test
    fun repeatsFastAfterConfiguredDelay() {
        val evaluator = evaluatorAlreadyFast()

        assertNull(evaluator.accept(sample(8.8f, 5_999L)).cue)
        assertEquals(Cue.TOO_FAST, evaluator.accept(sample(8.8f, 6_000L)).cue)
    }

    @Test
    fun repeatsSlowAfterConfiguredDelay() {
        val evaluator = PaceEvaluator(config)
        evaluator.accept(sample(7.4f, 0L))
        assertEquals(Cue.TOO_SLOW, evaluator.accept(sample(7.3f, 1_000L)).cue)

        assertNull(evaluator.accept(sample(7.2f, 5_999L)).cue)
        assertEquals(Cue.TOO_SLOW, evaluator.accept(sample(7.2f, 6_000L)).cue)
    }

    @Test
    fun goodPaceFiresOnceAfterReturning() {
        val evaluator = evaluatorAlreadyFast()

        assertNull(evaluator.accept(sample(8f, 2_000L)).cue)
        assertEquals(Cue.GOOD_PACE, evaluator.accept(sample(8f, 3_000L)).cue)
        assertNull(evaluator.accept(sample(8f, 4_000L)).cue)
    }

    @Test
    fun goodPaceAlsoFiresOnceAfterReturningFromSlow() {
        val evaluator = PaceEvaluator(config)
        evaluator.accept(sample(7.4f, 0L))
        evaluator.accept(sample(7.3f, 1_000L))

        assertNull(evaluator.accept(sample(8f, 2_000L)).cue)
        assertEquals(Cue.GOOD_PACE, evaluator.accept(sample(8f, 3_000L)).cue)
        assertNull(evaluator.accept(sample(8f, 4_000L)).cue)
    }

    @Test
    fun weakGpsSuspendsPaceAndCuesOnce() {
        val evaluator = PaceEvaluator(config)

        assertEquals(Cue.BAD_GPS, evaluator.accept(unreliable(0L)).cue)
        assertNull(evaluator.accept(unreliable(1_000L)).cue)
        val recovered = evaluator.accept(sample(8f, 2_000L))
        assertEquals(PaceState.ACQUIRING_GPS, recovered.state)
        assertEquals(Cue.GOOD_GPS, recovered.cue)
    }

    @Test
    fun unreliableSampleBreaksPaceConfirmation() {
        val evaluator = PaceEvaluator(config)

        evaluator.accept(sample(8.7f, 0L))
        evaluator.accept(unreliable(1_000L))
        evaluator.accept(sample(8.8f, 2_000L))
        assertEquals(Cue.TOO_FAST, evaluator.accept(sample(8.9f, 3_000L)).cue)
    }

    @Test
    fun recoveredGpsCanConfirmReturnToGoodPace() {
        val evaluator = evaluatorAlreadyFast()
        evaluator.accept(unreliable(2_000L))

        assertEquals(Cue.GOOD_GPS, evaluator.accept(sample(8f, 3_000L)).cue)
        assertEquals(Cue.GOOD_PACE, evaluator.accept(sample(8f, 4_000L)).cue)
    }

    @Test
    fun gpsRecoveryDoesNotConsumeAnOverduePaceReminder() {
        val evaluator = evaluatorAlreadyFast()
        evaluator.accept(unreliable(6_000L))

        assertEquals(Cue.GOOD_GPS, evaluator.accept(sample(8.8f, 7_000L)).cue)
        assertEquals(Cue.TOO_FAST, evaluator.accept(sample(8.8f, 7_001L)).cue)
    }

    @Test
    fun immediateFastEntryAfterGpsRecoveryEmitsBothCuesAndTimesRepeatFromDelivery() {
        val evaluator = PaceEvaluator(config.copy(confirmationSamples = 1))
        evaluator.accept(unreliable(0L))

        val recovered = evaluator.accept(sample(8.8f, 1_000L))
        assertEquals(PaceState.TOO_FAST, recovered.state)
        assertEquals(Cue.GOOD_GPS, recovered.cue)
        assertEquals(Cue.TOO_FAST, evaluator.accept(sample(8.8f, 1_001L)).cue)
        assertNull(evaluator.accept(sample(8.8f, 6_000L)).cue)
        assertEquals(Cue.TOO_FAST, evaluator.accept(sample(8.8f, 6_001L)).cue)
    }

    @Test
    fun immediateGoodReturnAfterGpsRecoveryEmitsBothCuesOnce() {
        val evaluator = PaceEvaluator(config.copy(confirmationSamples = 1))
        evaluator.accept(sample(8.8f, 0L))
        evaluator.accept(unreliable(1_000L))

        val recovered = evaluator.accept(sample(8f, 2_000L))
        assertEquals(PaceState.GOOD, recovered.state)
        assertEquals(Cue.GOOD_GPS, recovered.cue)
        assertEquals(Cue.GOOD_PACE, evaluator.accept(sample(8f, 2_001L)).cue)
        assertNull(evaluator.accept(sample(8f, 2_002L)).cue)
    }

    @Test
    fun aNewWeakGpsEpisodeCuesAgain() {
        val evaluator = PaceEvaluator(config)
        evaluator.accept(unreliable(0L))
        evaluator.accept(sample(8f, 1_000L))
        evaluator.accept(sample(8f, 2_000L))

        assertEquals(Cue.BAD_GPS, evaluator.accept(unreliable(3_000L)).cue)
    }

    @Test
    fun weakGpsIsSilentWhenDisabled() {
        val evaluator = PaceEvaluator(config.copy(gpsCuesEnabled = false))

        assertNull(evaluator.accept(unreliable(0L)).cue)
        assertEquals(PaceState.GPS_WEAK, evaluator.current.state)
        assertNull(evaluator.accept(sample(8f, 1_000L)).cue)
    }

    @Test
    fun profileChangeDropsOldCandidatesRemindersAndQueuedCues() {
        val evaluator = PaceEvaluator(PaceConfig(4f, 6f, 2, 5_000L, true))

        evaluator.accept(sample(7f, 0L))
        evaluator.updateConfig(PaceConfig(8f, 9f, 2, 5_000L, true))

        assertEquals(PaceState.ACQUIRING_GPS, evaluator.current.state)
        assertNull(evaluator.accept(sample(7f, 1_000L)).cue)
        assertEquals(Cue.TOO_SLOW, evaluator.accept(sample(7f, 2_000L)).cue)
    }

    @Test
    fun profileChangeDropsPaceCueQueuedBehindGpsRecovery() {
        val evaluator = PaceEvaluator(PaceConfig(4f, 6f, 1, 5_000L, true))

        evaluator.accept(unreliable(0L))
        assertEquals(Cue.GOOD_GPS, evaluator.accept(sample(7f, 1_000L)).cue)

        evaluator.updateConfig(PaceConfig(8f, 9f, 1, 5_000L, true))

        assertNull(evaluator.accept(sample(8.5f, 2_000L)).cue)
    }

    @Test
    fun confirmingAPaceChangeEmitsPaceStateConfirmedEventWithFromAndToState() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val evaluator = PaceEvaluator(config, onEvent = events::add)

        evaluator.accept(sample(8.7f, 0L))
        evaluator.accept(sample(8.8f, 1_000L)) // confirms TOO_FAST, fromState null (first confirmation)

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.PaceCandidateStartedEvent(0L, PaceState.TOO_FAST),
                dev.fred.pacorun.eventlog.PaceStateConfirmedEvent(1_000L, null, PaceState.TOO_FAST, 8.8f, true),
            ),
            events,
        )
    }

    @Test
    fun goodPaceAfterConfirmedTooFastProducesAnAudibleCueMarkedTrue() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val evaluator = PaceEvaluator(config, onEvent = events::add)

        evaluator.accept(sample(8.7f, 0L))
        evaluator.accept(sample(8.8f, 1_000L)) // confirms TOO_FAST
        events.clear()
        evaluator.accept(sample(8f, 2_000L))
        evaluator.accept(sample(8f, 3_000L)) // confirms GOOD, previous == TOO_FAST -> audible

        val confirmed = events.filterIsInstance<dev.fred.pacorun.eventlog.PaceStateConfirmedEvent>().single()
        assertEquals(PaceState.TOO_FAST, confirmed.fromState)
        assertEquals(PaceState.GOOD, confirmed.toState)
        assertEquals(true, confirmed.producedAudibleCue)
    }

    @Test
    fun newCandidateDifferentFromConfirmedEmitsPaceCandidateStartedEvent() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val evaluator = PaceEvaluator(config, onEvent = events::add)

        evaluator.accept(sample(7.3f, 0L))

        assertEquals(listOf(dev.fred.pacorun.eventlog.PaceCandidateStartedEvent(0L, PaceState.TOO_SLOW)), events)
    }

    @Test
    fun sampleSnappingBackToConfirmedAbandonsInProgressCandidate() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val evaluator = PaceEvaluator(config, onEvent = events::add)

        // config has confirmationSamples = 2 (see the shared `config` fixture above) -- reaching
        // CONFIRMED needs two consecutive same-range samples, same as every other test in this file.
        evaluator.accept(sample(8f, 0L)) // candidate GOOD starts accumulating
        evaluator.accept(sample(8f, 1_000L)) // confirms GOOD
        events.clear()
        evaluator.accept(sample(8.7f, 2_000L)) // candidate TOO_FAST starts accumulating
        evaluator.accept(sample(8f, 3_000L)) // snaps back to already-confirmed GOOD before TOO_FAST confirms

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.PaceCandidateStartedEvent(2_000L, PaceState.TOO_FAST),
                dev.fred.pacorun.eventlog.PaceCandidateAbandonedEvent(
                    3_000L,
                    PaceState.TOO_FAST,
                    1,
                    dev.fred.pacorun.eventlog.PaceCandidateAbandonReason.SNAPPED_BACK_TO_CONFIRMED,
                ),
            ),
            events,
        )
    }

    @Test
    fun candidateDirectlySupersededByADifferentCandidateEmitsAbandonedWithSupersededReason() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val evaluator = PaceEvaluator(config, onEvent = events::add)

        // No confirmation, no snap-back, no unreliable sample in between -- the in-progress
        // TOO_SLOW candidate is replaced directly by a TOO_FAST one before ever confirming.
        evaluator.accept(sample(7.3f, 0L)) // candidate TOO_SLOW starts accumulating
        evaluator.accept(sample(8.7f, 1_000L)) // TOO_FAST supersedes the TOO_SLOW candidate directly

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.PaceCandidateStartedEvent(0L, PaceState.TOO_SLOW),
                dev.fred.pacorun.eventlog.PaceCandidateAbandonedEvent(
                    1_000L,
                    PaceState.TOO_SLOW,
                    1,
                    dev.fred.pacorun.eventlog.PaceCandidateAbandonReason.SUPERSEDED_BY_NEW_CANDIDATE,
                ),
                dev.fred.pacorun.eventlog.PaceCandidateStartedEvent(1_000L, PaceState.TOO_FAST),
            ),
            events,
        )
    }

    @Test
    fun gpsBecomingUnreliableAbandonsInProgressCandidate() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val evaluator = PaceEvaluator(config, onEvent = events::add)

        evaluator.accept(sample(8.7f, 0L)) // candidate TOO_FAST starts accumulating
        events.clear()
        evaluator.accept(unreliable(1_000L))

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.PaceCandidateAbandonedEvent(
                    1_000L,
                    PaceState.TOO_FAST,
                    1,
                    dev.fred.pacorun.eventlog.PaceCandidateAbandonReason.GPS_BECAME_UNRELIABLE,
                ),
            ),
            events,
        )
    }

    @Test
    fun phaseChangeResetDoesNotEmitAnAbandonEvent() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val evaluator = PaceEvaluator(config, onEvent = events::add)

        evaluator.accept(sample(8.7f, 0L)) // candidate TOO_FAST starts accumulating
        events.clear()
        evaluator.updateConfig(config) // phase-change reset -- already covered by RoutinePhaseChangedEvent, not an abandon

        assertEquals(emptyList<dev.fred.pacorun.eventlog.SessionEvent>(), events)
    }

    private fun evaluatorAlreadyFast() = PaceEvaluator(config).also {
        it.accept(sample(8.7f, 0L))
        it.accept(sample(8.8f, 1_000L))
    }

    private fun sample(speedKmh: Float, elapsedRealtimeMillis: Long) = LocationSample(
        speedKmh = speedKmh,
        speedAccuracyKmh = .2f,
        reliable = true,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
    )

    private fun unreliable(elapsedRealtimeMillis: Long) = LocationSample(
        speedKmh = 0f,
        speedAccuracyKmh = null,
        reliable = false,
        elapsedRealtimeMillis = elapsedRealtimeMillis,
    )
}
