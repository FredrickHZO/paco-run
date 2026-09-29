package dev.fred.pacorun.service

import dev.fred.pacorun.location.LocationSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionSpeedEngineTest {
    @Test
    fun dopplerEngineReturnsRawSampleSpeedUnchanged() {
        val engine = DopplerSpeedEngine()
        val sample = LocationSample(speedKmh = 9.4f, speedAccuracyKmh = .2f, reliable = true, elapsedRealtimeMillis = 0L)
        val position = LocationPosition(45.0, 9.0, 0L, 3f)

        assertEquals(9.4, engine.accept(sample, position, reliable = true), .0001)
    }

    @Test
    fun dopplerEngineBreakChainIsANoOpReturningZero() {
        val engine = DopplerSpeedEngine()

        assertEquals(0.0, engine.breakChain(), .0001)
    }

    @Test
    fun kalmanEngineDelegatesToSpeedEstimator() {
        val engine = KalmanSpeedEngine()
        val sample = LocationSample(speedKmh = 40f, speedAccuracyKmh = .2f, reliable = true, elapsedRealtimeMillis = 0L)
        val firstFix = LocationPosition(45.0, 9.0, 0L, 3f)
        val secondFix = LocationPosition(45.0001, 9.0, 1_000L, 3f)

        // First fix: SpeedEstimator always returns 0.0 (initialization) -- proves delegation,
        // not a coincidence, since the sample's own speedKmh (40f) is nowhere near 0.
        assertEquals(0.0, engine.accept(sample, firstFix, reliable = true), .0001)
        // Second fix: real position-derived speed from SpeedEstimator, not the sample's 40f --
        // don't pin an exact Kalman output here (that's SpeedEstimatorTest.kt's job), just prove
        // this came from position, not from the sample's speedKmh field.
        val secondSpeed = engine.accept(sample, secondFix, reliable = true)
        assertTrue("expected a finite, non-negative speed", secondSpeed.isFinite() && secondSpeed >= 0.0)
        assertTrue("expected the position-derived speed to differ from the sample's own speedKmh (40)", secondSpeed != 40.0)
    }

    @Test
    fun kalmanEngineBreakChainDelegatesToSpeedEstimator() {
        val engine = KalmanSpeedEngine()
        val sample = LocationSample(speedKmh = 9f, speedAccuracyKmh = .2f, reliable = true, elapsedRealtimeMillis = 0L)
        engine.accept(sample, LocationPosition(45.0, 9.0, 0L, 3f), reliable = true)

        assertEquals(0.0, engine.breakChain(), .0001)
        // After breakChain, the next accept behaves like a fresh first fix again (0.0).
        assertEquals(0.0, engine.accept(sample, LocationPosition(45.0, 9.0001, 1_000L, 3f), reliable = true), .0001)
    }
}
