package dev.fred.pacorun.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun PacoRunTheme(
    dynamicColor: Boolean,
    fixedPalette: FixedPalette = FixedPalette.ONE,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    // Dynamic color (Material You) reads the wallpaper-derived system palette, introduced in
    // Android 12 (API 31) -- doesn't exist on older versions, falls back to the fixed palette.
    val supportsDynamicColor = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val colors = when {
        dynamicColor && supportsDynamicColor && darkTheme -> dynamicDarkColorScheme(LocalContext.current)
        dynamicColor && supportsDynamicColor -> dynamicLightColorScheme(LocalContext.current)
        else -> fixedColorScheme(fixedPalette, darkTheme)
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        typography = Typography(),
        content = content,
    )
}
