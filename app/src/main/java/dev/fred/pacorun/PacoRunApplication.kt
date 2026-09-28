package dev.fred.pacorun

import android.app.Application
import dev.fred.pacorun.service.RunPreferencesSource
import dev.fred.pacorun.service.RunSessionHostConfig
import dev.fred.pacorun.settings.RunPreferencesStore
import kotlinx.coroutines.flow.first

class PacoRunApplication : Application(), RunSessionHostConfig {
    override fun runPreferencesSource(): RunPreferencesSource {
        val store = RunPreferencesStore(this)
        return object : RunPreferencesSource {
            override suspend fun current() = store.preferences.first()
        }
    }

    override val notificationTitle: String get() = "Paco Run"
    override val gitCommitHash: String get() = BuildConfig.GIT_COMMIT_HASH
}
