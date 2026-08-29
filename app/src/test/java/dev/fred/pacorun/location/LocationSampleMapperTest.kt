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
}
