package dev.fred.pacorun.location

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationReadinessTest {
    @Test
    fun finePermissionAndProviderProduceReady() {
        assertEquals(
            LocationReadiness.READY,
            locationReadiness(fine = true, coarse = true, providerEnabled = true),
        )
    }

    @Test
    fun coarseOnlyIsApproximate() {
        assertEquals(
            LocationReadiness.APPROXIMATE_ONLY,
            locationReadiness(fine = false, coarse = true, providerEnabled = true),
        )
    }

    @Test
    fun disabledProviderWinsAfterPermissions() {
        assertEquals(
            LocationReadiness.LOCATION_OFF,
            locationReadiness(fine = true, coarse = true, providerEnabled = false),
        )
    }
}
