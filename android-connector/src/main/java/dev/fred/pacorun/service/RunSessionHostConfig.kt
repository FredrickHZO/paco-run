package dev.fred.pacorun.service

interface RunSessionHostConfig {
    fun runPreferencesSource(): RunPreferencesSource
    val notificationTitle: String
    val gitCommitHash: String
}
