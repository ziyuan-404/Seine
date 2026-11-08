package hk.uwu.reareye.ui.components.config

import android.annotation.SuppressLint
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.components.SeineMenuDefaults
import hk.uwu.reareye.ui.components.rememberPackageIconBitmap
import hk.uwu.reareye.ui.config.ConfigCategory
import hk.uwu.reareye.ui.config.ConfigCategoryIcon
import hk.uwu.reareye.ui.config.ConfigGroup
import hk.uwu.reareye.ui.config.ConfigItem
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.ConfigNode
import hk.uwu.reareye.ui.config.ConfigType
import hk.uwu.reareye.ui.config.ModuleSettingsController
import hk.uwu.reareye.ui.config.RearNotificationDefaults
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.config.StoreApiProvider
import hk.uwu.reareye.ui.config.normalizeRearStoreCustomDomainInput
import hk.uwu.reareye.ui.config.rememberRemotePrefsStatusRevision
import hk.uwu.reareye.ui.config.validateRearStoreCustomDomain
import kotlin.math.roundToInt
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.Switch
import ui.feice.mochi.basic.MochiDropdown
import ui.feice.mochi.basic.MochiItem
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListLeadingStyle
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.theme.LocalContentColor

@Composable
fun ConfigNodeRow(
    node: ConfigNode,
    prefsManager: PrefsManager,
    onOpenCategory: (ConfigCategory) -> Unit,
    onOpenAppList: (ConfigItem) -> Unit,
    onOpenManager: (ConfigItem) -> Unit,
    onPreferenceChanged: (ConfigItem) -> Unit = {},
) {
    when (node) {
        is ConfigCategory -> ConfigCategoryNodeRow(
            category = node,
            onClick = { onOpenCategory(node) }
        )

        is ConfigGroup -> Unit

        is ConfigItem -> ConfigItemNodeRow(
            item = node,
            prefsManager = prefsManager,
            onOpenAppList = onOpenAppList,
            onOpenManager = onOpenManager,
            onPreferenceChanged = onPreferenceChanged,
        )
    }
}

@Composable
fun ConfigCategoryNodeRow(category: ConfigCategory, onClick: () -> Unit) {
    MochiListRow(
        title = stringResource(category.titleRes),
        subtitle = category.descriptionRes?.let { stringResource(it) },
        chevron = true,
        leadingStyle = MochiListLeadingStyle.Symbol,
        leading = category.icon?.let { icon ->
            {
                ConfigCategoryStartIcon(icon = icon)
            }
        },
        onClick = onClick
    )
}

@Composable
private fun ConfigCategoryStartIcon(icon: ConfigCategoryIcon) {
    when (icon) {
        is ConfigCategoryIcon.Compose -> {
            Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon.imageVector,
                    contentDescription = null,
                    tint = LocalContentColor.current,
                    modifier = Modifier.size(22.dp)
                )
            }
        }

        is ConfigCategoryIcon.Package -> {
            val context = LocalContext.current
            val imageBitmap = rememberPackageIconBitmap(
                packageManager = context.packageManager,
                packageName = icon.packageName,
            )

            if (imageBitmap != null) {
                Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
                    Image(
                        bitmap = imageBitmap,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(28.dp))
            }
        }
    }
}

@Composable
fun ConfigItemNodeRow(
    item: ConfigItem,
    prefsManager: PrefsManager,
    onOpenAppList: (ConfigItem) -> Unit,
    onOpenManager: (ConfigItem) -> Unit,
    onPreferenceChanged: (ConfigItem) -> Unit = {},
) {
    if (item.key == ConfigKeys.MODULE_MOCHI_PALETTE_STYLE) {
        MochiColorsConfigInput(item, prefsManager, onPreferenceChanged)
        return
    }
    item.type.RenderInput(
        item = item,
        prefsManager = prefsManager,
        onOpenAppList = onOpenAppList,
        onOpenManager = onOpenManager,
        onPreferenceChanged = onPreferenceChanged,
    )
}

@Composable
fun BooleanConfigInput(
    item: ConfigItem,
    defaultValue: Boolean,
    prefsManager: PrefsManager,
    onPreferenceChanged: (ConfigItem) -> Unit = {},
) {
    val context = LocalContext.current
    if (item.key == ConfigKeys.REAR_NOTIFICATION_PRIVACY) {
        NativeNotificationPrivacyInput(item)
        return
    }
    if (item.key == ConfigKeys.REAR_APP_FACE_AUTH) {
        FaceAuthConfigInput(item, defaultValue, prefsManager, onPreferenceChanged)
        return
    }
    val checked = remember(item.key) {
        mutableStateOf(prefsManager.getBoolean(item.key, defaultValue))
    }

    ConfigSwitchRow(
        title = stringResource(item.titleRes),
        summary = item.descriptionRes?.let { stringResource(it) },
        checked = checked.value,
        onCheckedChange = {
            checked.value = it
            prefsManager.putBoolean(item.key, it)
            if (item.key == ConfigKeys.MODULE_HIDE_LAUNCHER_ENTRY) {
                ModuleSettingsController.syncLauncherEntryVisibility(context, hidden = it)
            }
            onPreferenceChanged(item)
        }
    )
}

@Composable
internal fun ConfigSwitchRow(
    title: String,
    checked: Boolean,
    summary: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    MochiListRow(
        title = title,
        subtitle = summary,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        },
    )
}

@Composable
fun AppListConfigInput(
    item: ConfigItem,
    defaultValues: Set<String>,
    prefsManager: PrefsManager,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    val remotePrefsStatusRevision = rememberRemotePrefsStatusRevision()
    var selectedCount by remember(item.key) { mutableIntStateOf(0) }
    var selectionReady by remember(item.key) { mutableStateOf(false) }

    LaunchedEffect(item.key, remotePrefsStatusRevision) {
        val remoteReady = withContext(Dispatchers.IO) { prefsManager.isRemoteReady() }
        if (!remoteReady) {
            selectionReady = false
            return@LaunchedEffect
        }
        selectedCount = withContext(Dispatchers.IO) {
            val selected = prefsManager.getStringSet(item.key, defaultValues)
            if (item.key == ConfigKeys.REAR_NOTIFICATION_SYNC_APPS) {
                RearNotificationDefaults.resolve(selected, context.packageManager).size
            } else selected.size
        }
        selectionReady = true
    }

    val selectedSummary = if (selectionReady) {
        stringResource(R.string.config_selected_apps, selectedCount)
    } else {
        stringResource(R.string.rear_widget_loading_data)
    }
    val description = item.descriptionRes?.let { stringResource(it) }
    val summary = if (description.isNullOrBlank()) selectedSummary else "$description\n$selectedSummary"
    MochiListRow(
        title = stringResource(item.titleRes),
        subtitle = summary,
        chevron = true,
        onClick = onClick
    )
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun MaskMultiSelectConfigInput(
    item: ConfigItem,
    defaultValue: Int,
    options: List<ConfigType.MaskOption>,
    prefsManager: PrefsManager,
    onPreferenceChanged: (ConfigItem) -> Unit = {},
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val popupScope = rememberCoroutineScope()
    val showModePopup = remember(item.key) { mutableStateOf(false) }
    var selectedMask by remember(item.key) {
        mutableIntStateOf(prefsManager.getInt(item.key, defaultValue))
    }
    val optionTitles = remember(item.key, options, configuration) {
        options.map { context.getString(it.titleRes) }
    }

    val selectedLabels = options
        .mapIndexedNotNull { index, option ->
            if ((selectedMask and option.maskValue) != 0) optionTitles[index] else null
        }

    val selectedSummary = if (selectedLabels.isEmpty()) {
        stringResource(R.string.lyric_display_mode_none)
    } else {
        selectedLabels.joinToString(separator = " / ")
    }
    val description = item.descriptionRes?.let { stringResource(it) }
    ConfigDropdownRow(
        title = stringResource(item.titleRes),
        summary = description,
        currentLabel = selectedSummary,
        labels = optionTitles,
        checkedIndices = options.indices.filter { (selectedMask and options[it].maskValue) != 0 }.toSet(),
        expanded = showModePopup.value,
        onExpandedChange = { showModePopup.value = it },
        onSelected = { index ->
            popupScope.launch {
                withFrameNanos { }
                selectedMask = selectedMask xor options[index].maskValue
                prefsManager.putInt(item.key, selectedMask)
                onPreferenceChanged(item)
            }
        },
    )
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun DensityDpiConfigInput(
    item: ConfigItem,
    defaultValue: Int,
    prefsManager: PrefsManager,
    onPreferenceChanged: (ConfigItem) -> Unit = {},
) {
    var value by remember(item.key) { mutableIntStateOf(prefsManager.getInt(item.key, defaultValue)) }
    val remoteRevision = rememberRemotePrefsStatusRevision()
    // A preference write also increments remoteRevision. Reading an older service
    // snapshot back during a drag used to snap the slider back and overwrite DPI.
    var edited by remember(item.key) { mutableStateOf(false) }
    LaunchedEffect(remoteRevision) {
        if (!edited && prefsManager.isRemoteReady()) value = prefsManager.getInt(item.key, defaultValue)
    }
    LaunchedEffect(value, edited) {
        if (edited) {
            kotlinx.coroutines.delay(300L)
            prefsManager.putInt(item.key, value)
            onPreferenceChanged(item)
        }
    }
    fun save(next: Int) {
        edited = true
        value = next
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(stringResource(item.titleRes), fontSize = 17.sp)
        item.descriptionRes?.let {
            Text(stringResource(it), color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp))
        }
        ConfigSwitchRow(
            title = stringResource(R.string.rear_app_dpi_system),
            checked = value == 0,
            onCheckedChange = { save(if (it) 0 else defaultValue) },
        )
        if (value != 0) {
            Text(stringResource(R.string.rear_app_dpi_current, value))
            Slider(
                value = value.coerceIn(120, 960).toFloat(),
                valueRange = 120f..960f,
                steps = 83,
                onValueChange = { raw ->
                    val next = (raw / 10f).roundToInt().coerceIn(12, 96) * 10
                    if (next != value) save(next)
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }
        Button(onClick = { save(defaultValue) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(stringResource(R.string.rear_app_dpi_reset))
        }
    }
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun EnumSingleSelectConfigInput(
    item: ConfigItem,
    defaultValue: Int,
    options: List<ConfigType.EnumOption>,
    prefsManager: PrefsManager,
    onPreferenceChanged: (ConfigItem) -> Unit = {},
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val popupScope = rememberCoroutineScope()
    val showEnumPopup = remember(item.key) { mutableStateOf(false) }
    fun readValue(): Int {
        val fallback = when (item.key) {
            ConfigKeys.REAR_APP_FACE_AUTH_STYLE, ConfigKeys.REAR_NOTIFICATION_FACE_AUTH_STYLE ->
                prefsManager.getInt(ConfigKeys.REAR_FACE_AUTH_STYLE, defaultValue)
            else -> defaultValue
        }
        return prefsManager.getInt(item.key, fallback)
    }
    var selectedValue by remember(item.key) { mutableIntStateOf(readValue()) }
    val remoteRevision = rememberRemotePrefsStatusRevision()
    var saving by remember(item.key) { mutableStateOf(false) }
    LaunchedEffect(remoteRevision) {
        if (!saving && prefsManager.isRemoteReady()) {
            selectedValue = readValue()
        }
    }
    val optionTitles = remember(item.key, options, configuration) {
        options.map { context.getString(it.titleRes) }
    }

    val selectedLabel = options
        .indexOfFirst { it.value == selectedValue }
        .takeIf { it >= 0 }
        ?.let { optionTitles[it] }
        ?: optionTitles.firstOrNull().orEmpty()
    val description = item.descriptionRes?.let { stringResource(it) }
    ConfigDropdownRow(
        title = stringResource(item.titleRes),
        summary = description,
        currentLabel = selectedLabel,
        labels = optionTitles,
        selectedIndex = options.indexOfFirst { it.value == selectedValue },
        expanded = showEnumPopup.value,
        onExpandedChange = { showEnumPopup.value = it },
        onSelected = { index ->
            if (!saving && index in options.indices) {
                saving = true
                val value = options[index].value
                popupScope.launch {
                    val committed = withContext(Dispatchers.IO) {
                        runCatching {
                            val editor = prefsManager.prefs.edit().putInt(item.key, value)
                            if (item.key == ConfigKeys.MODULE_THEME_MODE) {
                                editor.remove(ConfigKeys.MODULE_MOCHI_COLOR_MODE)
                            }
                            editor.commit()
                        }
                            .getOrDefault(false)
                    }
                    saving = false
                    if (committed) {
                        selectedValue = value
                        onPreferenceChanged(item)
                    } else {
                        Toast.makeText(context, R.string.seine_setting_save_failed, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        },
    )
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun RearStoreApiConfigInput(
    item: ConfigItem,
    apiConfig: ConfigType.RearStoreApi,
    prefsManager: PrefsManager,
    onPreferenceChanged: (ConfigItem) -> Unit = {},
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val popupScope = rememberCoroutineScope()
    val showApiPopup = remember(item.key) { mutableStateOf(false) }
    var selectedProviderValue by remember(item.key) {
        mutableIntStateOf(prefsManager.getInt(item.key, apiConfig.defaultProviderValue))
    }
    var customDomain by remember(apiConfig.customDomainKey) {
        mutableStateOf(prefsManager.getString(apiConfig.customDomainKey, ""))
    }
    val provider = StoreApiProvider.fromValue(selectedProviderValue)
    val validation = remember(customDomain) { validateRearStoreCustomDomain(customDomain) }
    val optionTitles = remember(configuration) {
        StoreApiProvider.selectableEntries.map { context.getString(it.titleRes) }
    }
    val selectedLabel = optionTitles[StoreApiProvider.selectableEntries.indexOf(provider)]
    val description = item.descriptionRes?.let { stringResource(it) }
    val summary = buildString {
        if (!description.isNullOrBlank()) {
            append(description)
            if (provider == StoreApiProvider.CUSTOM && validation != null) append('\n')
        }
        if (provider == StoreApiProvider.CUSTOM && validation != null) {
            append(validation.baseUrl)
        }
    }
    val customHelperText = when {
        customDomain.isBlank() -> stringResource(R.string.module_store_api_custom_domain_help)
        validation == null -> stringResource(R.string.module_store_api_custom_domain_invalid)
        else -> validation.baseUrl
    }
    val customHelperColor = when {
        customDomain.isBlank() -> MiuixTheme.colorScheme.onSurfaceVariantSummary
        validation == null -> MiuixTheme.colorScheme.error
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        ConfigDropdownRow(
            title = stringResource(item.titleRes),
            summary = summary,
            currentLabel = selectedLabel,
            labels = optionTitles,
            selectedIndex = StoreApiProvider.selectableEntries.indexOf(provider),
            expanded = showApiPopup.value,
            onExpandedChange = { showApiPopup.value = it },
            onSelected = { index ->
                popupScope.launch {
                    withFrameNanos { }
                    selectedProviderValue = StoreApiProvider.selectableEntries[index].value
                    prefsManager.putInt(item.key, selectedProviderValue)
                    onPreferenceChanged(item)
                }
            },
        )

        if (provider == StoreApiProvider.CUSTOM) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 14.dp),
            ) {
                TextField(
                    value = customDomain,
                    onValueChange = {
                        customDomain = normalizeRearStoreCustomDomainInput(it)
                        prefsManager.putString(apiConfig.customDomainKey, customDomain)
                        onPreferenceChanged(item)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.module_store_api_custom_domain),
                    singleLine = true,
                )
                Text(
                    text = customHelperText,
                    fontSize = 12.sp,
                    color = customHelperColor,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
fun FloatSliderConfigInput(
    item: ConfigItem,
    sliderConfig: ConfigType.FloatSlider,
    prefsManager: PrefsManager,
    onPreferenceChanged: (ConfigItem) -> Unit = {},
) {
    var selectedValue by remember(item.key) {
        mutableFloatStateOf(
            sliderConfig.normalizeValue(
                prefsManager.getFloat(
                    item.key,
                    sliderConfig.defaultValue
                )
            )
        )
    }
    val description = item.descriptionRes?.let { stringResource(it) }
    val valueText = remember(selectedValue, sliderConfig) {
        sliderConfig.formatValue(selectedValue)
    }
    val valueSummary = stringResource(R.string.config_slider_current_value, valueText)
    val summary = if (description.isNullOrBlank()) {
        valueSummary
    } else {
        "$description\n$valueSummary"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(
            text = stringResource(item.titleRes),
            fontSize = 17.sp,
        )
        Text(
            text = summary,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 2.dp)
        )
        Slider(
            value = selectedValue,
            onValueChange = {
                val normalizedValue = sliderConfig.normalizeValue(it)
                if (selectedValue != normalizedValue) {
                    selectedValue = normalizedValue
                    prefsManager.putFloat(item.key, normalizedValue)
                    onPreferenceChanged(item)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            valueRange = sliderConfig.minValue..sliderConfig.maxValue,
            steps = sliderConfig.steps,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = sliderConfig.formatValue(sliderConfig.minValue),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = sliderConfig.formatValue(sliderConfig.maxValue),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
fun ManagerConfigInput(item: ConfigItem, onClick: () -> Unit) {
    MochiListRow(
        title = stringResource(item.titleRes),
        subtitle = item.descriptionRes?.let { stringResource(it) },
        chevron = true,
        onClick = onClick,
    )
}

@Composable
internal fun ConfigDropdownRow(
    title: String,
    summary: String?,
    currentLabel: String,
    labels: List<String>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onSelected: (Int) -> Unit,
    selectedIndex: Int = -1,
    checkedIndices: Set<Int> = emptySet(),
) {
    val items = remember(labels, checkedIndices) {
        labels.mapIndexed { index, label ->
            MochiItem(
                label = label,
                icon = if (index in checkedIndices) {
                    { tint -> MochiIonicon("checkmark", tint = tint, size = 18.dp) }
                } else null,
            )
        }.toPersistentList()
    }
    MochiListRow(
        title = title,
        subtitle = summary?.takeIf { it.isNotBlank() },
        trailing = {
            MochiDropdown(
                expanded = expanded,
                onExpandedChange = onExpandedChange,
                items = items,
                onItemClick = onSelected,
                enabled = items.isNotEmpty(),
                selectedIndex = selectedIndex,
                behavior = SeineMenuDefaults.Trailing,
                menuWidth = 300.dp,
            ) {
                Text(
                    text = currentLabel,
                    color = LocalContentColor.current,
                    modifier = Modifier.widthIn(max = 112.dp),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.size(6.dp))
                MochiIonicon(if (expanded) "chevron-up" else "chevron-down", size = 14.dp)
            }
        },
    )
}
