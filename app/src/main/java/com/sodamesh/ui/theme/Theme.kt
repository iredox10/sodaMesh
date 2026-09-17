package com.sodamesh.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightScheme = lightColorScheme(
    primary = SodaTeal,
    onPrimary = SodaSurface,
    primaryContainer = SodaTealContainer,
    onPrimaryContainer = SodaTealDark,
    secondary = SodaAmber,
    onSecondary = SodaInk,
    secondaryContainer = SodaAmberContainer,
    onSecondaryContainer = SodaInk,
    surface = SodaSurface,
    onSurface = SodaInk,
    error = SodaError,
)

private val DarkScheme = darkColorScheme(
    primary = SodaTealContainer,
    onPrimary = SodaTealDark,
    primaryContainer = SodaTealContainerDark,
    onPrimaryContainer = SodaTealContainer,
    secondary = SodaAmberContainer,
    onSecondary = SodaAmberContainerDark,
    secondaryContainer = SodaAmberContainerDark,
    onSecondaryContainer = SodaAmberContainer,
    surface = SodaSurfaceDark,
    onSurface = SodaInkDark,
    error = SodaErrorDark,
)

@Composable
fun SodaMeshTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkScheme
        else -> LightScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = SodaTypography,
        content = content,
    )
}
