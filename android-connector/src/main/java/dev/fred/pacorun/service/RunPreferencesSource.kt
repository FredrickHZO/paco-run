package dev.fred.pacorun.service

import dev.fred.pacorun.settings.RunPreferences

interface RunPreferencesSource {
    suspend fun current(): RunPreferences
}
