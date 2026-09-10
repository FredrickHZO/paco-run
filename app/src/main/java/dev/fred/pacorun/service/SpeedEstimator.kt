package dev.fred.pacorun.service

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

class SpeedEstimator(
    private val qLow: Double = 0.10,
    private val qHigh: Double = 25.0,
    private val innovationThreshold: Double = 1.75,
    private val innovationEmaAlpha: Double = 0.3,
) {
    private class AxisKalman(initialPosition: Double) {
        var position: Double = initialPosition
        var velocity: Double = 0.0
        private var p00: Double = INITIAL_POSITION_VARIANCE
        private var p01: Double = 0.0
        private var p10: Double = 0.0
        private var p11: Double = INITIAL_VELOCITY_VARIANCE

        fun predict(dtSeconds: Double, processNoise: Double) {
            position += velocity * dtSeconds
            val q11 = processNoise * dtSeconds.pow(3) / 3.0
            val q12 = processNoise * dtSeconds.pow(2) / 2.0
            val q22 = processNoise * dtSeconds
            val newP00 = p00 + dtSeconds * (p10 + p01) + dtSeconds * dtSeconds * p11 + q11
            val newP01 = p01 + dtSeconds * p11 + q12
            val newP10 = p10 + dtSeconds * p11 + q12
            val newP11 = p11 + q22
            p00 = newP00; p01 = newP01; p10 = newP10; p11 = newP11
        }

        /** Returns the normalized innovation |residual| / sqrt(innovation variance), used to detect real accelerations. */
        fun update(measurement: Double, measurementVariance: Double): Double {
            val innovation = measurement - position
            val innovationVariance = p00 + measurementVariance
            val normalizedInnovation = kotlin.math.abs(innovation) / sqrt(innovationVariance)
            val k0 = p00 / innovationVariance
            val k1 = p10 / innovationVariance
            position += k0 * innovation
            velocity += k1 * innovation
            val newP00 = (1 - k0) * p00
            val newP01 = (1 - k0) * p01
            val newP10 = p10 - k1 * p00
            val newP11 = p11 - k1 * p01
            p00 = newP00; p01 = newP01; p10 = newP10; p11 = newP11
            return normalizedInnovation
        }

        private companion object {
            const val INITIAL_POSITION_VARIANCE = 100.0 // (10m std)^2 -- first fix could be anywhere nearby
            const val INITIAL_VELOCITY_VARIANCE = 25.0   // (5 m/s std)^2 -- no prior on starting speed
        }
    }

    private var eastAxis: AxisKalman? = null
    private var northAxis: AxisKalman? = null
    private var originLatitude: Double? = null
    private var originLongitude: Double? = null
    private var lastElapsedRealtimeMillis: Long? = null
    private var innovationEma: Double = 0.0

    fun accept(position: LocationPosition, reliable: Boolean): Double {
        if (!reliable || !position.isValid()) return breakChain()

        val lat0 = originLatitude ?: position.latitude.also { originLatitude = it }
        val lon0 = originLongitude ?: position.longitude.also { originLongitude = it }
        val metersPerDegreeLongitude = METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(lat0))
        val x = (position.longitude - lon0) * metersPerDegreeLongitude
        val y = (position.latitude - lat0) * METERS_PER_DEGREE_LATITUDE

        val east = eastAxis
        val north = northAxis
        val previousElapsed = lastElapsedRealtimeMillis

        if (east == null || north == null || previousElapsed == null) {
            eastAxis = AxisKalman(x)
            northAxis = AxisKalman(y)
            lastElapsedRealtimeMillis = position.elapsedRealtimeMillis
            return 0.0
        }

        val dtSeconds = (position.elapsedRealtimeMillis - previousElapsed) / 1_000.0
        if (dtSeconds <= 0.0) return breakChain()

        east.predict(dtSeconds, qLow)
        north.predict(dtSeconds, qLow)

        val accuracyMeters = (position.horizontalAccuracyMeters ?: DEFAULT_HORIZONTAL_ACCURACY_METERS).coerceAtLeast(1f)
        val measurementVariance = accuracyMeters.toDouble().pow(2)
        east.update(x, measurementVariance)
        north.update(y, measurementVariance)

        lastElapsedRealtimeMillis = position.elapsedRealtimeMillis
        return hypot(east.velocity, north.velocity) * METERS_PER_SECOND_TO_KMH
    }

    fun breakChain(): Double {
        eastAxis = null
        northAxis = null
        lastElapsedRealtimeMillis = null
        innovationEma = 0.0
        return 0.0
    }

    private fun LocationPosition.isValid() = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0

    private companion object {
        const val METERS_PER_DEGREE_LATITUDE = 111_320.0
        const val DEFAULT_HORIZONTAL_ACCURACY_METERS = 10f
        const val METERS_PER_SECOND_TO_KMH = 3.6
    }
}
