package dev.fred.pacorun.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class SyntheticTrajectoryTest {
    @Test
    fun generatedFixesAverageToTheRequestedSpeedOverAConstantSegment() {
        val fixes = SyntheticTrajectory.generate(
            speedProfileKmh = listOf(60.0 to 10.0),
            noiseStdMeters = 3.0,
        )

        assertEquals(60, fixes.size)
        assertTrue(fixes.all { it.trueSpeedKmh == 10.0 })

        // Measure average speed via net displacement (first to last fix) instead of
        // summing consecutive-fix distances. At high noise relative to per-step signal,
        // summing point-to-point distances exhibits upward bias from the "coastline paradox":
        // noise jitter at each fix never partially cancels when summing magnitudes alone.
        // Net displacement is far less sensitive to per-fix noise.
        val firstPos = fixes.first().position
        val lastPos = fixes.last().position
        val netDisplacementMeters = haversineMeters(firstPos.latitude, firstPos.longitude, lastPos.latitude, lastPos.longitude)
        val totalSeconds = (lastPos.elapsedRealtimeMillis - firstPos.elapsedRealtimeMillis) / 1_000.0
        val averageSpeedKmh = (netDisplacementMeters / totalSeconds) * 3.6
        assertEquals(10.0, averageSpeedKmh, 2.0)
    }

    @Test
    fun noiseStandardDeviationMatchesRequestedMagnitude() {
        // Zero true motion, so every fix-to-fix displacement IS the noise, letting us check
        // the generator's actual injected noise std against what was requested.
        val fixes = SyntheticTrajectory.generate(
            speedProfileKmh = listOf(200.0 to 0.0),
            noiseStdMeters = 5.0,
        )

        val deltas = (1 until fixes.size).map { i ->
            haversineMeters(
                fixes[i - 1].position.latitude, fixes[i - 1].position.longitude,
                fixes[i].position.latitude, fixes[i].position.longitude,
            )
        }
        // Each delta is the difference of two independent 2D Gaussian noise samples (std
        // noiseStdMeters each), so its expected magnitude is on the order of noiseStdMeters --
        // loose bounds here since this is a statistical property of a small sample, not exact.
        val meanDelta = deltas.average()
        assertTrue("mean delta $meanDelta should be roughly noiseStdMeters-scaled", meanDelta in 1.0..15.0)
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1); val dLambda = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dPhi / 2).let { it * it } +
            kotlin.math.cos(p1) * kotlin.math.cos(p2) * kotlin.math.sin(dLambda / 2).let { it * it }
        return 2 * r * kotlin.math.asin(sqrt(a))
    }
}
