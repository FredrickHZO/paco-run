package dev.fred.pacorun.service

import android.app.Application
import dev.fred.pacorun.settings.RunPreferences

class TestApplication : Application(), RunSessionHostConfig {
    override fun runPreferencesSource(): RunPreferencesSource = object : RunPreferencesSource {
        override suspend fun current(): RunPreferences = RunPreferences()
    }

    override val notificationTitle: String get() = "Paco Run Test"
    override val gitCommitHash: String get() = "test"
}
