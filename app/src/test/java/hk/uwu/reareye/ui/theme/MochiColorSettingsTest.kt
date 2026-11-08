package hk.uwu.reareye.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test
import ui.feice.mochi.theme.ColorSchemeMode
import ui.feice.mochi.theme.PaletteStyle

class MochiColorSettingsTest {
    @Test
    fun existingThemeModesKeepTheirAppearanceUntilColorsAreConfigured() {
        AppThemeMode.entries.forEach { theme ->
            assertEquals(theme.colorSchemeMode.name, MochiColorSettings().resolvedMode(theme).name)
        }
    }

    @Test
    fun explicitModeOverridesLegacyAppearance() {
        assertEquals(ColorSchemeMode.MonetSystem, MochiColorSettings(mode = 0).resolvedMode(AppThemeMode.MIUIX_DARK))
        assertEquals(ColorSchemeMode.System, MochiColorSettings(mode = 1).resolvedMode(AppThemeMode.MONET_LIGHT))
        assertEquals(ColorSchemeMode.Light, MochiColorSettings(mode = 2).resolvedMode(AppThemeMode.FLOWMIX_DARK))
        assertEquals(ColorSchemeMode.Dark, MochiColorSettings(mode = 3).resolvedMode(AppThemeMode.MIUIX_LIGHT))
    }

    @Test
    fun paletteUsesDemoAlgorithmsAndHandlesUnknownValues() {
        assertEquals(PaletteStyle.TonalSpot, MochiColorSettings().paletteStyle)
        assertEquals(PaletteStyle.Expressive, MochiColorSettings(palette = 1).paletteStyle)
        assertEquals(PaletteStyle.Vibrant, MochiColorSettings(palette = 2).paletteStyle)
        assertEquals(PaletteStyle.TonalSpot, MochiColorSettings(palette = 100).paletteStyle)
    }
}
