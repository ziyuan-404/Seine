package hk.uwu.reareye.ui.components.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.components.SeineMenuDefaults
import hk.uwu.reareye.repository.rearwidget.RearBusinessConfig
import hk.uwu.reareye.ui.components.DialogFormColumn
import hk.uwu.reareye.ui.components.OverlayDialog
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.preference.SwitchPreference
import kotlinx.collections.immutable.toPersistentList
import ui.feice.mochi.basic.MochiIconDropdown
import ui.feice.mochi.basic.MochiItem
import ui.feice.mochi.icons.MochiIonicon

@Composable
internal fun RearCardCreateDialog(
    show: Boolean,
    mode: Int,
    onModeChange: (Int) -> Unit,
    businesses: List<RearBusinessConfig>,
    normalTitle: String,
    onNormalTitleChange: (String) -> Unit,
    normalPackageName: String,
    onNormalPackageNameChange: (String) -> Unit,
    normalBusiness: String,
    onNormalBusinessChange: (String) -> Unit,
    normalPriorityText: String,
    onNormalPriorityTextChange: (String) -> Unit,
    normalAutomaticPriority: Boolean,
    onNormalAutomaticPriorityChange: (Boolean) -> Unit,
    normalSticky: Boolean,
    onNormalStickyChange: (Boolean) -> Unit,
    appTitle: String,
    onAppTitleChange: (String) -> Unit,
    selectedAppBusinessIndex: Int,
    onSelectedAppBusinessIndexChange: (Int) -> Unit,
    onConfirmNormal: () -> Unit,
    onConfirmApp: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    val safeMode = mode.coerceIn(0, 1)
    val businessSnapshot = businesses.toList()
    val autocompleteBusinesses = remember(businessSnapshot) {
        businessSnapshot
            .distinctBy { it.business }
            .sortedBy { it.business.lowercase() }
    }
    OverlayDialog(
        show = show,
        title = stringResource(
            if (safeMode == 1) R.string.rear_widget_add_app_card else R.string.rear_widget_add_card,
        ),
        onDismissRequest = onDismissRequest,
    ) {
        DialogFormColumn {
            TabRowWithContour(
                tabs = listOf(
                    stringResource(R.string.rear_widget_card_tab_normal),
                    stringResource(R.string.rear_widget_card_tab_app),
                ),
                selectedTabIndex = safeMode,
                onTabSelected = onModeChange,
                modifier = Modifier.fillMaxWidth(),
            )

            if (safeMode == 1) {
                TextField(
                    value = appTitle,
                    onValueChange = onAppTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.rear_widget_app_card_name),
                    singleLine = true,
                )
                if (businesses.isNotEmpty()) {
                    val safeBusinessIndex =
                        selectedAppBusinessIndex.coerceIn(0, businesses.lastIndex)
                    var expanded by remember { mutableStateOf(false) }
                    ConfigDropdownRow(
                        title = stringResource(R.string.rear_widget_app_bound_component_title),
                        summary = null,
                        currentLabel = businesses[safeBusinessIndex].business,
                        labels = businesses.map { it.business },
                        selectedIndex = safeBusinessIndex,
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        onSelected = onSelectedAppBusinessIndexChange,
                    )
                } else {
                    Text(stringResource(R.string.rear_widget_app_no_component))
                }
                RearCardDialogButtons(
                    confirmEnabled = appTitle.isNotBlank() && businesses.isNotEmpty(),
                    onConfirm = onConfirmApp,
                    onCancel = onDismissRequest,
                )
            } else {
                TextField(
                    value = normalTitle,
                    onValueChange = onNormalTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.rear_widget_card_title),
                    singleLine = true,
                )
                TextField(
                    value = normalPackageName,
                    onValueChange = onNormalPackageNameChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.rear_widget_target_package),
                    singleLine = true,
                )
                RearBusinessAutocompleteTextField(
                    value = normalBusiness,
                    businesses = autocompleteBusinesses,
                    onValueChange = onNormalBusinessChange,
                    onSelect = { business ->
                        onNormalBusinessChange(business.business)
                        onNormalPriorityTextChange(business.defaultPriority.toString())
                    },
                )
                SwitchPreference(
                    title = stringResource(R.string.rear_widget_priority_mode_auto),
                    summary = stringResource(
                        if (normalAutomaticPriority) R.string.rear_widget_priority_auto_desc
                        else R.string.rear_widget_priority_manual_desc,
                    ),
                    checked = normalAutomaticPriority,
                    onCheckedChange = onNormalAutomaticPriorityChange,
                )
                TextField(
                    value = normalPriorityText,
                    onValueChange = onNormalPriorityTextChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = stringResource(R.string.rear_widget_default_priority),
                    enabled = !normalAutomaticPriority,
                    singleLine = true,
                )
                SwitchPreference(
                    title = stringResource(R.string.rear_widget_card_sticky),
                    summary = stringResource(R.string.rear_widget_card_sticky_desc),
                    checked = normalSticky,
                    onCheckedChange = onNormalStickyChange,
                )
                RearCardDialogButtons(
                    confirmEnabled = normalPackageName.isNotBlank() && normalBusiness.isNotBlank(),
                    onConfirm = onConfirmNormal,
                    onCancel = onDismissRequest,
                )
            }
        }
    }
}

@Composable
private fun RearBusinessAutocompleteTextField(
    value: String,
    businesses: List<RearBusinessConfig>,
    onValueChange: (String) -> Unit,
    onSelect: (RearBusinessConfig) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val normalizedQuery = value.trim()
    val suggestions = remember(businesses, normalizedQuery) {
        if (normalizedQuery.isEmpty()) {
            businesses
        } else {
            businesses.filter { business ->
                business.business.contains(normalizedQuery, ignoreCase = true)
            }
        }
    }

    val items = remember(suggestions) {
        suggestions.map { MochiItem(it.business) }.toPersistentList()
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.rear_widget_business_name),
            singleLine = true,
        )
        if (items.isNotEmpty()) {
            MochiIconDropdown(
                behavior = SeineMenuDefaults.Trailing,
                expanded = expanded,
                onExpandedChange = { expanded = it },
                items = items,
                onItemClick = { index -> onSelect(suggestions[index]) },
                selectedIndex = suggestions.indexOfFirst { it.business.equals(value, ignoreCase = true) },
                menuWidth = 232.dp,
            ) {
                MochiIonicon(
                    "chevron-down",
                    contentDescription = stringResource(R.string.rear_widget_app_bound_component_title),
                )
            }
        }
    }
}

@Composable
private fun RearCardDialogButtons(
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onConfirm,
            enabled = confirmEnabled,
            colors = ButtonDefaults.buttonColorsPrimary(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.rear_widget_confirm))
        }
        Button(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.rear_widget_cancel))
        }
    }
}
