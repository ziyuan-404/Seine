package hk.uwu.reareye.ui.theme

import android.app.Activity
import androidx.annotation.StringRes
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import hk.uwu.reareye.R
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import ui.feice.mochi.theme.ColorSchemeMode as MochiColorSchemeMode
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.theme.animateMochiColorScheme
import ui.feice.mochi.theme.PaletteStyle
import ui.feice.mochi.theme.ThemeController as MochiThemeController

enum class AppThemeMode(
    val value: Int,
    @param:StringRes val titleRes: Int,
    val colorSchemeMode: ColorSchemeMode,
    val usesMiuix: Boolean = false,
) {
    MIUIX_SYSTEM(
        value = 3,
        titleRes = R.string.module_theme_mode_miuix_system,
        colorSchemeMode = ColorSchemeMode.System,
    ),
    MIUIX_LIGHT(
        value = 0,
        titleRes = R.string.module_theme_mode_miuix_light,
        colorSchemeMode = ColorSchemeMode.Light,
    ),
    MIUIX_DARK(
        value = 1,
        titleRes = R.string.module_theme_mode_miuix_dark,
        colorSchemeMode = ColorSchemeMode.Dark,
    ),
    MONET_SYSTEM(
        value = 2,
        titleRes = R.string.module_theme_mode_monet_system,
        colorSchemeMode = ColorSchemeMode.MonetSystem,
    ),
    MONET_LIGHT(
        value = 4,
        titleRes = R.string.module_theme_mode_monet_light,
        colorSchemeMode = ColorSchemeMode.MonetLight,
    ),
    MONET_DARK(
        value = 5,
        titleRes = R.string.module_theme_mode_monet_dark,
        colorSchemeMode = ColorSchemeMode.MonetDark,
    ),
    FLOWMIX_SYSTEM(6, R.string.module_theme_mode_flowmix_system, ColorSchemeMode.System, true),
    FLOWMIX_LIGHT(7, R.string.module_theme_mode_flowmix_light, ColorSchemeMode.Light, true),
    FLOWMIX_DARK(8, R.string.module_theme_mode_flowmix_dark, ColorSchemeMode.Dark, true);

    fun isDark(systemDark: Boolean): Boolean {
        return when (this) {
            MIUIX_SYSTEM, FLOWMIX_SYSTEM -> systemDark
            MIUIX_LIGHT, FLOWMIX_LIGHT -> false
            MIUIX_DARK, FLOWMIX_DARK -> true
            MONET_SYSTEM -> systemDark
            MONET_LIGHT -> false
            MONET_DARK -> true
        }
    }

    companion object {
        val default = MIUIX_SYSTEM
        val selectableEntries = entries

        fun fromValue(value: Int): AppThemeMode {
            return entries.firstOrNull { it.value == value } ?: default
        }
    }
}

@Composable
@ReadOnlyComposable
fun isAppInDarkTheme(themeMode: AppThemeMode): Boolean {
    return themeMode.isDark(isSystemInDarkTheme())
}

internal val LocalSeineMiuixStyle = staticCompositionLocalOf { false }

data class MochiColorSettings(
    val mode: Int = -1,
    val palette: Int = 0,
    val seed: Int? = null,
) {
    fun resolvedMode(theme: AppThemeMode): MochiColorSchemeMode = when (mode) {
        0 -> MochiColorSchemeMode.MonetSystem
        1 -> MochiColorSchemeMode.System
        2 -> MochiColorSchemeMode.Light
        3 -> MochiColorSchemeMode.Dark
        else -> MochiColorSchemeMode.valueOf(theme.colorSchemeMode.name)
    }

    val paletteStyle: PaletteStyle
        get() = when (palette) {
            1 -> PaletteStyle.Expressive
            2 -> PaletteStyle.Vibrant
            else -> PaletteStyle.TonalSpot
        }

    fun keyColor(theme: AppThemeMode): Color =
        seed?.let(::Color) ?: if (theme.usesMiuix) Color(0xFF684D77) else Color(0xFF67539F)
}

@Composable
fun AppTheme(
    themeMode: AppThemeMode = AppThemeMode.default,
    colorSettings: MochiColorSettings = MochiColorSettings(),
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val mode = colorSettings.resolvedMode(themeMode)
    val darkTheme = when (mode) {
        MochiColorSchemeMode.Dark, MochiColorSchemeMode.MonetDark -> true
        MochiColorSchemeMode.Light, MochiColorSchemeMode.MonetLight -> false
        else -> isSystemInDarkTheme()
    }
    val keyColor = colorSettings.keyColor(themeMode)
    val controller = remember(themeMode, mode, keyColor, darkTheme) {
        if (themeMode.usesMiuix) {
            val miuixMode = if (darkTheme) ColorSchemeMode.MonetDark else ColorSchemeMode.MonetLight
            ThemeController(colorSchemeMode = miuixMode, keyColor = keyColor)
        } else ThemeController(ColorSchemeMode.valueOf(mode.name), keyColor = keyColor)
    }
    val mochiController = remember(mode, keyColor, colorSettings.paletteStyle) {
        MochiThemeController(
            colorSchemeMode = mode,
            keyColor = keyColor,
            paletteStyle = colorSettings.paletteStyle,
        )
    }

    MiuixTheme(controller) {
        LaunchedEffect(darkTheme) {
            val window = (context as? Activity)?.window ?: return@LaunchedEffect
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
        val palette = mochiController.currentColorScheme()
        val colors = if (themeMode.usesMiuix && !darkTheme) palette.copy(
            background = Color(0xFFFCF6FB),
            surface = Color(0xFFFCF6FB),
            surfaceContainerLowest = Color(0xFFE7DFE8),
            surfaceContainerLow = Color(0xFFE7DFE8),
            surfaceContainer = Color(0xFFFAF4FA),
            surfaceContainerHigh = Color(0xFFE7DFE8),
            surfaceContainerHighest = Color(0xFFE1D6E4),
        ) else palette
        val animatedColors = animateMochiColorScheme(colors)
        val controlColors = MiuixTheme.colorScheme.copy(
            primary = animatedColors.primary,
            onPrimary = animatedColors.onPrimary,
            primaryVariant = animatedColors.primary,
            onPrimaryVariant = animatedColors.primaryContainer,
            primaryContainer = animatedColors.primaryContainer,
            onPrimaryContainer = animatedColors.onPrimaryContainer,
            disabledPrimary = animatedColors.primary.copy(alpha = 0.38f),
            disabledPrimaryButton = animatedColors.primary.copy(alpha = 0.38f),
            disabledPrimarySlider = animatedColors.primary.copy(alpha = 0.38f),
        )
        MiuixTheme(colors = controlColors) {
            MochiTheme(colors = animatedColors, systemBarAppearance = false) {
                CompositionLocalProvider(LocalSeineMiuixStyle provides themeMode.usesMiuix, content = content)
            }
        }
    }
}
