package dev.fred.pacorun.location

import android.location.Location
import java.util.concurrent.TimeUnit

class LocationSampleMapper(
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
    private val maxSpeedAccuracyMps: Float = .35f,
    // A fix with good speed accuracy can still have a poor horizontal (position) fix -- speed
    // is derived largely from Doppler/carrier measurements and can stay accurate even when the
    // position itself is tens of meters off. 20m rejects fixes that are clearly degraded
    // (typical open-sky accuracy is 3-5m, urban-canyon/tree-cover commonly 10-30m) while not
    // rejecting ordinary conditions.
    private val maxHorizontalAccuracyMeters: Float = 20f,
) {
    fun map(location: Location, nowElapsedRealtimeMillis: Long): LocationSample {
        val elapsedRealtimeMillis = TimeUnit.NANOSECONDS.toMillis(location.elapsedRealtimeNanos)
        return map(
            speedMps = location.takeIf { it.hasSpeed() }?.speed,
            accuracyMps = location.takeIf { it.hasSpeedAccuracy() }?.speedAccuracyMetersPerSecond,
            ageMillis = nowElapsedRealtimeMillis - elapsedRealtimeMillis,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
            horizontalAccuracyMeters = location.takeIf { it.hasAccuracy() }?.accuracy,
        )
    }

    fun map(
        speedMps: Float?,
        accuracyMps: Float?,
        ageMillis: Long,
        elapsedRealtimeMillis: Long = 0L,
        horizontalAccuracyMeters: Float? = null,
    ): LocationSample {
        val reliable = speedMps != null &&
            speedMps.isFinite() &&
            speedMps >= 0f &&
            accuracyMps != null &&
            accuracyMps.isFinite() &&
            accuracyMps >= 0f &&
            ageMillis in 0..maxAgeMillis &&
            accuracyMps <= maxSpeedAccuracyMps &&
            (horizontalAccuracyMeters == null || (horizontalAccuracyMeters.isFinite() && horizontalAccuracyMeters <= maxHorizontalAccuracyMeters))

        return LocationSample(
            speedKmh = (speedMps ?: 0f) * 3.6f,
            speedAccuracyKmh = accuracyMps?.times(3.6f),
            reliable = reliable,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
        )
    }

    companion object {
        const val DEFAULT_MAX_AGE_MILLIS = 3_000L
    }
}
