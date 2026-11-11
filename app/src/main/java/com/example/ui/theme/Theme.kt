package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColorScheme = lightColorScheme(
    primary = Leather,
    onPrimary = Color.White,
    primaryContainer = LeatherContainer,
    onPrimaryContainer = OnLeatherContainer,
    secondary = InkSoft,
    onSecondary = Color.White,
    secondaryContainer = PaperDeep,
    onSecondaryContainer = Ink,
    tertiary = Moss,
    tertiaryContainer = MossContainer,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperDim,
    onSurfaceVariant = InkSoft,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F2EA),
    surfaceContainer = PaperDim,
    surfaceContainerHigh = PaperDeep,
    surfaceContainerHighest = Color(0xFFE2D9CA),
    outline = Color(0xFFA89C8C),
    outlineVariant = Outline
)

private val DarkColorScheme = darkColorScheme(
    primary = Copper,
    onPrimary = Color(0xFF3A1508),
    primaryContainer = CopperContainer,
    onPrimaryContainer = Color(0xFFFFDBCC),
    secondary = NightInkSoft,
    secondaryContainer = NightSurfaceHighest,
    onSecondaryContainer = NightInk,
    tertiary = NightMoss,
    background = NightBackground,
    onBackground = NightInk,
    surface = NightBackground,
    onSurface = NightInk,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = NightInkSoft,
    surfaceContainerLowest = Color(0xFF0F0D0B),
    surfaceContainerLow = NightSurface,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceHigh,
    surfaceContainerHighest = NightSurfaceHighest,
    outline = Color(0xFF8C8273),
    outlineVariant = NightOutline
)

/**
 * App theme. On Android 12+ colours come from the wallpaper (Material You dynamic colour);
 * older phones fall back to the built-in paper & leather library palette.
 */
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val colorScheme = when {
        dynamicColor && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S ->
            if (darkTheme) androidx.compose.material3.dynamicDarkColorScheme(context)
            else androidx.compose.material3.dynamicLightColorScheme(context)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
