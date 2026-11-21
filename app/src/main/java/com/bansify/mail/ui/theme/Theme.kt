package com.bansify.mail.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.bansify.mail.data.model.ThemePreference

private val LightColorScheme = lightColorScheme(
    primary = MailPrimaryLight,
    onPrimary = MailOnPrimaryLight,
    primaryContainer = MailPrimaryContainerLight,
    onPrimaryContainer = MailOnPrimaryContainerLight,
    secondary = MailSecondaryLight,
    onSecondary = MailOnSecondaryLight,
    secondaryContainer = MailSecondaryContainerLight,
    onSecondaryContainer = MailOnSecondaryContainerLight,
    tertiary = MailTertiaryLight,
    onTertiary = MailOnTertiaryLight,
    tertiaryContainer = MailTertiaryContainerLight,
    onTertiaryContainer = MailOnTertiaryContainerLight,
    error = MailErrorLight,
    onError = MailOnErrorLight,
    errorContainer = MailErrorContainerLight,
    onErrorContainer = MailOnErrorContainerLight,
    background = MailBackgroundLight,
    onBackground = MailOnBackgroundLight,
    surface = MailSurfaceLight,
    onSurface = MailOnSurfaceLight,
    surfaceVariant = MailSurfaceVariantLight,
    onSurfaceVariant = MailOnSurfaceVariantLight,
    outline = MailOutlineLight,
    outlineVariant = MailOutlineVariantLight,
    surfaceContainerLow = MailSurfaceContainerLowLight,
    surfaceContainer = MailSurfaceContainerLight,
    surfaceContainerHigh = MailSurfaceContainerHighLight,
    surfaceContainerHighest = MailSurfaceContainerHighestLight
)

private val DarkColorScheme = darkColorScheme(
    primary = MailPrimaryDark,
    onPrimary = MailOnPrimaryDark,
    primaryContainer = MailPrimaryContainerDark,
    onPrimaryContainer = MailOnPrimaryContainerDark,
    secondary = MailSecondaryDark,
    onSecondary = MailOnSecondaryDark,
    secondaryContainer = MailSecondaryContainerDark,
    onSecondaryContainer = MailOnSecondaryContainerDark,
    tertiary = MailTertiaryDark,
    onTertiary = MailOnTertiaryDark,
    tertiaryContainer = MailTertiaryContainerDark,
    onTertiaryContainer = MailOnTertiaryContainerDark,
    error = MailErrorDark,
    onError = MailOnErrorDark,
    errorContainer = MailErrorContainerDark,
    onErrorContainer = MailOnErrorContainerDark,
    background = MailBackgroundDark,
    onBackground = MailOnBackgroundDark,
    surface = MailSurfaceDark,
    onSurface = MailOnSurfaceDark,
    surfaceVariant = MailSurfaceVariantDark,
    onSurfaceVariant = MailOnSurfaceVariantDark,
    outline = MailOutlineDark,
    outlineVariant = MailOutlineVariantDark,
    surfaceContainerLow = MailSurfaceContainerLowDark,
    surfaceContainer = MailSurfaceContainerDark,
    surfaceContainerHigh = MailSurfaceContainerHighDark,
    surfaceContainerHighest = MailSurfaceContainerHighestDark
)

val MailShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun MailAppTheme(
    themePreference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit
) {
    val darkTheme = when (themePreference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }

    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MailTypography,
        shapes = MailShapes,
        content = content
    )
}
