package dev.fred.pacorun.location

import android.location.Location
import dev.fred.pacorun.eventlog.QualityTier
import dev.fred.pacorun.eventlog.RejectionReason
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
        val reliability = evaluate(speedMps, accuracyMps, ageMillis, horizontalAccuracyMeters)
        return LocationSample(
            speedKmh = (speedMps ?: 0f) * 3.6f,
            speedAccuracyKmh = accuracyMps?.times(3.6f),
            reliable = reliability.reliable,
            elapsedRealtimeMillis = elapsedRealtimeMillis,
        )
    }

    // Exposed separately from map() (whose own return type/behavior stays unchanged for its
    // existing callers) so a caller that also wants to know WHICH threshold(s) failed -- for a
    // diagnostic LocationFixEvent -- can ask, without map() changing shape.
    fun evaluate(
        speedMps: Float?,
        accuracyMps: Float?,
        ageMillis: Long,
        horizontalAccuracyMeters: Float? = null,
    ): LocationReliability {
        val reasons = buildList {
            if (speedMps == null || !speedMps.isFinite() || speedMps < 0f) add(RejectionReason.MISSING_OR_INVALID_SPEED)
            if (accuracyMps == null || !accuracyMps.isFinite() || accuracyMps < 0f) {
                add(RejectionReason.MISSING_OR_INVALID_SPEED_ACCURACY)
            } else if (accuracyMps > maxSpeedAccuracyMps) {
                add(RejectionReason.SPEED_ACCURACY_TOO_HIGH)
            }
            if (horizontalAccuracyMeters != null &&
                (!horizontalAccuracyMeters.isFinite() || horizontalAccuracyMeters > maxHorizontalAccuracyMeters)
            ) {
                add(RejectionReason.HORIZONTAL_ACCURACY_TOO_HIGH)
            }
            if (ageMillis !in 0..maxAgeMillis) add(RejectionReason.FIX_TOO_OLD)
        }
        return LocationReliability(reliable = reasons.isEmpty(), reasons = reasons)
    }

    // Readable projection of thresholds that already exist elsewhere in this class -- not a new
    // judgment except for the OTTIMA/BUONA "half the configured threshold" margin, called out
    // explicitly in the spec (5.2) as the one genuinely new cut, open for the owner to revise.
    fun qualityTier(
        reliability: LocationReliability,
        accuracyMps: Float?,
        horizontalAccuracyMeters: Float?,
    ): QualityTier = when {
        reliability.reasons.size >= 2 || reliability.reasons.any { it in MISSING_DATA_REASONS } -> QualityTier.SCARSA
        reliability.reasons.size == 1 -> QualityTier.DEGRADATA
        accuracyMps != null &&
            accuracyMps <= maxSpeedAccuracyMps / 2f &&
            (horizontalAccuracyMeters == null || horizontalAccuracyMeters <= maxHorizontalAccuracyMeters / 2f) -> QualityTier.OTTIMA
        else -> QualityTier.BUONA
    }

    companion object {
        const val DEFAULT_MAX_AGE_MILLIS = 3_000L
        private val MISSING_DATA_REASONS = setOf(
            RejectionReason.MISSING_OR_INVALID_SPEED,
            RejectionReason.MISSING_OR_INVALID_SPEED_ACCURACY,
        )
    }
}

data class LocationReliability(val reliable: Boolean, val reasons: List<RejectionReason>)
