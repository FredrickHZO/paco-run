package dev.fred.pacorun.ui.home

import dev.fred.pacorun.location.LocationReadiness
import dev.fred.pacorun.settings.RunPreferences

data class HomeUiState(
    val preferences: RunPreferences,
    val locationReadiness: LocationReadiness,
)
