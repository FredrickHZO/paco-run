package dev.fred.pacorun.service

import dev.fred.pacorun.location.LocationSample
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedEngineDiagnosticsTest {
    @Test
    fun `KalmanSpeedEngine implements SpeedEngineDiagnostics`() {
        val engine = KalmanSpeedEngine()
        assertNotNull(engine as? SpeedEngineDiagnostics)
    }

    @Test
    fun `DopplerSpeedEngine does not implement SpeedEngineDiagnostics`() {
        val engine = DopplerSpeedEngine()
        assertNull(engine as? SpeedEngineDiagnostics)
    }

    @Test
    fun `diagnostics reflects the underlying SpeedEstimator after a real measurement`() {
        val engine = KalmanSpeedEngine()
        val diagnosticsView = engine as SpeedEngineDiagnostics
        val position = LocationPosition(latitude = 45.0, longitude = 9.0, elapsedRealtimeMillis = 0L, horizontalAccuracyMeters = 5f)
        engine.accept(sample = fakeSample(), position = position, reliable = true)
        // Second fix: first accept() only seeds the filter (returns 0.0, lastAcceptWasMeasurement=false).
        val secondPosition = position.copy(elapsedRealtimeMillis = 1_000L, latitude = 45.0001)
        engine.accept(sample = fakeSample(), position = secondPosition, reliable = true)
        assertTrue(diagnosticsView.lastAcceptWasMeasurement())
        assertNotNull(diagnosticsView.diagnostics())
    }

    private fun fakeSample() = LocationSample(
        speedKmh = 10f, speedAccuracyKmh = .3f, reliable = true, elapsedRealtimeMillis = 0L,
    )
}
