package dev.fred.pacorun.service

import dev.fred.pacorun.eventlog.SpeedEstimatorDiagnostics

interface SpeedEngineDiagnostics {
    fun diagnostics(): SpeedEstimatorDiagnostics
    fun lastAcceptWasMeasurement(): Boolean
}
