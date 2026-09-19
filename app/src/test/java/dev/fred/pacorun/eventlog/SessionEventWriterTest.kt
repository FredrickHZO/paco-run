package dev.fred.pacorun.eventlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventWriterTest {
    @Test
    fun jsonNumberRendersFiniteDoubleAsIs() {
        assertEquals("3.5", jsonNumber(3.5))
    }

    @Test
    fun jsonNumberRendersNonFiniteDoubleAsNull() {
        assertEquals("null", jsonNumber(Double.NaN))
        assertEquals("null", jsonNumber(Double.POSITIVE_INFINITY))
        assertEquals("null", jsonNumber(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun jsonNumberRendersNonFiniteFloatAsNull() {
        assertEquals("null", jsonNumber(Float.NaN))
        assertEquals("null", jsonNumber(Float.POSITIVE_INFINITY))
    }

    @Test
    fun escapeJsonEscapesQuotesBackslashesAndControlCharacters() {
        assertEquals("line1\\nline2", "line1\nline2".escapeJson())
        assertEquals("say \\\"hi\\\"", "say \"hi\"".escapeJson())
        assertEquals("back\\\\slash", "back\\slash".escapeJson())
    }

    @Test
    fun routinePhaseChangedEventSerializesAllFieldsWithTypeDiscriminator() {
        val event = RoutinePhaseChangedEvent(
            elapsedRealtimeMillis = 12_345L,
            fromPhase = dev.fred.pacorun.routine.RoutinePhase.RUN,
            toPhase = dev.fred.pacorun.routine.RoutinePhase.WALK,
            cumulativeDistanceMeters = 250.5,
        )

        assertEquals(
            "{\"type\":\"RoutinePhaseChangedEvent\",\"elapsedRealtimeMillis\":12345," +
                "\"fromPhase\":\"RUN\",\"toPhase\":\"WALK\",\"cumulativeDistanceMeters\":250.5}",
            SessionEventWriter.toJsonLine(event),
        )
    }

    @Test
    fun paceStateConfirmedEventSerializesNullFromStateAsJsonNull() {
        val event = PaceStateConfirmedEvent(
            elapsedRealtimeMillis = 1_000L,
            fromState = null,
            toState = dev.fred.pacorun.pace.PaceState.GOOD,
            speedKmh = 8f,
            producedAudibleCue = false,
        )

        assertEquals(
            "{\"type\":\"PaceStateConfirmedEvent\",\"elapsedRealtimeMillis\":1000," +
                "\"fromState\":null,\"toState\":\"GOOD\",\"speedKmh\":8.0,\"producedAudibleCue\":false," +
                "\"rawSpeedKmh\":null}",
            SessionEventWriter.toJsonLine(event),
        )
    }

    @Test
    fun paceStateConfirmedEventSerializesRawSpeedKmhWhenPresent() {
        val event = PaceStateConfirmedEvent(
            elapsedRealtimeMillis = 1_000L,
            fromState = dev.fred.pacorun.pace.PaceState.TOO_SLOW,
            toState = dev.fred.pacorun.pace.PaceState.GOOD,
            speedKmh = 7.8f,
            producedAudibleCue = true,
            rawSpeedKmh = 7.4f,
        )

        assertEquals(
            "{\"type\":\"PaceStateConfirmedEvent\",\"elapsedRealtimeMillis\":1000," +
                "\"fromState\":\"TOO_SLOW\",\"toState\":\"GOOD\",\"speedKmh\":7.8,\"producedAudibleCue\":true," +
                "\"rawSpeedKmh\":7.4}",
            SessionEventWriter.toJsonLine(event),
        )
    }

    @Test
    fun deviceHealthSampledEventSerializesNullBatteryPercentAsJsonNull() {
        val event = DeviceHealthSampledEvent(
            elapsedRealtimeMillis = 2_000L,
            batteryPercent = null,
            thermalStatus = ThermalStatus.MODERATE,
        )

        assertEquals(
            "{\"type\":\"DeviceHealthSampledEvent\",\"elapsedRealtimeMillis\":2000," +
                "\"batteryPercent\":null,\"thermalStatus\":\"MODERATE\"}",
            SessionEventWriter.toJsonLine(event),
        )
    }

    @Test
    fun locationFixEventSerializesRejectionReasonListAndNestedNullableDiagnostics() {
        val event = LocationFixEvent(
            elapsedRealtimeMillis = 3_000L,
            sequenceNumber = 4,
            elapsedSinceStartMillis = 3_000L,
            provider = "gps",
            reliable = false,
            rejectionReasons = listOf(RejectionReason.SPEED_ACCURACY_TOO_HIGH, RejectionReason.FIX_TOO_OLD),
            satellitesUsedInFix = 6,
            satelliteCn0MinDbHz = 18.5f,
            satelliteCn0AvgDbHz = 24.1f,
            satelliteCn0MaxDbHz = 30.0f,
            qualityTier = QualityTier.SCARSA,
            speedEstimatorDiagnostics = null,
        )

        assertEquals(
            "{\"type\":\"LocationFixEvent\",\"elapsedRealtimeMillis\":3000,\"sequenceNumber\":4," +
                "\"elapsedSinceStartMillis\":3000,\"provider\":\"gps\",\"reliable\":false," +
                "\"rejectionReasons\":[\"SPEED_ACCURACY_TOO_HIGH\",\"FIX_TOO_OLD\"]," +
                "\"satellitesUsedInFix\":6,\"satelliteCn0MinDbHz\":18.5,\"satelliteCn0AvgDbHz\":24.1," +
                "\"satelliteCn0MaxDbHz\":30.0,\"qualityTier\":\"SCARSA\",\"speedEstimatorDiagnostics\":null}",
            SessionEventWriter.toJsonLine(event),
        )
    }

    @Test
    fun locationFixEventSerializesNonFiniteInnovationEmaInsideNestedDiagnosticsAsNull() {
        val event = LocationFixEvent(
            elapsedRealtimeMillis = 4_000L,
            sequenceNumber = 5,
            elapsedSinceStartMillis = 4_000L,
            provider = "gps",
            reliable = true,
            rejectionReasons = emptyList(),
            satellitesUsedInFix = null,
            satelliteCn0MinDbHz = null,
            satelliteCn0AvgDbHz = null,
            satelliteCn0MaxDbHz = null,
            qualityTier = QualityTier.BUONA,
            speedEstimatorDiagnostics = SpeedEstimatorDiagnostics(
                processNoiseRegime = ProcessNoiseRegime.Q_HIGH,
                accuracyGateEngaged = true,
                innovationEmaMagnitude = Double.NaN,
            ),
        )

        val line = SessionEventWriter.toJsonLine(event)

        assertEquals(
            "{\"type\":\"LocationFixEvent\",\"elapsedRealtimeMillis\":4000,\"sequenceNumber\":5," +
                "\"elapsedSinceStartMillis\":4000,\"provider\":\"gps\",\"reliable\":true," +
                "\"rejectionReasons\":[],\"satellitesUsedInFix\":null,\"satelliteCn0MinDbHz\":null," +
                "\"satelliteCn0AvgDbHz\":null,\"satelliteCn0MaxDbHz\":null,\"qualityTier\":\"BUONA\"," +
                "\"speedEstimatorDiagnostics\":{\"processNoiseRegime\":\"Q_HIGH\",\"accuracyGateEngaged\":true," +
                "\"innovationEmaMagnitude\":null,\"robustWeight\":1.0,\"postBurstExemptionApplied\":false}}",
            line,
        )
        assertEquals(false, line.contains("NaN"))
    }

    @Test
    fun speedEstimatorDiagnosticsSerializesRobustWeight() {
        val event = LocationFixEvent(
            elapsedRealtimeMillis = 5_000L,
            sequenceNumber = 6,
            elapsedSinceStartMillis = 5_000L,
            provider = "gps",
            reliable = true,
            rejectionReasons = emptyList(),
            satellitesUsedInFix = null,
            satelliteCn0MinDbHz = null,
            satelliteCn0AvgDbHz = null,
            satelliteCn0MaxDbHz = null,
            qualityTier = QualityTier.BUONA,
            speedEstimatorDiagnostics = SpeedEstimatorDiagnostics(
                processNoiseRegime = ProcessNoiseRegime.Q_LOW,
                accuracyGateEngaged = false,
                innovationEmaMagnitude = 0.1,
                robustWeight = 0.42,
            ),
        )

        assertTrue(SessionEventWriter.toJsonLine(event).contains("\"robustWeight\":0.42"))
    }

    @Test
    fun sessionStartedSerializesNestedPreferencesAndSensors() {
        val event = SessionStarted(
            elapsedRealtimeMillis = 0L,
            deviceModel = "Google Pixel 9a",
            osVersionRelease = "16",
            osApiLevel = 36,
            appGitCommitHash = "abc123def456",
            preferences = dev.fred.pacorun.settings.RunPreferences(),
            sensors = SensorAvailability(
                hasBarometer = true,
                hasMagnetometer = true,
                hasAccelerometer = true,
                hasGyroscope = true,
                hasStepCounter = false,
                hasStepDetector = false,
            ),
            networkTypeAtStart = NetworkType.WIFI,
        )

        val line = SessionEventWriter.toJsonLine(event)

        assertEquals(true, line.startsWith("{\"type\":\"SessionStarted\",\"elapsedRealtimeMillis\":0,"))
        assertEquals(true, line.contains("\"deviceModel\":\"Google Pixel 9a\""))
        assertEquals(true, line.contains("\"preferences\":{\"mode\":\"CONSTANT\""))
        assertEquals(true, line.contains("\"sensors\":{\"hasBarometer\":true"))
        assertEquals(true, line.contains("\"networkTypeAtStart\":\"WIFI\""))
        assertEquals(false, line.contains("null"))
        // Float -> Double must go through the String round-trip: a direct cast would render the
        // default cueVolume of 0.6f as 0.6000000238418579.
        assertEquals(true, line.contains("\"cueVolume\":0.6"))
        assertEquals(false, line.contains("0.6000000238418579"))
    }
}
