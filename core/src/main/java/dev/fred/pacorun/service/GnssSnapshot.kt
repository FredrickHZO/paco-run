package dev.fred.pacorun.service

import dev.fred.pacorun.eventlog.SatelliteGeometry

data class GnssSnapshot(
    val satellitesUsedInFix: Int,
    val cn0MinDbHz: Float?,
    val cn0AvgDbHz: Float?,
    val cn0MaxDbHz: Float?,
    val geometry: List<SatelliteGeometry>,
)
