package dev.waterctl.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = AppColors.LightPrimary,
    onPrimary = AppColors.LightOnPrimary,
    primaryContainer = AppColors.LightPrimaryContainer,
    onPrimaryContainer = AppColors.LightOnPrimaryContainer,
    secondary = AppColors.LightSecondary,
    onSecondary = AppColors.LightOnSecondary,
    secondaryContainer = AppColors.LightSecondaryContainer,
    onSecondaryContainer = AppColors.LightOnSecondaryContainer,
    tertiary = AppColors.LightTertiary,
    onTertiary = AppColors.LightOnTertiary,
    tertiaryContainer = AppColors.LightTertiaryContainer,
    onTertiaryContainer = AppColors.LightOnTertiaryContainer,
    error = AppColors.LightError,
    onError = AppColors.LightOnError,
    errorContainer = AppColors.LightErrorContainer,
    onErrorContainer = AppColors.LightOnErrorContainer,
    background = AppColors.LightBackground,
    onBackground = AppColors.LightOnBackground,
    surface = AppColors.LightSurface,
    onSurface = AppColors.LightOnSurface,
    surfaceVariant = AppColors.LightSurfaceVariant,
    onSurfaceVariant = AppColors.LightOnSurfaceVariant,
    outline = AppColors.LightOutline,
    outlineVariant = AppColors.LightOutlineVariant,
    surfaceContainerLowest = AppColors.LightSurfaceContainerLowest,
    surfaceContainerLow = AppColors.LightSurfaceContainerLow,
    surfaceContainer = AppColors.LightSurfaceContainer,
    surfaceContainerHigh = AppColors.LightSurfaceContainerHigh,
    surfaceContainerHighest = AppColors.LightSurfaceContainerHighest,
    inverseSurface = AppColors.LightInverseSurface,
    inverseOnSurface = AppColors.LightInverseOnSurface,
    inversePrimary = AppColors.LightInversePrimary,
)

private val DarkColors = darkColorScheme(
    primary = AppColors.DarkPrimary,
    onPrimary = AppColors.DarkOnPrimary,
    primaryContainer = AppColors.DarkPrimaryContainer,
    onPrimaryContainer = AppColors.DarkOnPrimaryContainer,
    secondary = AppColors.DarkSecondary,
    onSecondary = AppColors.DarkOnSecondary,
    secondaryContainer = AppColors.DarkSecondaryContainer,
    onSecondaryContainer = AppColors.DarkOnSecondaryContainer,
    tertiary = AppColors.DarkTertiary,
    onTertiary = AppColors.DarkOnTertiary,
    tertiaryContainer = AppColors.DarkTertiaryContainer,
    onTertiaryContainer = AppColors.DarkOnTertiaryContainer,
    error = AppColors.DarkError,
    onError = AppColors.DarkOnError,
    errorContainer = AppColors.DarkErrorContainer,
    onErrorContainer = AppColors.DarkOnErrorContainer,
    background = AppColors.DarkBackground,
    onBackground = AppColors.DarkOnBackground,
    surface = AppColors.DarkSurface,
    onSurface = AppColors.DarkOnSurface,
    surfaceVariant = AppColors.DarkSurfaceVariant,
    onSurfaceVariant = AppColors.DarkOnSurfaceVariant,
    outline = AppColors.DarkOutline,
    outlineVariant = AppColors.DarkOutlineVariant,
    surfaceContainerLowest = AppColors.DarkSurfaceContainerLowest,
    surfaceContainerLow = AppColors.DarkSurfaceContainerLow,
    surfaceContainer = AppColors.DarkSurfaceContainer,
    surfaceContainerHigh = AppColors.DarkSurfaceContainerHigh,
    surfaceContainerHighest = AppColors.DarkSurfaceContainerHighest,
    inverseSurface = AppColors.DarkInverseSurface,
    inverseOnSurface = AppColors.DarkInverseOnSurface,
    inversePrimary = AppColors.DarkInversePrimary,
)

/**
 * 主题：跟随系统深浅色；Android 12+ 使用壁纸动态取色（Material You），
 * 更低版本回落到设计稿的蓝色基线色板。
 */
@Composable
fun WaterCtlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> DarkColors
        else -> LightColors
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
