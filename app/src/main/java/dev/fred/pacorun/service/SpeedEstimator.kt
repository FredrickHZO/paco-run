package dev.fred.pacorun.service

import dev.fred.pacorun.eventlog.ProcessNoiseRegime
import dev.fred.pacorun.eventlog.SessionEvent
import dev.fred.pacorun.eventlog.SpeedChainBreakReason
import dev.fred.pacorun.eventlog.SpeedChainBrokenEvent
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sqrt

class SpeedEstimator(
    private val qLow: Double = 0.10,
    // Round-2 fix: qHigh=25.0 (a ~250x jump from qLow) was tuned against a noise-free
    // simulation. Against real GPS noise it drives up the *velocity* Kalman gain (k1, not the
    // position gain k0, which barely moves across the tested range) -- measured: k1 goes from
    // 0.054 at q=0.10 to 0.338 at q=25, a 6.2x amplification -- which injects raw position noise
    // directly into the velocity estimate as large, spurious swings (measured: 0.68 km/h of
    // per-fix velocity jitter at q=0.10 vs. 4.26 km/h at q=25). A much smaller jump (~7.5x qLow)
    // still measurably speeds up settling on a real acceleration without that amplification.
    // See task-3-report.md for the empirical sweep across qHigh values.
    private val qHigh: Double = 0.75,
    // Round-2 fix: 0.30 was tuned against noise-free innovation math. Empirically, a
    // consecutive-fixes hysteresis requirement was also tried on top of the signed EMA (see
    // task-3-report.md) but cost more detection latency than it saved in false-positive rate
    // within a realistic settling budget; a higher single-fix threshold on the signed EMA alone
    // gives a better empirical trade-off between the two properties this switch must satisfy.
    private val innovationThreshold: Double = 0.60,
    private val innovationEmaAlpha: Double = 0.3,
    // Task 1 (speed-estimator-accuracy-gate-and-export plan): an independent gate on top of the
    // adaptive switch above, added after the prior plan's final review found the switch can be
    // fooled by a sudden, severe accuracy degradation with no real motion change -- the raw
    // position noise from bad accuracy looks just like a real acceleration to the innovation
    // detector. Empirical sweep (see task-1-report.md): alpha=0.05/ratio=2.0 is the slowest
    // baseline / smallest threshold combination in the sweep that (a) protects every single fix
    // of the Task 8 degraded burst across all 5000 swept seeds (minimum gated count 8/8, not just
    // the first fix -- this is what rules out a single-previous-fix baseline, which would only
    // catch the first fix) and (b) produces zero false engagements against the real recorded
    // run's ordinary 3.0-6.6 m accuracy jitter (0/5195 fixes). Ratios up to 2.2 at this alpha tie
    // on both counts; 2.0 was picked as the round number in the middle of that flat region.
    private val accuracyDegradationRatio: Double = 2.0,
    private val accuracyBaselineAlpha: Double = 0.05,
    private val onEvent: (SessionEvent) -> Unit = {},
) {
    // Round-2 fix: an EMA of *unsigned* normalized innovation magnitude cannot tell a real
    // sustained acceleration apart from routine GPS noise -- a correctly-calibrated Kalman
    // filter's normalized innovation behaves like |Z| for a standard normal Z even at true
    // constant velocity (E|Z| ~= 0.8), so its EMA wanders close to any threshold low enough to
    // catch a real speed step. Signed innovation doesn't have this problem: pure noise flips
    // sign fix-to-fix and its EMA reverts toward zero, while a sustained real acceleration
    // biases the innovation in one direction for several fixes in a row and its EMA grows.
    // See task-3-report.md for the empirical evidence.
    private class AxisKalman(initialPosition: Double) {
        var position: Double = initialPosition
        var velocity: Double = 0.0
        /** Signed normalized innovation from the most recent [update] call -- see class-level comment. */
        var lastSignedNormalizedInnovation: Double = 0.0
            private set
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
            val signedNormalizedInnovation = innovation / sqrt(innovationVariance)
            lastSignedNormalizedInnovation = signedNormalizedInnovation
            val normalizedInnovation = kotlin.math.abs(signedNormalizedInnovation)
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
    // Signed innovation EMA per axis (see class-level comment): reverts toward zero under
    // symmetric noise, grows under a sustained real acceleration biased in one direction.
    private var eastInnovationEma: Double = 0.0
    private var northInnovationEma: Double = 0.0
    private var lastProcessNoise: Double = qLow
    // Short-memory EMA baseline of horizontalAccuracyMeters, used by the accuracy gate below. A
    // single-previous-fix baseline was explicitly rejected (see task-1-report.md): it would only
    // catch the first fix of a multi-fix degraded burst, comparing each later fix against the
    // already-degraded fix before it instead of against what accuracy actually looked like before
    // the burst started.
    private var accuracyBaselineMeters: Double? = null
    private var lastAccuracyGateEngaged: Boolean = false
    // False whenever the last accept() returned without performing a real Kalman update (first
    // fix of a chain, or any breakChain() path): in those cases lastProcessNoise /
    // lastAccuracyGateEngaged still hold an earlier fix's values, so a caller reporting them as
    // this fix's diagnostics would be reporting stale data.
    private var lastAcceptWasMeasurement = false

    // Test/debugging accessors (internal visibility)
    internal fun getLastInnovationEma(): Double = maxOf(kotlin.math.abs(eastInnovationEma), kotlin.math.abs(northInnovationEma))
    internal fun getLastProcessNoise(): Double = lastProcessNoise
    internal fun getLastAccuracyGateEngaged(): Boolean = lastAccuracyGateEngaged
    internal fun getLastProcessNoiseRegime(): ProcessNoiseRegime =
        if (lastProcessNoise == qHigh) ProcessNoiseRegime.Q_HIGH else ProcessNoiseRegime.Q_LOW
    internal fun getLastAcceptWasMeasurement(): Boolean = lastAcceptWasMeasurement

    fun accept(position: LocationPosition, reliable: Boolean): Double {
        if (!reliable || !position.isValid()) {
            onEvent(SpeedChainBrokenEvent(position.elapsedRealtimeMillis, SpeedChainBreakReason.UNRELIABLE_OR_INVALID_FIX))
            return breakChain()
        }

        val lat0 = originLatitude ?: position.latitude.also { originLatitude = it }
        val lon0 = originLongitude ?: position.longitude.also { originLongitude = it }
        val metersPerDegreeLongitude = METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(lat0))
        val x = (position.longitude - lon0) * metersPerDegreeLongitude
        val y = (position.latitude - lat0) * METERS_PER_DEGREE_LATITUDE

        val accuracyMeters = (position.horizontalAccuracyMeters ?: DEFAULT_HORIZONTAL_ACCURACY_METERS).coerceAtLeast(1f).toDouble()

        val east = eastAxis
        val north = northAxis
        val previousElapsed = lastElapsedRealtimeMillis

        if (east == null || north == null || previousElapsed == null) {
            eastAxis = AxisKalman(x)
            northAxis = AxisKalman(y)
            lastElapsedRealtimeMillis = position.elapsedRealtimeMillis
            accuracyBaselineMeters = accuracyMeters
            lastAcceptWasMeasurement = false
            return 0.0
        }

        val dtSeconds = (position.elapsedRealtimeMillis - previousElapsed) / 1_000.0
        if (dtSeconds <= 0.0) {
            onEvent(SpeedChainBrokenEvent(position.elapsedRealtimeMillis, SpeedChainBreakReason.NON_POSITIVE_TIME_DELTA))
            return breakChain()
        }

        // Accuracy gate (Task 1): a fix whose accuracy has degraded well past the recent
        // baseline gets qLow forced regardless of the innovation-EMA decision below, and its
        // innovation is excluded from that EMA -- see the class-level comment on
        // [accuracyBaselineMeters] for why this needs a smoothed baseline, not just the previous
        // fix's accuracy.
        val accuracyBaseline = accuracyBaselineMeters ?: accuracyMeters
        val accuracyDegraded = accuracyMeters > accuracyBaseline * accuracyDegradationRatio
        lastAccuracyGateEngaged = accuracyDegraded

        val processNoise = if (accuracyDegraded) {
            qLow
        } else if (maxOf(kotlin.math.abs(eastInnovationEma), kotlin.math.abs(northInnovationEma)) > innovationThreshold) {
            qHigh
        } else {
            qLow
        }
        lastProcessNoise = processNoise
        lastAcceptWasMeasurement = true
        east.predict(dtSeconds, processNoise)
        north.predict(dtSeconds, processNoise)

        val measurementVariance = accuracyMeters.pow(2)
        east.update(x, measurementVariance)
        north.update(y, measurementVariance)
        if (!accuracyDegraded) {
            eastInnovationEma = innovationEmaAlpha * east.lastSignedNormalizedInnovation + (1 - innovationEmaAlpha) * eastInnovationEma
            northInnovationEma = innovationEmaAlpha * north.lastSignedNormalizedInnovation + (1 - innovationEmaAlpha) * northInnovationEma
        }
        // Always update the baseline, even on a degraded fix, so it tracks reality rather than
        // freezing on stale "good" data forever.
        accuracyBaselineMeters = accuracyBaselineAlpha * accuracyMeters + (1 - accuracyBaselineAlpha) * accuracyBaseline

        lastElapsedRealtimeMillis = position.elapsedRealtimeMillis
        val speed = hypot(east.velocity, north.velocity) * METERS_PER_SECOND_TO_KMH
        if (speed.isFinite()) return speed
        onEvent(SpeedChainBrokenEvent(position.elapsedRealtimeMillis, SpeedChainBreakReason.NON_FINITE_RESULT))
        return breakChain()
    }

    fun breakChain(): Double {
        eastAxis = null
        northAxis = null
        lastElapsedRealtimeMillis = null
        eastInnovationEma = 0.0
        northInnovationEma = 0.0
        accuracyBaselineMeters = null
        lastAcceptWasMeasurement = false
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
