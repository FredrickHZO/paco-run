package dev.fred.pacorun.pace

import dev.fred.pacorun.location.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
                // rawSpeedKmh defaults to sample.speedKmh when the caller (this test) doesn't pass
                // it explicitly -- both fields equal 8.8f here, same as pre-P2 behavior.
                dev.fred.pacorun.eventlog.PaceStateConfirmedEvent(1_000L, null, PaceState.TOO_FAST, 8.8f, true, rawSpeedKmh = 8.8f),
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
        assertEquals(8f, confirmed.rawSpeedKmh)
    }

    @Test
    fun rawSpeedKmhParameterFlowsToEventPayloadWithoutAffectingDecisionLogic() {
        val events = mutableListOf<dev.fred.pacorun.eventlog.SessionEvent>()
        val evaluator = PaceEvaluator(config, onEvent = events::add)

        // sample.speedKmh is the (here: stand-in "smoothed") value PaceEvaluator decides on --
        // 8.7/8.8, both TOO_FAST. rawSpeedKmh is deliberately a different reading (9.1/9.4) to
        // prove decision logic ignores it entirely, only the event payload sees it.
        evaluator.accept(sample(8.7f, 0L), rawSpeedKmh = 9.1f)
        val result = evaluator.accept(sample(8.8f, 1_000L), rawSpeedKmh = 9.4f)

        assertEquals(PaceState.TOO_FAST, result.state) // decided from sample.speedKmh=8.8, not rawSpeedKmh
        val confirmed = events.filterIsInstance<dev.fred.pacorun.eventlog.PaceStateConfirmedEvent>().single()
        assertEquals(8.8f, confirmed.speedKmh) // decision input, unchanged behavior
        assertEquals(9.4f, confirmed.rawSpeedKmh) // the raw value, threaded through for the payload only
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

    @Test
    fun emaSmoothingSubstantiallyReducesDirectFlipsOnTheNarrowBandWorstCaseOscillation() {
        val config = PaceConfig(minKmh = 7.5f, maxKmh = 8.2f, confirmationSamples = 2, repeatMillis = 5_000L, gpsCuesEnabled = false)
        val trajectory = narrowBandOscillation(periodSeconds = 7.0, sampleCount = 90)

        val baselineFlips = confirmedDirectFlips(PaceEvaluator(config), trajectory, smoother = null)
        val smoothedFlips = confirmedDirectFlips(PaceEvaluator(config), trajectory, smoother = dev.fred.pacorun.service.EmaPaceSmoother(timeConstantSeconds = 6.0))

        // The baseline must actually reproduce repeated flapping on these parameters, or the
        // synthetic trajectory isn't representative of the real worst case (design doc Decision
        // 8.1's own instruction: if it doesn't flap, revise the parameters before drawing any
        // conclusion about tau). If this fails, adjust periodSeconds within the design doc's
        // specified 6-8s range before touching anything else.
        assertTrue("baseline should flap repeatedly on a 0.7 km/h band, got $baselineFlips", baselineFlips >= 5)
        // EMA at tau=6.0s must cut that flapping substantially, not just marginally.
        assertTrue(
            "smoothing should cut direct flips by at least half, got $baselineFlips -> $smoothedFlips",
            smoothedFlips <= baselineFlips / 2,
        )
    }

    @Test
    fun emaSmoothingConfirmsASustainedEffortChangeWithinABoundedLagBeyondTheExistingDebounce() {
        // Not an oscillation -- a genuine, sustained effort change: TOO_SLOW held steady for 20s,
        // then a real step up into the GOOD band, held steady. Decision 8.1's second criterion:
        // smoothing must not turn responsiveness into practical unresponsiveness.
        val config = PaceConfig(minKmh = 7.5f, maxKmh = 8.2f, confirmationSamples = 2, repeatMillis = 5_000L, gpsCuesEnabled = false)
        val evaluator = PaceEvaluator(config)
        val smoother = dev.fred.pacorun.service.EmaPaceSmoother(timeConstantSeconds = 6.0)

        var elapsedMillis = 0L
        repeat(20) {
            val decisionSpeed = smoother.accept(7.0f, elapsedMillis)
            evaluator.accept(sample(decisionSpeed, elapsedMillis), rawSpeedKmh = 7.0f)
            elapsedMillis += 1_000L
        }
        val stepElapsedMillis = elapsedMillis
        var confirmedAtMillis: Long? = null
        repeat(30) {
            val decisionSpeed = smoother.accept(7.85f, elapsedMillis)
            val result = evaluator.accept(sample(decisionSpeed, elapsedMillis), rawSpeedKmh = 7.85f)
            if (confirmedAtMillis == null && result.state == PaceState.GOOD) confirmedAtMillis = elapsedMillis
            elapsedMillis += 1_000L
        }

        val lagMillis = requireNotNull(confirmedAtMillis) { "GOOD was never confirmed after the step" } - stepElapsedMillis
        // Explicit bound (a plan-level refinement, the design doc left the exact number
        // unspecified): comfortably above the theoretical ~7s (5.3s for the EMA to re-enter the
        // band + up to 2s for the confirmationSamples debounce) while still ruling out "tens of
        // seconds" unresponsiveness.
        assertTrue("lag should stay bounded, was ${lagMillis}ms", lagMillis <= 15_000L)
    }

    /**
     * Reproduces the 2026-09-13 narrow-band run's approximate shape (see design doc Decision
     * 8.1): 7.19-9.38 km/h peak-to-peak oscillation (the one concrete per-second extract that
     * run's own validation doc reports), around a [periodSeconds] cycle, sampled at 1Hz -- not a
     * replay of real per-tick data (none exists for that run; P1 didn't exist yet).
     */
    private fun narrowBandOscillation(periodSeconds: Double, sampleCount: Int): List<Pair<Float, Long>> {
        val center = 8.285 // midpoint of the 7.19-9.38 km/h peak-to-peak extract
        val amplitude = 1.095 // (9.38 - 7.19) / 2
        return (0 until sampleCount).map { i ->
            val tSeconds = i.toDouble()
            val speedKmh = center + amplitude * kotlin.math.sin(2.0 * Math.PI * tSeconds / periodSeconds)
            speedKmh.toFloat() to (tSeconds * 1_000.0).toLong()
        }
    }

    private fun confirmedDirectFlips(
        evaluator: PaceEvaluator,
        trajectory: List<Pair<Float, Long>>,
        smoother: dev.fred.pacorun.service.SessionPaceSmoothingEngine?,
    ): Int {
        var previousState: PaceState? = null
        var directFlips = 0
        trajectory.forEach { (rawSpeedKmh, elapsedMillis) ->
            val decisionSpeed = smoother?.accept(rawSpeedKmh, elapsedMillis) ?: rawSpeedKmh
            val state = evaluator.accept(sample(decisionSpeed, elapsedMillis), rawSpeedKmh).state
            if (previousState != null && previousState != state &&
                (previousState == PaceState.TOO_SLOW && state == PaceState.TOO_FAST ||
                    previousState == PaceState.TOO_FAST && state == PaceState.TOO_SLOW)
            ) {
                directFlips++
            }
            previousState = state
        }
        return directFlips
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
