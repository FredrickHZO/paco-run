package dev.fred.pacorun.service

import dev.fred.pacorun.eventlog.AudioCueEmittedEvent
import dev.fred.pacorun.eventlog.DeviceHealthSampledEvent
import dev.fred.pacorun.eventlog.DistanceChainBreakReason
import dev.fred.pacorun.eventlog.DistanceChainBrokenEvent
import dev.fred.pacorun.eventlog.GpsSilentGapEvent
import dev.fred.pacorun.eventlog.LocationFixEvent
import dev.fred.pacorun.eventlog.LocationReliability
import dev.fred.pacorun.eventlog.PaceCandidateStartedEvent
import dev.fred.pacorun.eventlog.QualityTier
import dev.fred.pacorun.eventlog.RejectionReason
import dev.fred.pacorun.eventlog.RoutinePhaseChangedEvent
import dev.fred.pacorun.eventlog.SpeedChainBreakReason
import dev.fred.pacorun.eventlog.SpeedChainBrokenEvent
import dev.fred.pacorun.eventlog.ThermalStatus
import dev.fred.pacorun.location.LocationSample
import dev.fred.pacorun.settings.RoutineMode
import dev.fred.pacorun.settings.RunPreferences
import dev.fred.pacorun.settings.SpeedRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSessionEventSequencerTest {

    private fun newSequencer(
        preferences: RunPreferences = RunPreferences(),
        startedAt: Long = 0L,
    ) = RunSessionEventSequencer(
        preferences = preferences,
        onCues = {},
        publish = {},
        sessionStartedAtElapsedMillis = startedAt,
    )

    private fun reliableSample(now: Long) =
        LocationSample(speedKmh = 10f, speedAccuracyKmh = .3f, reliable = true, elapsedRealtimeMillis = now)

    private fun position(now: Long, lat: Double = 45.0, lon: Double = 9.0) =
        LocationPosition(latitude = lat, longitude = lon, elapsedRealtimeMillis = now, horizontalAccuracyMeters = 5f)

    private fun invalidPosition(now: Long) =
        LocationPosition(latitude = Double.NaN, longitude = 9.0, elapsedRealtimeMillis = now, horizontalAccuracyMeters = 5f)

    private fun reliableReliability() = LocationReliability(reliable = true, reasons = emptyList())
    private fun unreliableReliability() =
        LocationReliability(reliable = false, reasons = listOf(RejectionReason.MISSING_OR_INVALID_SPEED))

    // Caso 2: ordinamento causale -- LocationFixEvent (costruito dal Sequencer stesso, stato
    // post-tick) deve comparire PRIMA delle sue conseguenze (eventi emessi dai motori interni
    // durante la stessa chiamata a coordinator.tick()), non dopo.
    @Test
    fun `LocationFixEvent comes before the events its own tick caused`() {
        val sequencer = newSequencer()
        sequencer.onLocationFix(reliableSample(0L), position(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L)
        // Salto di 1 grado di latitudine in 1s (~111km) supera il maxSpeedMetersPerSecond=15.0
        // di default di DistanceAccumulator -> IMPLAUSIBLE_JUMP, emesso DENTRO coordinator.tick().
        val result = sequencer.onLocationFix(
            reliableSample(1_000L), position(1_000L, lat = 46.0), null, "gps", reliableReliability(), QualityTier.OTTIMA, 1_000L,
        )
        val fixIndex = result.events.indexOfFirst { it is LocationFixEvent }
        val consequenceIndex = result.events.indexOfFirst { it is DistanceChainBrokenEvent }
        assertTrue("both events must be present", fixIndex >= 0 && consequenceIndex >= 0)
        assertTrue("LocationFixEvent must precede its own consequences", fixIndex < consequenceIndex)
    }

    // Caso 3(a): un'interruzione GPS rilevata SOLO da onTimerTick, indipendentemente da qualunque
    // fix NEL FRATTEMPO (non "nessun fix mai" -- PendingSessionCoordinator.advanceTimer() è un
    // no-op finché activeCoordinator è null, e activeCoordinator si crea SOLO dentro tick() al
    // primo fix affidabile, RunSessionStore.kt:427-440 -- serve quindi un primo fix per attivare
    // il coordinator, poi il gap di tempo puramente temporale che segue deve bastare a far
    // scattare l'interruzione).
    @Test
    fun `onTimerTick alone detects a GPS outage after a prior fix, with no further fix in between`() {
        val sequencer = newSequencer(preferences = RunPreferences(mode = RoutineMode.CONSTANT))
        sequencer.onLocationFix(reliableSample(0L), position(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L)
        // GpsFreshnessTracker.isOutage(now) confronta now - <ultimo fix affidabile> contro
        // DEFAULT_MAX_AGE_MILLIS (3_000ms) -- 4_000ms dopo l'unico fix supera la soglia, e questo
        // tick non ha mai chiamato onLocationFix di nuovo.
        val result = sequencer.onTimerTick(4_000L)
        assertTrue(result.events.any { it is DistanceChainBrokenEvent && it.reason == DistanceChainBreakReason.GPS_OUTAGE_ENTERED })
    }

    // Caso 3(b): un tick puramente temporale (nessun fix GPS in QUEL tick) che fa scattare un cue
    // di routine produce un AudioCueEmittedEvent. Stessa nota del caso 3(a): serve un primo fix
    // per attivare il coordinator (in modalità WALK_RUN con initialConstantKm=0.01f, non 0f, così
    // quel primo fix da solo NON fa scattare startRun()/ROUTINE_START -- resta in
    // CONSTANT_BEFORE, perché il primo fix non ha ancora un anchor precedente e quindi contribuisce
    // sempre 0 metri di distanza). Il cue che vogliamo isolare in un tick puramente temporale è la
    // transizione RUN->WALK dopo runSeconds, che RoutineEngine.advanceTo() decide solo dal tempo
    // trascorso, non dalla distanza -- ma per raggiungere la fase RUN serve comunque partire da
    // lì: si usa quindi initialConstantKm=0f per il primo fix (attiva + fa scattare ROUTINE_START
    // su QUEL fix, ignorato da questo test) e si isola il cue di interesse (WALK_ROUTINE) sul
    // secondo tick, puramente temporale, 11s dopo, con runSeconds=10.
    @Test
    fun `a purely temporal tick that triggers a routine phase transition emits AudioCueEmittedEvent`() {
        val sequencer = newSequencer(
            preferences = RunPreferences(mode = RoutineMode.WALK_RUN, initialConstantKm = 0f, runSeconds = 10, walkSeconds = 10),
        )
        // Attiva il coordinator e fa scattare ROUTINE_START su questo stesso fix (ignorato qui --
        // lo si prova già nel caso 9, sotto).
        sequencer.onLocationFix(reliableSample(0L), position(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L)
        // 11s dopo, nessun fix nel mezzo: solo il tempo trascorso fa scattare la transizione
        // RUN->WALK (durationMillis()=10_000L per la fase RUN) e il cue WALK_ROUTINE.
        val result = sequencer.onTimerTick(11_000L)
        assertTrue(result.events.any { it is AudioCueEmittedEvent })
    }

    // Caso 4: lo snapshot GNSS passato a onLocationFix deve finire nel LocationFixEvent di QUEL
    // fix, non in un altro.
    @Test
    fun `the GNSS snapshot passed to onLocationFix ends up on that fix's LocationFixEvent`() {
        val sequencer = newSequencer()
        val snapshot = GnssSnapshot(satellitesUsedInFix = 7, cn0MinDbHz = 18f, cn0AvgDbHz = 22f, cn0MaxDbHz = 30f, geometry = emptyList())
        val result = sequencer.onLocationFix(reliableSample(0L), position(0L), snapshot, "gps", reliableReliability(), QualityTier.OTTIMA, 0L)
        val fixEvent = result.events.filterIsInstance<LocationFixEvent>().single()
        assertEquals(7, fixEvent.satellitesUsedInFix)
        assertEquals(18f, fixEvent.satelliteCn0MinDbHz)
    }

    // Caso 5: cadenza dei 30s per deviceHealthSampleDue, e recordDeviceHealthSample() costruisce
    // l'evento corretto con i valori passati. (L'ANTEPOSIZIONE di questo evento rispetto agli
    // altri eventi dello stesso tick è comportamento del layer Android, non del Sequencer --
    // testata al Task 9, non qui: recordDeviceHealthSample() qui produce solo l'evento isolato.)
    @Test
    fun `deviceHealthSampleDue fires exactly every 30 seconds, and recordDeviceHealthSample builds the right event`() {
        val sequencer = newSequencer(preferences = RunPreferences(mode = RoutineMode.CONSTANT))
        val firstTick = sequencer.onTimerTick(1_000L)
        assertTrue("first tick must be due (bucket 0 -> first observation)", firstTick.deviceHealthSampleDue)
        val secondTick = sequencer.onTimerTick(2_000L)
        assertFalse("same 30s bucket, must not be due again", secondTick.deviceHealthSampleDue)
        val thirdTick = sequencer.onTimerTick(31_000L)
        assertTrue("new 30s bucket, must be due again", thirdTick.deviceHealthSampleDue)

        val event = sequencer.recordDeviceHealthSample(batteryPercent = 42, thermalStatus = ThermalStatus.MODERATE, now = 31_000L)
        val health = event as DeviceHealthSampledEvent
        assertEquals(42, health.batteryPercent)
        assertEquals(ThermalStatus.MODERATE, health.thermalStatus)
        assertEquals(31_000L, health.elapsedRealtimeMillis)
    }

    // Caso 6: locationSequenceNumber riparte da 0 a ogni nuova istanza (una per sessione).
    @Test
    fun `sequence number starts at 0 for a fresh Sequencer instance`() {
        val sequencer = newSequencer()
        val first = sequencer.onLocationFix(reliableSample(0L), position(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L)
        assertEquals(0, first.events.filterIsInstance<LocationFixEvent>().single().sequenceNumber)
        val second = sequencer.onLocationFix(reliableSample(1_000L), position(1_000L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 1_000L)
        assertEquals(1, second.events.filterIsInstance<LocationFixEvent>().single().sequenceNumber)
    }

    // Caso 7: fusione delle due chiamate a evaluate() -- qui verifichiamo solo che il Sequencer
    // stesso accetti reliability/qualityTier come PARAMETRI (calcolati una sola volta dal
    // chiamante, non ricalcolati internamente) e li riporti fedelmente sul LocationFixEvent --
    // il resto della fusione (chi chiama evaluate() e quante volte) è comportamento di
    // :android-connector, testato al Task 9.
    @Test
    fun `onLocationFix reports the reliability and qualityTier it was given, without recomputing them`() {
        val sequencer = newSequencer()
        val reliability = LocationReliability(reliable = false, reasons = listOf(RejectionReason.HORIZONTAL_ACCURACY_TOO_HIGH))
        val result = sequencer.onLocationFix(reliableSample(0L), position(0L), null, "gps", reliability, QualityTier.SCARSA, 0L)
        val fixEvent = result.events.filterIsInstance<LocationFixEvent>().single()
        assertFalse(fixEvent.reliable)
        assertEquals(listOf(RejectionReason.HORIZONTAL_ACCURACY_TOO_HIGH), fixEvent.rejectionReasons)
        assertEquals(QualityTier.SCARSA, fixEvent.qualityTier)
    }

    // Caso 8: gpsSilentGap -- un gap che supera la soglia produce un GpsSilentGapEvent PRIMA del
    // LocationFixEvent nella stessa lista.
    @Test
    fun `a silent gap over threshold produces GpsSilentGapEvent before LocationFixEvent`() {
        val sequencer = newSequencer(startedAt = 0L)
        // GpsSilentGapTracker's default threshold is 5_000ms -- a fix arriving 6s after reset()
        // (called with sessionStartedAtElapsedMillis=0 in the constructor) triggers it.
        val result = sequencer.onLocationFix(reliableSample(6_000L), position(6_000L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 6_000L)
        val gapIndex = result.events.indexOfFirst { it is GpsSilentGapEvent }
        val fixIndex = result.events.indexOfFirst { it is LocationFixEvent }
        assertTrue("gap event must be present", gapIndex >= 0)
        assertTrue("gap event must precede the fix event", gapIndex < fixIndex)
    }

    // Caso 9: collegamento dei quattro motori interni alla onEvent del Sequencer -- ciascuno
    // deve poter emettere il proprio evento specifico, distinto da quelli che
    // SessionCoordinator.process() emette per conto proprio.
    @Test
    fun `all four inner engines are wired to the Sequencer's own onEvent`() {
        // RoutineEngine: RoutinePhaseChangedEvent al primo FIX (non tick -- PendingSessionCoordinator
        // non è ancora attivato prima di un primo fix affidabile, vedi nota sui casi 3(a)/3(b) sopra)
        // in WALK_RUN con initialConstantKm=0, che fa scattare startRun() sullo stesso fix che attiva
        // il coordinator.
        val routineSequencer = newSequencer(
            preferences = RunPreferences(mode = RoutineMode.WALK_RUN, initialConstantKm = 0f, runSeconds = 30, walkSeconds = 30),
        )
        val routineResult = routineSequencer.onLocationFix(
            reliableSample(0L), position(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L,
        )
        assertTrue(routineResult.events.any { it is RoutinePhaseChangedEvent })

        // PaceEvaluator: PaceCandidateStartedEvent al primo fix affidabile nel range di pace.
        val paceSequencer = newSequencer(preferences = RunPreferences(constantRange = SpeedRange(9f, 11f)))
        val paceResult = paceSequencer.onLocationFix(reliableSample(0L), position(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L)
        assertTrue(paceResult.events.any { it is PaceCandidateStartedEvent })

        // SpeedEstimator: SpeedChainBrokenEvent(UNRELIABLE_OR_INVALID_FIX) SOLO con una posizione
        // non valida -- SessionCoordinator.process() chiama sempre speedEngine.accept(reliable=true),
        // quindi un fixture "non affidabile" non basta a raggiungere questo ramo.
        val speedSequencer = newSequencer()
        val speedResult = speedSequencer.onLocationFix(
            reliableSample(0L), invalidPosition(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L,
        )
        assertTrue(speedResult.events.any { it is SpeedChainBrokenEvent && it.reason == SpeedChainBreakReason.UNRELIABLE_OR_INVALID_FIX })

        // DistanceAccumulator: DistanceChainBrokenEvent(IMPLAUSIBLE_JUMP), stesso salto del caso 2.
        val distanceSequencer = newSequencer()
        distanceSequencer.onLocationFix(reliableSample(0L), position(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L)
        val distanceResult = distanceSequencer.onLocationFix(
            reliableSample(1_000L), position(1_000L, lat = 46.0), null, "gps", reliableReliability(), QualityTier.OTTIMA, 1_000L,
        )
        assertTrue(distanceResult.events.any { it is DistanceChainBrokenEvent && it.reason == DistanceChainBreakReason.IMPLAUSIBLE_JUMP })
    }

    // Caso 11: engineSpeedKmh si azzera esplicitamente prima di ogni chiamata interna -- un fix
    // inaffidabile che segue un fix affidabile NON deve ereditare la velocità del fix precedente.
    @Test
    fun `engineSpeedKmh is null after an unreliable fix, even right after a reliable one`() {
        val sequencer = newSequencer()
        val firstResult = sequencer.onLocationFix(reliableSample(0L), position(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L)
        // Primo accept() su un KalmanSpeedEngine vergine ritorna sempre 0.0 (semina il filtro) --
        // non è null, ma non è ancora questo il punto: verifichiamo solo che NON resti "appeso"
        // sul secondo fix inaffidabile sotto.
        assertTrue(firstResult.engineSpeedKmh != null)
        val secondSample = LocationSample(speedKmh = 0f, speedAccuracyKmh = null, reliable = false, elapsedRealtimeMillis = 1_000L)
        val secondResult = sequencer.onLocationFix(secondSample, position(1_000L), null, "gps", unreliableReliability(), QualityTier.SCARSA, 1_000L)
        assertNull("an unreliable fix must not inherit the previous fix's speed", secondResult.engineSpeedKmh)
    }

    // Caso 12: il gate reliability.reliable su speedEstimatorDiagnostics -- un fix inaffidabile con
    // getLastAcceptWasMeasurement() ancora vero (stato residuo del fix precedente) non deve
    // allegare diagnostica.
    @Test
    fun `speedEstimatorDiagnostics is null on an unreliable fix, even with residual estimator state`() {
        val sequencer = newSequencer()
        sequencer.onLocationFix(reliableSample(0L), position(0L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 0L)
        // Secondo fix affidabile: il filtro ha ora eseguito una misura reale (lastAcceptWasMeasurement=true).
        sequencer.onLocationFix(reliableSample(1_000L), position(1_000L), null, "gps", reliableReliability(), QualityTier.OTTIMA, 1_000L)
        // Terzo fix, GPS-livello (reliability.reliable=false) ma senza breakChain() (nessuna posizione
        // invalida) -- lastAcceptWasMeasurement() dell'estimator resta true dal fix precedente.
        val thirdSample = LocationSample(speedKmh = 0f, speedAccuracyKmh = null, reliable = false, elapsedRealtimeMillis = 2_000L)
        val result = sequencer.onLocationFix(
            thirdSample, position(2_000L), null, "gps", unreliableReliability(), QualityTier.SCARSA, 2_000L,
        )
        val fixEvent = result.events.filterIsInstance<LocationFixEvent>().single()
        assertNull("reliability.reliable=false must block diagnostics regardless of estimator state", fixEvent.speedEstimatorDiagnostics)
    }
}
