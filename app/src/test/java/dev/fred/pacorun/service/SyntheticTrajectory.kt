package dev.fred.pacorun.service

import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object SyntheticTrajectory {
    data class Fix(val position: LocationPosition, val trueSpeedKmh: Double)

    private const val METERS_PER_DEGREE_LATITUDE = 222_640.0
    private const val KMH_TO_METERS_PER_SECOND = 1.0 / 3.6

    fun generate(
        speedProfileKmh: List<Pair<Double, Double>>,
        noiseStdMeters: Double,
        sampleIntervalSeconds: Double = 1.0,
        accuracyMeters: Float = noiseStdMeters.toFloat(),
        headingDegrees: Double = 0.0,
        startLatitude: Double = 45.0,
        startLongitude: Double = 9.0,
        startElapsedMillis: Long = 0L,
        seed: Long = 42L,
    ): List<Fix> {
        val random = Random(seed)
        val headingRadians = Math.toRadians(headingDegrees)
        val fixes = mutableListOf<Fix>()

        var trueLatitude = startLatitude
        var trueLongitude = startLongitude
        var elapsedMillis = startElapsedMillis
        var timeInSegment = 0.0
        var segmentIndex = 0

        while (segmentIndex < speedProfileKmh.size) {
            val (durationSeconds, speedKmh) = speedProfileKmh[segmentIndex]
            val distanceMeters = (speedKmh * KMH_TO_METERS_PER_SECOND) * sampleIntervalSeconds
            val metersPerDegreeLongitude = METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(trueLatitude))
            trueLatitude += (distanceMeters * cos(headingRadians)) / METERS_PER_DEGREE_LATITUDE
            trueLongitude += (distanceMeters * sin(headingRadians)) / metersPerDegreeLongitude
            elapsedMillis += (sampleIntervalSeconds * 1_000).toLong()

            val (noisyLat, noisyLon) = addGaussianNoiseMeters(trueLatitude, trueLongitude, noiseStdMeters, random)
            fixes += Fix(LocationPosition(noisyLat, noisyLon, elapsedMillis, accuracyMeters), speedKmh)

            timeInSegment += sampleIntervalSeconds
            if (timeInSegment + 1e-9 >= durationSeconds) {
                timeInSegment = 0.0
                segmentIndex++
            }
        }
        return fixes
    }

    private fun addGaussianNoiseMeters(latitude: Double, longitude: Double, stdMeters: Double, random: Random): Pair<Double, Double> {
        val metersPerDegreeLongitude = METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(latitude))
        val noisyLat = latitude + (gaussian(random) * stdMeters) / METERS_PER_DEGREE_LATITUDE
        val noisyLon = longitude + (gaussian(random) * stdMeters) / metersPerDegreeLongitude
        return noisyLat to noisyLon
    }

    // Box-Muller transform: a standard normal sample from two uniform ones, no extra dependency needed.
    private fun gaussian(random: Random): Double {
        val u1 = random.nextDouble().coerceAtLeast(1e-12)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * Math.PI * u2)
    }
}
