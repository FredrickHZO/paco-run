package dev.fred.pacorun.service

import dev.fred.pacorun.audio.AudioCue
import dev.fred.pacorun.location.LocationSample
import dev.fred.pacorun.pace.Cue
import dev.fred.pacorun.pace.PaceConfig
import dev.fred.pacorun.pace.PaceResult
import dev.fred.pacorun.pace.PaceState
import dev.fred.pacorun.routine.RoutineCue
import dev.fred.pacorun.routine.RoutineInput
import dev.fred.pacorun.routine.RoutinePhase
import dev.fred.pacorun.routine.RoutineResult
import dev.fred.pacorun.settings.RoutineMode
import dev.fred.pacorun.settings.RunPreferences
import dev.fred.pacorun.settings.SpeedRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCoordinatorTest {
    @Test
    fun profileResetPrecedesPaceEvaluationAndRoutineCuesPrecedePaceCues() {
        val events = mutableListOf<String>()
        val routine = FakeRoutineEngine(events, profileChanged = true)
        val pace = FakePaceEvaluator(events, PaceResult(PaceState.TOO_FAST, 9f, Cue.TOO_FAST))
        val published = mutableListOf<RunSessionState>()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = routine,
            paceEvaluator = pace,
            onCues = { cues, _ -> events += "cues:${cues.joinToString()}" },
            publish = published::add,
            speedEngine = DopplerSpeedEngine(),
        )

        coordinator.tick(
            elapsedRealtimeMillis = 0L,
            sample = reliableSample(9f, 0L),
            position = LocationPosition(0.0, 0.0, 0L),
        )
        events.clear()
        coordinator.tick(
            elapsedRealtimeMillis = 1_000L,
            sample = reliableSample(9f, 1_000L),
            // 0.0001 degrees longitude at the equator (~11.13m): a real movement, comfortably
            // clear of DistanceAccumulator's jitter floor (unlike the previous 0.00001/1.11m,
            // which the jitter filter now correctly suppresses as noise).
            position = LocationPosition(0.0, .0001, 1_000L),
        )

        assertEquals(listOf("routine", "reset", "pace", "cues:ROUTINE_START, TOO_FAST"), events)
        assertEquals(11.13, routine.lastInput.reliableDistanceDeltaMeters, .02)
        val state = published.last() as RunSessionState.Running
        assertEquals(RoutinePhase.RUN, state.phase)
        assertEquals(9f, state.speedKmh)
        assertEquals(4_000L, state.intervalRemainingMillis)
        assertEquals(3.0, state.totalDistanceMeters, .001)
        assertEquals(2.0, state.routineDistanceMeters, .001)
    }

    @Test
    fun weakGpsStillAdvancesRoutineAndIsPassedToPaceWithoutDistance() {
        val events = mutableListOf<String>()
        val routine = FakeRoutineEngine(events, profileChanged = false)
        val pace = FakePaceEvaluator(events, PaceResult(PaceState.GPS_WEAK, null, Cue.BAD_GPS))
        val published = mutableListOf<RunSessionState>()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = routine,
            paceEvaluator = pace,
            onCues = { cues, _ -> events += "cues:${cues.joinToString()}" },
            publish = published::add,
            speedEngine = DopplerSpeedEngine(),
        )

        coordinator.tick(1_000L, unreliableSample(1_000L), LocationPosition(0.0, .00001, 1_000L))

        assertEquals(listOf("routine", "pace", "cues:ROUTINE_START, BAD_GPS"), events)
        assertFalse(routine.lastInput.gpsReliable)
        assertEquals(0.0, routine.lastInput.reliableDistanceDeltaMeters, .001)
        assertFalse(pace.samples.single().reliable)
        val state = published.single() as RunSessionState.Running
        assertFalse(state.gpsReliable)
        assertEquals(null, state.speedKmh)
    }

    @Test
    fun timerBeforeEveryLocationKeepsAnchorAndProcessesEachLocationOnce() {
        val events = mutableListOf<String>()
        val routine = FakeRoutineEngine(events, profileChanged = false)
        val pace = FakePaceEvaluator(events, PaceResult(PaceState.GOOD, 8f, null))
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = routine,
            paceEvaluator = pace,
            onCues = { _, _ -> },
            publish = {},
            speedEngine = DopplerSpeedEngine(),
        )

        coordinator.tick(0L, sample = null, position = null)
        coordinator.tick(0L, reliableSample(8f, 0L), LocationPosition(0.0, 0.0, 0L))
        coordinator.tick(1_000L, sample = null, position = null)
        coordinator.tick(1_000L, reliableSample(8f, 1_000L), LocationPosition(0.0, .0001, 1_000L))

        assertEquals(11.13, routine.lastInput.reliableDistanceDeltaMeters, .02)
        assertEquals(4, routine.inputs.size)
        assertEquals(2, pace.samples.size)
        assertTrue(pace.samples[0].reliable)
        assertTrue(pace.samples[1].reliable)
    }

    @Test
    fun timerBeforeEveryLocationDoesNotBreakPaceConfirmation() {
        val cues = mutableListOf<AudioCue>()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(confirmationSamples = 2),
            routineEngine = FakeRoutineEngine(mutableListOf(), profileChanged = false),
            paceEvaluator = PaceEvaluatorAdapter(
                dev.fred.pacorun.pace.PaceEvaluator(PaceConfig(7f, 8f, 2, 5_000L, false)),
            ),
            onCues = { values, _ -> cues += values },
            publish = {},
            speedEngine = DopplerSpeedEngine(),
        )

        coordinator.tick(0L, reliableSample(9f, 0L), LocationPosition(0.0, 0.0, 0L))
        coordinator.tick(1_000L, sample = null, position = null)
        coordinator.tick(1_000L, reliableSample(9f, 1_000L), LocationPosition(0.0, .00001, 1_000L))

        assertTrue(AudioCue.TOO_FAST in cues)
    }

    @Test
    fun timerFirstLocationInSameSecondKeepsDistanceAndPaceWithoutAnotherTimingAdvance() {
        val routine = FakeRoutineEngine(mutableListOf(), profileChanged = false)
        val pace = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null))
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = routine,
            paceEvaluator = pace,
            onCues = { _, _ -> },
            publish = {},
            speedEngine = DopplerSpeedEngine(),
        )

        coordinator.tick(0L, reliableSample(8f, 0L), LocationPosition(0.0, 0.0, 0L))
        coordinator.advanceTimer(1_000L, signalGpsOutage = false)
        coordinator.tick(1_900L, reliableSample(8f, 1_900L), LocationPosition(0.0, .0001, 1_900L))

        assertEquals(listOf(0L, 1_000L, 1_000L), routine.inputs.map { it.elapsedRealtimeMillis })
        assertEquals(11.13, routine.lastInput.reliableDistanceDeltaMeters, .02)
        assertEquals(2, pace.samples.size)
        assertEquals(1_900L, pace.samples.last().elapsedRealtimeMillis)
    }

    @Test
    fun weakGpsCueAndRecoveryClearPaceCandidates() {
        val cues = mutableListOf<AudioCue>()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(confirmationSamples = 2, gpsCuesEnabled = true),
            routineEngine = FakeRoutineEngine(mutableListOf(), profileChanged = false),
            paceEvaluator = PaceEvaluatorAdapter(
                dev.fred.pacorun.pace.PaceEvaluator(PaceConfig(7f, 8f, 2, 5_000L, true)),
            ),
            onCues = { values, _ -> cues += values },
            publish = {},
            speedEngine = DopplerSpeedEngine(),
        )

        coordinator.tick(0L, reliableSample(9f, 0L), LocationPosition(0.0, 0.0, 0L))
        coordinator.tick(1_000L, unreliableSample(1_000L), LocationPosition(0.0, .00001, 1_000L))
        coordinator.tick(2_000L, reliableSample(9f, 2_000L), LocationPosition(0.0, .00002, 2_000L))
        coordinator.tick(3_000L, reliableSample(9f, 3_000L), LocationPosition(0.0, .00003, 3_000L))

        assertEquals(listOf(AudioCue.BAD_GPS, AudioCue.GOOD_GPS, AudioCue.TOO_FAST), cues.filter {
            it == AudioCue.BAD_GPS || it == AudioCue.GOOD_GPS || it == AudioCue.TOO_FAST
        })
    }

    @Test
    fun firstTimerOutageBreaksAnchorSoRecoveryDoesNotBridgeDistance() {
        val routine = FakeRoutineEngine(mutableListOf(), profileChanged = false)
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = routine,
            paceEvaluator = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null)),
            onCues = { _, _ -> },
            publish = {},
            speedEngine = DopplerSpeedEngine(),
        )

        coordinator.tick(0L, reliableSample(8f, 0L), LocationPosition(0.0, 0.0, 0L))
        coordinator.advanceTimer(4_000L, signalGpsOutage = true)
        coordinator.tick(5_000L, reliableSample(8f, 5_000L), LocationPosition(0.0, .00001, 5_000L))

        assertEquals(0.0, routine.lastInput.reliableDistanceDeltaMeters, .001)
    }

    @Test
    fun healthyTimerRetainsReliableSpeedForStateAndPhaseDirectionCue() {
        val cues = mutableListOf<AudioCue>()
        val published = mutableListOf<RunSessionState>()
        val preferences = RunPreferences(
            mode = RoutineMode.WALK_RUN,
            constantRange = SpeedRange(7f, 8f),
            runRange = SpeedRange(9f, 10f),
            walkUsesConstantRange = false,
            walkRange = SpeedRange(4f, 5f),
            runSeconds = 1,
            walkSeconds = 60,
        )
        val coordinator = SessionCoordinator(
            preferences = preferences,
            routineEngine = RoutineEngineAdapter(dev.fred.pacorun.routine.RoutineEngine(preferences)),
            paceEvaluator = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 11f, null)),
            onCues = { values, _ -> cues += values },
            publish = published::add,
            speedEngine = DopplerSpeedEngine(),
        )

        coordinator.tick(0L, reliableSample(11f, 0L), LocationPosition(0.0, 0.0, 0L))
        cues.clear()
        coordinator.advanceTimer(1_000L, signalGpsOutage = false)

        assertEquals(listOf(AudioCue.WALK_ROUTINE, AudioCue.SLOW_DOWN), cues)
        val state = published.last() as RunSessionState.Running
        assertTrue(state.gpsReliable)
        assertEquals(11f, state.speedKmh)
        assertEquals(SpeedRange(4f, 5f), state.activeRange)
    }

    @Test
    fun profileChangesDuringOneOutageEmitOneBadGpsThenOneGoodGpsOnRecovery() {
        val cues = mutableListOf<AudioCue>()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(gpsCuesEnabled = true),
            routineEngine = FakeRoutineEngine(mutableListOf(), profileChanged = true),
            paceEvaluator = PaceEvaluatorAdapter(
                dev.fred.pacorun.pace.PaceEvaluator(PaceConfig(7f, 8f, 2, 5_000L, true)),
            ),
            onCues = { values, _ -> cues += values },
            publish = {},
            speedEngine = DopplerSpeedEngine(),
        )

        coordinator.advanceTimer(0L, signalGpsOutage = true)
        coordinator.advanceTimer(1_000L, signalGpsOutage = true)
        coordinator.advanceTimer(2_000L, signalGpsOutage = true)
        coordinator.tick(3_000L, reliableSample(8f, 3_000L), LocationPosition(0.0, 0.0, 3_000L))

        assertEquals(listOf(AudioCue.BAD_GPS, AudioCue.GOOD_GPS), cues.filter {
            it == AudioCue.BAD_GPS || it == AudioCue.GOOD_GPS
        })
    }

    private fun reliableSample(speedKmh: Float, time: Long) = LocationSample(speedKmh, .2f, true, time)
    private fun unreliableSample(time: Long) = LocationSample(0f, null, false, time)

    @Test
    fun speedEngineSyncsWithDistanceEngineOnReliableFixesAndOutage() {
        val speedEngine = FakeSpeedEngine()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = FakeRoutineEngine(mutableListOf(), profileChanged = false),
            paceEvaluator = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null)),
            onCues = { _, _ -> },
            publish = {},
            speedEngine = speedEngine,
        )

        coordinator.tick(0L, reliableSample(8f, 0L), LocationPosition(0.0, 0.0, 0L))
        coordinator.tick(1_000L, reliableSample(8f, 1_000L), LocationPosition(0.0, .0001, 1_000L))
        assertEquals(2, speedEngine.acceptCalls)
        assertEquals(0, speedEngine.breakChainCalls)

        coordinator.advanceTimer(5_000L, signalGpsOutage = true)
        assertEquals(1, speedEngine.breakChainCalls)
    }

    @Test
    fun timerRetainsFreshGpsUsesSpeedEngineOutputNotRawSample() {
        val speedEngine = FakeSpeedEngine(fixedOutput = 42.0)
        val published = mutableListOf<RunSessionState>()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = FakeRoutineEngine(mutableListOf(), profileChanged = false),
            paceEvaluator = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null)),
            onCues = { _, _ -> },
            publish = published::add,
            speedEngine = speedEngine,
        )

        coordinator.tick(0L, reliableSample(9f, 0L), LocationPosition(0.0, 0.0, 0L))
        coordinator.advanceTimer(1_000L, signalGpsOutage = false)

        val state = published.last() as RunSessionState.Running
        assertTrue(state.gpsReliable)
        // Without the fix, this would read 9f (the raw sample's speedKmh, cached in
        // latestReliableSample before the substitution) instead of 42f (the engine's output).
        assertEquals(42f, state.speedKmh)
    }

    @Test
    fun onSpeedComputedFiresOnlyOnActualReliableFixesWithEngineValue() {
        val computed = mutableListOf<Double?>()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = FakeRoutineEngine(mutableListOf(), profileChanged = false),
            paceEvaluator = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null)),
            onCues = { _, _ -> },
            publish = {},
            speedEngine = FakeSpeedEngine(fixedOutput = 7.0),
            onSpeedComputed = { computed += it },
        )

        coordinator.tick(0L, reliableSample(9f, 0L), LocationPosition(0.0, 0.0, 0L))
        coordinator.advanceTimer(1_000L, signalGpsOutage = false) // grace window: must NOT fire
        coordinator.advanceTimer(5_000L, signalGpsOutage = true) // outage: must NOT fire

        assertEquals(listOf(7.0), computed)
    }

    @Test
    fun timerAndRoutineDoNotAdvanceBeforeFirstReliableFix() {
        val routine = FakeRoutineEngine(mutableListOf(), profileChanged = false)
        val published = mutableListOf<RunSessionState>()
        val pending = PendingSessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = routine,
            paceEvaluator = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null)),
            onCues = { _, _ -> },
            publish = published::add,
            speedEngine = DopplerSpeedEngine(),
        )

        pending.advanceTimer(10_000L, signalGpsOutage = true)

        assertTrue(published.isEmpty())
        assertTrue(routine.inputs.isEmpty())
        assertFalse(pending.isActivated)
    }

    @Test
    fun unreliableSampleWhileAcquiringDoesNotActivate() {
        val routine = FakeRoutineEngine(mutableListOf(), profileChanged = false)
        val published = mutableListOf<RunSessionState>()
        val pending = PendingSessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = routine,
            paceEvaluator = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GPS_WEAK, null, null)),
            onCues = { _, _ -> },
            publish = published::add,
            speedEngine = DopplerSpeedEngine(),
        )

        pending.tick(1_000L, unreliableSample(1_000L), LocationPosition(0.0, 0.0, 1_000L))

        assertTrue(published.isEmpty())
        assertTrue(routine.inputs.isEmpty())
        assertFalse(pending.isActivated)
    }

    @Test
    fun firstReliableFixActivatesAndStartsAtZeroWithoutAddingDistance() {
        val published = mutableListOf<RunSessionState>()
        val preferences = RunPreferences()
        val pending = PendingSessionCoordinator(
            preferences = preferences,
            routineEngine = RoutineEngineAdapter(dev.fred.pacorun.routine.RoutineEngine(preferences)),
            paceEvaluator = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null)),
            onCues = { _, _ -> },
            publish = published::add,
            speedEngine = DopplerSpeedEngine(),
        )

        pending.tick(12_000L, reliableSample(8f, 12_000L), LocationPosition(0.0, 0.0, 12_000L))

        assertTrue(pending.isActivated)
        val running = published.last() as RunSessionState.Running
        assertEquals(0L, running.elapsedMillis)
        assertEquals(0.0, running.totalDistanceMeters, 0.0)
    }

    @Test
    fun onceActivatedLaterTicksDelegateToTheSameCoordinator() {
        val routine = FakeRoutineEngine(mutableListOf(), profileChanged = false)
        val pending = PendingSessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = routine,
            paceEvaluator = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null)),
            onCues = { _, _ -> },
            publish = {},
            speedEngine = DopplerSpeedEngine(),
        )

        pending.tick(0L, reliableSample(8f, 0L), LocationPosition(0.0, 0.0, 0L))
        pending.advanceTimer(1_000L, signalGpsOutage = false)

        assertTrue(pending.isActivated)
        assertEquals(2, routine.inputs.size)
    }

    @Test
    fun isolatedUnreliableFixDoesNotResetKalmanSpeedEngineOrCauseSpuriousPaceCue() {
        val preferences = RunPreferences(confirmationSamples = 1)
        val cues = mutableListOf<AudioCue>()
        val coordinator = SessionCoordinator(
            preferences = preferences,
            routineEngine = FakeRoutineEngine(mutableListOf(), profileChanged = false),
            paceEvaluator = PaceEvaluatorAdapter(
                dev.fred.pacorun.pace.PaceEvaluator(PaceConfig(7f, 9f, 1, 5_000L, false)),
            ),
            onCues = { values, _ -> cues += values },
            publish = {},
            // Real KalmanSpeedEngine, not a fake -- this is exactly what Task 4's fakes missed.
        )
        // Metro fisso a ~0.0001 gradi di longitudine all'equatore per ogni secondo: circa
        // 11.13 m/s... troppo veloce. Per un passo realistico a 8 km/h serve uno spostamento di
        // (8/3.6) m/s = 2.222 m/s, cioè 2.222/111_320*cos(0) gradi di longitudine per secondo.
        val degreesPerSecondAt8Kmh = (8.0 / 3.6) / 111_320.0
        var longitude = 0.0
        var time = 0L

        // Alcuni fix reali a ritmo costante, per lasciare al Kalman il tempo di convergere.
        repeat(15) {
            longitude += degreesPerSecondAt8Kmh
            time += 1_000L
            coordinator.tick(time, reliableSample(8f, time), LocationPosition(0.0, longitude, time))
        }
        cues.clear()

        // Un singolo fix isolato giudicato inaffidabile -- non un'interruzione prolungata.
        // longitude avanza comunque di un secondo pieno: il runner si è mosso davvero, solo il
        // GPS non ce l'ha detto in modo affidabile per quell'istante (la sua posizione qui non
        // viene comunque mai letta da nessun motore, dato che il fix è marcato inaffidabile).
        longitude += degreesPerSecondAt8Kmh
        time += 1_000L
        coordinator.tick(time, unreliableSample(time), LocationPosition(0.0, longitude, time))

        // Il prossimo fix riprende esattamente lo stesso ritmo di prima -- il gap dall'ultimo
        // fix REALMENTE usato è di 2 secondi, e riflette correttamente 2 secondi di
        // spostamento vero a 8 km/h (non un dimezzamento fittizio della velocità media).
        longitude += degreesPerSecondAt8Kmh
        time += 1_000L
        coordinator.tick(time, reliableSample(8f, time), LocationPosition(0.0, longitude, time))

        // Prima del fix: il motore si resetta, riparte da una velocità vicina a 0, e
        // PaceEvaluator emette TOO_SLOW pur non essendoci stato alcun cambio di ritmo reale.
        assertTrue(
            "spurious pace cue after an isolated unreliable fix, no real pace change occurred: $cues",
            AudioCue.TOO_SLOW !in cues,
        )
    }

    @Test
    fun isolatedUnreliableFixDoesNotBreakSpeedEngineChain() {
        val speedEngine = FakeSpeedEngine()
        val coordinator = SessionCoordinator(
            preferences = RunPreferences(),
            routineEngine = FakeRoutineEngine(mutableListOf(), profileChanged = false),
            paceEvaluator = FakePaceEvaluator(mutableListOf(), PaceResult(PaceState.GOOD, 8f, null)),
            onCues = { _, _ -> },
            publish = {},
            speedEngine = speedEngine,
        )

        coordinator.tick(0L, reliableSample(8f, 0L), LocationPosition(0.0, 0.0, 0L))
        coordinator.tick(1_000L, unreliableSample(1_000L), LocationPosition(0.0, .0001, 1_000L))

        assertEquals(0, speedEngine.breakChainCalls)
    }

    private class FakeRoutineEngine(
        private val events: MutableList<String>,
        private val profileChanged: Boolean,
    ) : SessionRoutineEngine {
        lateinit var lastInput: RoutineInput
        val inputs = mutableListOf<RoutineInput>()

        override fun accept(input: RoutineInput): RoutineResult {
            events += "routine"
            lastInput = input
            inputs += input
            return RoutineResult(
                phase = RoutinePhase.RUN,
                activeRange = SpeedRange(7f, 8f),
                intervalRemainingMillis = 4_000L,
                totalDistanceMeters = 3.0,
                routineDistanceMeters = 2.0,
                cues = listOf(RoutineCue.ROUTINE_START),
                profileChanged = profileChanged,
            )
        }
    }

    private class FakePaceEvaluator(
        private val events: MutableList<String>,
        private val result: PaceResult,
    ) : SessionPaceEvaluator {
        val samples = mutableListOf<LocationSample>()
        override fun updateConfig(config: PaceConfig) {
            events += "reset"
        }

        override fun accept(sample: LocationSample): PaceResult {
            events += "pace"
            samples += sample
            return result
        }
    }

    private class FakeSpeedEngine(private val fixedOutput: Double = 0.0) : SessionSpeedEngine {
        var acceptCalls = 0
            private set
        var breakChainCalls = 0
            private set

        override fun accept(sample: LocationSample, position: LocationPosition, reliable: Boolean): Double {
            acceptCalls++
            return fixedOutput
        }

        override fun breakChain(): Double {
            breakChainCalls++
            return 0.0
        }
    }
}
