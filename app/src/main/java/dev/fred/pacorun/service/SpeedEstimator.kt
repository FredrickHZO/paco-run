package dev.fred.pacorun.service

class SpeedEstimator(
    private val qLow: Double = 0.10,
    private val qHigh: Double = 25.0,
    private val innovationThreshold: Double = 1.75,
    private val innovationEmaAlpha: Double = 0.3,
) {
    private var lastElapsedRealtimeMillis: Long? = null
    private var innovationEma: Double = 0.0

    fun accept(position: LocationPosition, reliable: Boolean): Double {
        if (!reliable || !position.isValid()) return breakChain()

        val previousElapsed = lastElapsedRealtimeMillis
        if (previousElapsed == null) {
            lastElapsedRealtimeMillis = position.elapsedRealtimeMillis
            return 0.0
        }

        val dtSeconds = (position.elapsedRealtimeMillis - previousElapsed) / 1_000.0
        if (dtSeconds <= 0.0) return breakChain()

        lastElapsedRealtimeMillis = position.elapsedRealtimeMillis
        return 0.0 // real Kalman math lands in Task 2
    }

    fun breakChain(): Double {
        lastElapsedRealtimeMillis = null
        innovationEma = 0.0
        return 0.0
    }

    private fun LocationPosition.isValid() = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
}
