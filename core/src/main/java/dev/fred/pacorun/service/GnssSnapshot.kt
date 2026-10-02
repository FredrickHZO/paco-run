package dev.fred.pacorun.service

import dev.fred.pacorun.eventlog.SatelliteGeometry
import dev.fred.pacorun.eventlog.SatelliteMeasurement

data class GnssSnapshot(
    val satellitesUsedInFix: Int,
    val cn0MinDbHz: Float?,
    val cn0AvgDbHz: Float?,
    val cn0MaxDbHz: Float?,
    val geometry: List<SatelliteGeometry>,
)

// G12 phase 1: raw per-satellite Doppler collection, parallel to GnssSnapshot/GnssStatus above but
// sourced from GnssMeasurementsEvent instead -- a separate, unrelated Android callback with its own
// support/availability status per device.
data class GnssMeasurementSnapshot(
    val clockBiasNanos: Double?,
    val clockBiasUncertaintyNanos: Double?,
    val clockDriftNanosPerSecond: Double?,
    val clockDriftUncertaintyNanosPerSecond: Double?,
    val measurements: List<SatelliteMeasurement>,
)
