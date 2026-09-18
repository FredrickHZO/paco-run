package dev.fred.pacorun.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSampleMapperTest {
    private val mapper = LocationSampleMapper()

    @Test
    fun convertsMetersPerSecondToKmh() {
        val sample = mapper.map(speedMps = 2.5f, accuracyMps = .1f, ageMillis = 0L)

        assertEquals(9f, sample.speedKmh, .001f)
        assertEquals(.36f, sample.speedAccuracyKmh!!, .001f)
        assertTrue(sample.reliable)
    }

    @Test
    fun rejectsMissingSpeed() {
        assertFalse(mapper.map(speedMps = null, accuracyMps = .1f, ageMillis = 0L).reliable)
    }

    @Test
    fun rejectsMissingAccuracy() {
        assertFalse(mapper.map(speedMps = 2.5f, accuracyMps = null, ageMillis = 0L).reliable)
    }

    @Test
    fun rejectsStaleFix() {
        assertFalse(mapper.map(speedMps = 2.5f, accuracyMps = .1f, ageMillis = 3_001L).reliable)
    }

    @Test
    fun rejectsFixFromTheFuture() {
        assertFalse(mapper.map(speedMps = 2.5f, accuracyMps = .1f, ageMillis = -1L).reliable)
    }

    @Test
    fun rejectsNonFiniteSpeed() {
        assertFalse(mapper.map(speedMps = Float.NaN, accuracyMps = .1f, ageMillis = 0L).reliable)
        assertFalse(mapper.map(speedMps = Float.POSITIVE_INFINITY, accuracyMps = .1f, ageMillis = 0L).reliable)
    }

    @Test
    fun rejectsNonFiniteAccuracy() {
        assertFalse(mapper.map(speedMps = 2.5f, accuracyMps = Float.NaN, ageMillis = 0L).reliable)
        assertFalse(mapper.map(speedMps = 2.5f, accuracyMps = Float.POSITIVE_INFINITY, ageMillis = 0L).reliable)
    }

    @Test
    fun rejectsUncertaintyTooLargeForConfiguredBand() {
        assertFalse(mapper.map(speedMps = 2.5f, accuracyMps = .5f, ageMillis = 0L).reliable)
    }

    @Test
    fun acceptsConfiguredUncertaintyThreshold() {
        val mapper = LocationSampleMapper(maxSpeedAccuracyMps = .5f)

        assertTrue(mapper.map(speedMps = 2.5f, accuracyMps = .5f, ageMillis = 0L).reliable)
    }

    @Test
    fun rejectsPoorHorizontalAccuracy() {
        val sample = mapper.map(speedMps = 2.5f, accuracyMps = .1f, ageMillis = 0L, horizontalAccuracyMeters = 25f)

        assertFalse(sample.reliable)
    }

    @Test
    fun acceptsGoodHorizontalAccuracy() {
        val sample = mapper.map(speedMps = 2.5f, accuracyMps = .1f, ageMillis = 0L, horizontalAccuracyMeters = 10f)

        assertTrue(sample.reliable)
    }

    @Test
    fun missingHorizontalAccuracyDoesNotDisqualifyTheFix() {
        val sample = mapper.map(speedMps = 2.5f, accuracyMps = .1f, ageMillis = 0L, horizontalAccuracyMeters = null)

        assertTrue(sample.reliable)
    }

    @Test
    fun acceptsConfiguredHorizontalAccuracyThreshold() {
        val mapper = LocationSampleMapper(maxHorizontalAccuracyMeters = 30f)

        assertTrue(
            mapper.map(speedMps = 2.5f, accuracyMps = .1f, ageMillis = 0L, horizontalAccuracyMeters = 25f).reliable,
        )
    }

    @Test
    fun evaluateProducesNoReasonsWhenEverythingIsWithinBounds() {
        val reliability = mapper.evaluate(speedMps = 2.5f, accuracyMps = .1f, ageMillis = 0L)

        assertTrue(reliability.reliable)
        assertEquals(emptyList<dev.fred.pacorun.eventlog.RejectionReason>(), reliability.reasons)
    }

    @Test
    fun evaluateCanProduceMultipleReasonsForTheSameFix() {
        // Both speed AND its accuracy are missing at once -- the spec explicitly calls for a
        // list, not a single value, exactly for this case.
        val reliability = mapper.evaluate(speedMps = null, accuracyMps = null, ageMillis = 0L)

        assertEquals(
            listOf(
                dev.fred.pacorun.eventlog.RejectionReason.MISSING_OR_INVALID_SPEED,
                dev.fred.pacorun.eventlog.RejectionReason.MISSING_OR_INVALID_SPEED_ACCURACY,
            ),
            reliability.reasons,
        )
    }

    @Test
    fun evaluateMatchesMapsExistingReliableBooleanForEveryThreshold() {
        assertEquals(mapper.map(speedMps = 2.5f, accuracyMps = .1f, ageMillis = 0L).reliable, mapper.evaluate(2.5f, .1f, 0L).reliable)
        assertEquals(mapper.map(speedMps = null, accuracyMps = .1f, ageMillis = 0L).reliable, mapper.evaluate(null, .1f, 0L).reliable)
        assertEquals(mapper.map(speedMps = 2.5f, accuracyMps = .5f, ageMillis = 0L).reliable, mapper.evaluate(2.5f, .5f, 0L).reliable)
        assertEquals(mapper.map(speedMps = 2.5f, accuracyMps = .1f, ageMillis = 3_001L).reliable, mapper.evaluate(2.5f, .1f, 3_001L).reliable)
        assertEquals(
            mapper.map(speedMps = 2.5f, accuracyMps = .1f, ageMillis = 0L, horizontalAccuracyMeters = 25f).reliable,
            mapper.evaluate(2.5f, .1f, 0L, 25f).reliable,
        )
    }

    @Test
    fun qualityTierIsOttimaWhenWellWithinHalfEveryThreshold() {
        val reliability = mapper.evaluate(speedMps = 2.5f, accuracyMps = .1f, ageMillis = 0L, horizontalAccuracyMeters = 5f)

        assertEquals(dev.fred.pacorun.eventlog.QualityTier.OTTIMA, mapper.qualityTier(reliability, .1f, 5f))
    }

    @Test
    fun qualityTierIsBuonaWhenReliableButNotWithinHalfThreshold() {
        // accuracyMps = .3f is <= maxSpeedAccuracyMps (.35f) but > half of it (.175f).
        val reliability = mapper.evaluate(speedMps = 2.5f, accuracyMps = .3f, ageMillis = 0L)

        assertEquals(dev.fred.pacorun.eventlog.QualityTier.BUONA, mapper.qualityTier(reliability, .3f, null))
    }

    @Test
    fun qualityTierIsDegradataForExactlyOneThresholdExceededReason() {
        val reliability = mapper.evaluate(speedMps = 2.5f, accuracyMps = .5f, ageMillis = 0L)

        assertEquals(dev.fred.pacorun.eventlog.QualityTier.DEGRADATA, mapper.qualityTier(reliability, .5f, null))
    }

    @Test
    fun qualityTierIsScarsaForTwoOrMoreReasons() {
        val reliability = mapper.evaluate(speedMps = 2.5f, accuracyMps = .5f, ageMillis = 3_001L)

        assertEquals(dev.fred.pacorun.eventlog.QualityTier.SCARSA, mapper.qualityTier(reliability, .5f, null))
    }

    @Test
    fun qualityTierIsScarsaWhenTheSingleReasonIsMissingDataNotJustAThresholdMiss() {
        val reliability = mapper.evaluate(speedMps = null, accuracyMps = .1f, ageMillis = 0L)

        assertEquals(dev.fred.pacorun.eventlog.QualityTier.SCARSA, mapper.qualityTier(reliability, .1f, null))
    }
}
