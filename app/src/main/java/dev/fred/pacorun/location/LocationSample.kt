package dev.fred.pacorun.location

data class LocationSample(
    val speedKmh: Float,
    val speedAccuracyKmh: Float?,
    val reliable: Boolean,
    val elapsedRealtimeMillis: Long,
)
