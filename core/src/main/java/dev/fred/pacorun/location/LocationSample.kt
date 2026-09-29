package dev.fred.pacorun.location

const val DEFAULT_MAX_AGE_MILLIS = 3_000L

data class LocationSample(
    val speedKmh: Float,
    val speedAccuracyKmh: Float?,
    val reliable: Boolean,
    val elapsedRealtimeMillis: Long,
)
