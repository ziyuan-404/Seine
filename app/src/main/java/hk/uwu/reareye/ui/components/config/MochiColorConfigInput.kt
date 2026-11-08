package hk.uwu.reareye.ui.components.config

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.config.ConfigItem
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.ConfigType
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.config.rememberRemotePrefsStatusRevision
import hk.uwu.reareye.ui.theme.AppThemeMode
import hk.uwu.reareye.ui.theme.MochiColorSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Text
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.picker.MochiSeedPalette
import ui.feice.mochi.theme.ColorSchemeMode
import ui.feice.mochi.theme.MochiTheme

@Composable
internal fun MochiColorsConfigInput(
    item: ConfigItem,
    prefs: PrefsManager,
    onPreferenceChanged: (ConfigItem) -> Unit,
) {
    val type = item.type as ConfigType.EnumSingleSelect
    val revision = rememberRemotePrefsStatusRevision()
    val mode = remember(revision) { prefs.getInt(ConfigKeys.MODULE_MOCHI_COLOR_MODE, -1) }
    val theme = AppThemeMode.fromValue(prefs.getInt(ConfigKeys.MODULE_THEME_MODE, AppThemeMode.default.value))
    val resolvedMode = MochiColorSettings(mode = mode).resolvedMode(theme)
    val currentMode = when (resolvedMode) {
        ColorSchemeMode.MonetSystem, ColorSchemeMode.MonetLight, ColorSchemeMode.MonetDark -> 0
        ColorSchemeMode.System -> 1
        ColorSchemeMode.Light -> 2
        ColorSchemeMode.Dark -> 3
    }
    val modeOptions = listOf(
        ConfigType.EnumOption(R.string.seine_mochi_mode_monet, 0),
        ConfigType.EnumOption(R.string.seine_mochi_mode_system, 1),
        ConfigType.EnumOption(R.string.seine_mochi_mode_light, 2),
        ConfigType.EnumOption(R.string.seine_mochi_mode_dark, 3),
    )
    MochiListRow(title = stringResource(item.titleRes))
    EnumSingleSelectConfigInput(
        item = item.copy(
            key = ConfigKeys.MODULE_MOCHI_COLOR_MODE,
            titleRes = R.string.seine_mochi_mode,
            type = ConfigType.EnumSingleSelect(currentMode, modeOptions),
        ),
        defaultValue = currentMode,
        options = modeOptions,
        prefsManager = prefs,
        onPreferenceChanged = onPreferenceChanged,
    )
    EnumSingleSelectConfigInput(
        item = item.copy(titleRes = R.string.seine_mochi_algorithm),
        defaultValue = type.defaultValue,
        options = type.options,
        prefsManager = prefs,
        onPreferenceChanged = onPreferenceChanged,
    )
}

@Composable
internal fun MochiSeedConfigInput(
    item: ConfigItem,
    prefs: PrefsManager,
    onPreferenceChanged: (ConfigItem) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val revision = rememberRemotePrefsStatusRevision()
    val theme = AppThemeMode.fromValue(prefs.getInt(ConfigKeys.MODULE_THEME_MODE, AppThemeMode.default.value))
    val settings = remember(revision, theme) {
        MochiColorSettings(
            mode = prefs.getInt(ConfigKeys.MODULE_MOCHI_COLOR_MODE, -1),
            palette = prefs.getInt(ConfigKeys.MODULE_MOCHI_PALETTE_STYLE, 0),
            seed = if (prefs.prefs.contains(ConfigKeys.MODULE_MOCHI_SEED_COLOR)) {
                prefs.getInt(ConfigKeys.MODULE_MOCHI_SEED_COLOR, 0)
            } else null,
        )
    }
    val mode = settings.resolvedMode(theme)
    val monet = mode == ColorSchemeMode.MonetSystem || mode == ColorSchemeMode.MonetLight ||
        mode == ColorSchemeMode.MonetDark
    var saving by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        MochiListRow(title = stringResource(item.titleRes))
        MochiSeedPalette(
            selected = if (monet) null else settings.keyColor(theme),
            style = settings.paletteStyle,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            onSelect = { seed ->
                if (!saving) {
                    saving = true
                    // Keep the current light/dark policy when leaving wallpaper colors.
                    val staticMode = when (mode) {
                        ColorSchemeMode.Light, ColorSchemeMode.MonetLight -> 2
                        ColorSchemeMode.Dark, ColorSchemeMode.MonetDark -> 3
                        else -> 1
                    }
                    scope.launch {
                        val committed = withContext(Dispatchers.IO) {
                            runCatching {
                                prefs.prefs.edit()
                                    .putInt(ConfigKeys.MODULE_MOCHI_SEED_COLOR, seed.toArgb())
                                    .putInt(ConfigKeys.MODULE_MOCHI_COLOR_MODE, staticMode)
                                    .commit()
                            }.getOrDefault(false)
                        }
                        saving = false
                        if (committed) onPreferenceChanged(item)
                        else Toast.makeText(context, R.string.seine_setting_save_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            },
        )
        Text(
            text = stringResource(R.string.seine_mochi_seed_desc),
            color = MochiTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        )
    }
}
