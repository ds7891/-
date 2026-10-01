package me.rerere.rikkahub.ui.theme.presets

import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.theme.PresetTheme

val SpringThemePreset by lazy {
    PresetTheme(
        id = "spring",
        name = {
            Text(stringResource(id = R.string.theme_name_spring))
        },
        standardLight = lightScheme,
        standardDark = darkScheme,
    )
}

//region 淡绿色主题（Light Green）
private val primaryLight = Color(0xFF4CAF50)
private val onPrimaryLight = Color(0xFFFFFFFF)
private val primaryContainerLight = Color(0xFFC8E6C9)
private val onPrimaryContainerLight = Color(0xFF0B3D0F)
private val secondaryLight = Color(0xFF52634F)
private val onSecondaryLight = Color(0xFFFFFFFF)
private val secondaryContainerLight = Color(0xFFD6E8D3)
private val onSecondaryContainerLight = Color(0xFF101F10)
private val tertiaryLight = Color(0xFF386A5C)
private val onTertiaryLight = Color(0xFFFFFFFF)
private val tertiaryContainerLight = Color(0xFFBCECE0)
private val onTertiaryContainerLight = Color(0xFF00201A)
private val errorLight = Color(0xFFBA1A1A)
private val onErrorLight = Color(0xFFFFFFFF)
private val errorContainerLight = Color(0xFFFFDAD6)
private val onErrorContainerLight = Color(0xFF93000A)
private val backgroundLight = Color(0xFFF7FBF4)
private val onBackgroundLight = Color(0xFF191D17)
private val surfaceLight = Color(0xFFF7FBF4)
private val onSurfaceLight = Color(0xFF191D17)
private val surfaceVariantLight = Color(0xFFDDE5DB)
private val onSurfaceVariantLight = Color(0xFF414941)
private val outlineLight = Color(0xFF717971)
private val outlineVariantLight = Color(0xFFC1C9BF)
private val scrimLight = Color(0xFF000000)
private val inverseSurfaceLight = Color(0xFF2E322C)
private val inverseOnSurfaceLight = Color(0xFFEFF2EA)
private val inversePrimaryLight = Color(0xFF8CD98F)
private val surfaceDimLight = Color(0xFFD7DBD3)
private val surfaceBrightLight = Color(0xFFF7FBF4)
private val surfaceContainerLowestLight = Color(0xFFFFFFFF)
private val surfaceContainerLowLight = Color(0xFFF1F5EE)
private val surfaceContainerLight = Color(0xFFEBEFE8)
private val surfaceContainerHighLight = Color(0xFFE5EAE2)
private val surfaceContainerHighestLight = Color(0xFFDFE4DC)

private val primaryDark = Color(0xFF8CD98F)
private val onPrimaryDark = Color(0xFF00390A)
private val primaryContainerDark = Color(0xFF1B5E20)
private val onPrimaryContainerDark = Color(0xFFC8E6C9)
private val secondaryDark = Color(0xFFBACCB5)
private val onSecondaryDark = Color(0xFF253423)
private val secondaryContainerDark = Color(0xFF3B4B39)
private val onSecondaryContainerDark = Color(0xFFD6E8D3)
private val tertiaryDark = Color(0xFFA0D0C0)
private val onTertiaryDark = Color(0xFF00382C)
private val tertiaryContainerDark = Color(0xFF1E4F42)
private val onTertiaryContainerDark = Color(0xFFBCECE0)
private val errorDark = Color(0xFFFFB4AB)
private val onErrorDark = Color(0xFF690005)
private val errorContainerDark = Color(0xFF93000A)
private val onErrorContainerDark = Color(0xFFFFDAD6)
private val backgroundDark = Color(0xFF111411)
private val onBackgroundDark = Color(0xFFDFE4DC)
private val surfaceDark = Color(0xFF111411)
private val onSurfaceDark = Color(0xFFDFE4DC)
private val surfaceVariantDark = Color(0xFF414941)
private val onSurfaceVariantDark = Color(0xFFC1C9BF)
private val outlineDark = Color(0xFF8B938A)
private val outlineVariantDark = Color(0xFF414941)
private val scrimDark = Color(0xFF000000)
private val inverseSurfaceDark = Color(0xFFDFE4DC)
private val inverseOnSurfaceDark = Color(0xFF2E322C)
private val inversePrimaryDark = Color(0xFF2E7D32)
private val surfaceDimDark = Color(0xFF111411)
private val surfaceBrightDark = Color(0xFF373A35)
private val surfaceContainerLowestDark = Color(0xFF0C0F0C)
private val surfaceContainerLowDark = Color(0xFF191D17)
private val surfaceContainerDark = Color(0xFF1D211C)
private val surfaceContainerHighDark = Color(0xFF272B26)
private val surfaceContainerHighestDark = Color(0xFF323631)
//endregion

private val lightScheme = lightColorScheme(
    primary = primaryLight,
    onPrimary = onPrimaryLight,
    primaryContainer = primaryContainerLight,
    onPrimaryContainer = onPrimaryContainerLight,
    secondary = secondaryLight,
    onSecondary = onSecondaryLight,
    secondaryContainer = secondaryContainerLight,
    onSecondaryContainer = onSecondaryContainerLight,
    tertiary = tertiaryLight,
    onTertiary = onTertiaryLight,
    tertiaryContainer = tertiaryContainerLight,
    onTertiaryContainer = onTertiaryContainerLight,
    error = errorLight,
    onError = onErrorLight,
    errorContainer = errorContainerLight,
    onErrorContainer = onErrorContainerLight,
    background = backgroundLight,
    onBackground = onBackgroundLight,
    surface = surfaceLight,
    onSurface = onSurfaceLight,
    surfaceVariant = surfaceVariantLight,
    onSurfaceVariant = onSurfaceVariantLight,
    outline = outlineLight,
    outlineVariant = outlineVariantLight,
    scrim = scrimLight,
    inverseSurface = inverseSurfaceLight,
    inverseOnSurface = inverseOnSurfaceLight,
    inversePrimary = inversePrimaryLight,
    surfaceDim = surfaceDimLight,
    surfaceBright = surfaceBrightLight,
    surfaceContainerLowest = surfaceContainerLowestLight,
    surfaceContainerLow = surfaceContainerLowLight,
    surfaceContainer = surfaceContainerLight,
    surfaceContainerHigh = surfaceContainerHighLight,
    surfaceContainerHighest = surfaceContainerHighestLight,
)

private val darkScheme = darkColorScheme(
    primary = primaryDark,
    onPrimary = onPrimaryDark,
    primaryContainer = primaryContainerDark,
    onPrimaryContainer = onPrimaryContainerDark,
    secondary = secondaryDark,
    onSecondary = onSecondaryDark,
    secondaryContainer = secondaryContainerDark,
    onSecondaryContainer = onSecondaryContainerDark,
    tertiary = tertiaryDark,
    onTertiary = onTertiaryDark,
    tertiaryContainer = tertiaryContainerDark,
    onTertiaryContainer = onTertiaryContainerDark,
    error = errorDark,
    onError = onErrorDark,
    errorContainer = errorContainerDark,
    onErrorContainer = onErrorContainerDark,
    background = backgroundDark,
    onBackground = onBackgroundDark,
    surface = surfaceDark,
    onSurface = onSurfaceDark,
    surfaceVariant = surfaceVariantDark,
    onSurfaceVariant = onSurfaceVariantDark,
    outline = outlineDark,
    outlineVariant = outlineVariantDark,
    scrim = scrimDark,
    inverseSurface = inverseSurfaceDark,
    inverseOnSurface = inverseOnSurfaceDark,
    inversePrimary = inversePrimaryDark,
    surfaceDim = surfaceDimDark,
    surfaceBright = surfaceBrightDark,
    surfaceContainerLowest = surfaceContainerLowestDark,
    surfaceContainerLow = surfaceContainerLowDark,
    surfaceContainer = surfaceContainerDark,
    surfaceContainerHigh = surfaceContainerHighDark,
    surfaceContainerHighest = surfaceContainerHighestDark,
)
