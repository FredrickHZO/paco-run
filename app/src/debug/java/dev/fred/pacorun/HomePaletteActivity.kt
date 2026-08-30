package dev.fred.pacorun

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import dev.fred.pacorun.location.LocationReadiness
import dev.fred.pacorun.settings.RoutineMode
import dev.fred.pacorun.settings.RunPreferences
import dev.fred.pacorun.ui.home.HomeScreen
import dev.fred.pacorun.ui.home.HomeUiState
import dev.fred.pacorun.ui.theme.FixedPalette
import dev.fred.pacorun.ui.theme.PacoRunTheme

/** Debug-only launcher for comparing identical Home renders across theme inputs. */
class HomePaletteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val palette = runCatching {
            FixedPalette.valueOf(intent.getStringExtra("palette") ?: FixedPalette.ONE.name)
        }.getOrDefault(FixedPalette.ONE)
        val dynamic = intent.getBooleanExtra("dynamic", false)
        setContent {
            PacoRunTheme(
                dynamicColor = dynamic,
                fixedPalette = palette,
                darkTheme = if (intent.hasExtra("dark")) {
                    intent.getBooleanExtra("dark", false)
                } else {
                    isSystemInDarkTheme()
                },
            ) {
                HomeScreen(
                    HomeUiState(
                        RunPreferences(mode = RoutineMode.WALK_RUN),
                        LocationReadiness.READY,
                    ),
                    onStart = {},
                    onEdit = {},
                )
            }
        }
    }
}
