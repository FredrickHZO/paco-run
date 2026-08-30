package dev.fred.pacorun.location

enum class LocationReadiness { READY, LOCATION_OFF, PERMISSION_NEEDED, APPROXIMATE_ONLY }

fun locationReadiness(fine: Boolean, coarse: Boolean, providerEnabled: Boolean) = when {
    !fine && coarse -> LocationReadiness.APPROXIMATE_ONLY
    !fine -> LocationReadiness.PERMISSION_NEEDED
    !providerEnabled -> LocationReadiness.LOCATION_OFF
    else -> LocationReadiness.READY
}
