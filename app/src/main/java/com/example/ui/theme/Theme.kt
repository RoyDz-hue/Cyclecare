package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = RoseLight,
    onPrimary = Color.White,
    primaryContainer = RoseDark,
    onPrimaryContainer = RoseContainer,
    secondary = AmberSecondary,
    secondaryContainer = AmberContainer,
    background = Color(0xFF1A1114),
    surface = Color(0xFF22191C),
    surfaceVariant = Color(0xFF332429),
    onBackground = Color(0xFFFBECEF),
    onSurface = Color(0xFFFBECEF)
)

private val LightColorScheme = lightColorScheme(
    primary = RosePrimary,
    onPrimary = Color.White,
    primaryContainer = RoseContainer,
    onPrimaryContainer = OnRoseContainer,
    secondary = AmberSecondary,
    secondaryContainer = AmberContainer,
    onSecondaryContainer = OnAmberContainer,
    tertiary = PurpleTertiary,
    tertiaryContainer = PurpleContainer,
    background = BackgroundWarm,
    surface = SurfaceWarm,
    surfaceVariant = SurfaceVariantWarm,
    onBackground = NeutralDark,
    onSurface = NeutralDark,
    onSurfaceVariant = NeutralMedium
)

@Composable
fun CycleCareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Set to false to preserve curated CycleCare rose aesthetic
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
