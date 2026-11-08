package hk.uwu.reareye.ui.components.config

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.updateTransition
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.config.ConfigCategory
import hk.uwu.reareye.ui.featureGuideAnchor
import kotlinx.collections.immutable.toPersistentList
import top.yukonga.miuix.kmp.basic.Icon
import ui.feice.mochi.basic.MochiBarIconButton
import ui.feice.mochi.effect.overscroll.MochiScrollDefaults
import ui.feice.mochi.effect.overscroll.ProvideMochiOverscroll
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.LocalMochiListItemSpacing
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.list.rows
import ui.feice.mochi.navigation.MochiTitleBar
import ui.feice.mochi.theme.LocalContentColor
import ui.feice.mochi.theme.MochiDefaults

private enum class DashboardTab { CARDS, COMPONENTS, WALLPAPERS, MORE }

enum class ConfigDashboardAction {
    ADD_CARD,
    ADD_COMPONENT,
    IMPORT_WALLPAPER,
    REFRESH_WALLPAPER,
}

/** Four object tabs; More uses the same categories as its search results. */
@Composable
internal fun ConfigDashboard(
    moreCategories: List<MoreCategory>,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onOpenCategory: (ConfigCategory) -> Unit,
    onOpenScriptManagement: () -> Unit,
    onMoreSearchRequested: () -> Unit,
    onOpenStore: () -> Unit,
    onOpenPresetPackDialog: (() -> Unit)? = null,
    selectedTabIndex: Int = 0,
    onSelectedTabIndexChange: (Int) -> Unit = {},
    moreScrollIndex: Int = 0,
    moreScrollOffset: Int = 0,
    onMoreScrollChanged: (Int, Int) -> Unit = { _, _ -> },
    cardContent: @Composable (String?, () -> Unit, (String) -> Unit, ConfigDashboardAction?, () -> Unit) -> Unit = { _, _, _, _, _ -> },
    componentContent: @Composable (String?, () -> Unit, (String) -> Unit, ConfigDashboardAction?, () -> Unit) -> Unit = { _, _, _, _, _ -> },
    wallpaperContent: @Composable (ConfigDashboardAction?, () -> Unit) -> Unit = { _, _ -> },
) {
    var selectedTab by remember(selectedTabIndex) {
        mutableStateOf(DashboardTab.entries.getOrNull(selectedTabIndex) ?: DashboardTab.CARDS)
    }
    // Keep each manager composed after its first visit. This avoids tearing down its loaded
    // state and starting a second repository load on every tab switch.
    var loadedTabMask by rememberSaveable {
        mutableStateOf(1 shl (selectedTabIndex.coerceIn(0, DashboardTab.entries.lastIndex)))
    }
    // Programmatic navigation must load the destination just like a tab tap.
    LaunchedEffect(selectedTab) {
        loadedTabMask = loadedTabMask or (1 shl selectedTab.ordinal)
    }
    var focusCardId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusComponentBusiness by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingAction by remember { mutableStateOf<ConfigDashboardAction?>(null) }

    fun selectTab(index: Int) {
        val tab = DashboardTab.entries.getOrNull(index) ?: return
        selectedTab = tab
        loadedTabMask = loadedTabMask or (1 shl tab.ordinal)
        onSelectedTabIndexChange(index)
    }

    val tabs = listOf(
        stringResource(R.string.config_tab_cards),
        stringResource(R.string.config_tab_components),
        stringResource(R.string.config_tab_wallpapers),
        stringResource(R.string.config_tab_more),
    )
    val transition = updateTransition(selectedTab, label = "Config tabs")
    val tabLabels = remember(tabs) { tabs.toPersistentList() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MochiDefaults.groupedBackground())
            .then(modifier),
    ) {
        MochiTitleBar(
            modifier = Modifier.featureGuideAnchor("config_header_${selectedTab.ordinal}"),
            title = stringResource(R.string.configuration_title),
            navigationIcon = {
                MochiBarIconButton(
                    onClick = onOpenStore,
                    modifier = Modifier.featureGuideAnchor("config_store_button"),
                ) {
                    MochiIonicon("cloud-download", contentDescription = stringResource(R.string.store_navigation))
                }
            },
            actions = {
                when (selectedTab) {
                    DashboardTab.CARDS -> DashboardAction("add", R.string.rear_widget_add_card) {
                        pendingAction = ConfigDashboardAction.ADD_CARD
                    }
                    DashboardTab.COMPONENTS -> DashboardAction("add", R.string.rear_widget_add_business) {
                        pendingAction = ConfigDashboardAction.ADD_COMPONENT
                    }
                    DashboardTab.WALLPAPERS -> {
                        DashboardAction("add", R.string.rear_wallpaper_import) {
                            pendingAction = ConfigDashboardAction.IMPORT_WALLPAPER
                        }
                        DashboardAction("refresh", R.string.rear_wallpaper_refresh) {
                            pendingAction = ConfigDashboardAction.REFRESH_WALLPAPER
                        }
                    }
                    DashboardTab.MORE -> DashboardAction("search", R.string.config_more_search, onMoreSearchRequested)
                }
            },
        )
        ConfigSegmentedControl(
            items = tabLabels,
            selectedIndex = selectedTab.ordinal,
            onSelect = ::selectTab,
            modifier = Modifier.featureGuideAnchor("config_tabs"),
            topPadding = 4.dp,
        )
        Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            DashboardTab.entries.forEach { tab ->
                if (tab != selectedTab && loadedTabMask and (1 shl tab.ordinal) == 0) return@forEach
                key(tab) {
                    val visible = tab == transition.currentState || tab == transition.targetState
                    val progress = transition.animateFloat(
                        transitionSpec = { spring(dampingRatio = .94f, stiffness = 480f) },
                        label = "Tab visibility",
                    ) { if (it == tab) 1f else 0f }
                    Box(
                        Modifier.fillMaxSize()
                            .zIndex(if (tab == selectedTab) 1f else 0f)
                            .graphicsLayer {
                                alpha = progress.value.coerceIn(0f, 1f)
                                translationX = (1f - alpha) * 18.dp.toPx()
                            }
                            .layout { measurable, constraints ->
                            if (visible) {
                                val page = measurable.measure(constraints)
                                layout(page.width, page.height) { page.placeRelative(0, 0) }
                            } else {
                                layout(constraints.minWidth, constraints.minHeight) {}
                            }
                        },
                    ) {
                        when (tab) {
                            DashboardTab.CARDS -> cardContent(
                                focusCardId,
                                { focusCardId = null },
                                { business ->
                                    focusComponentBusiness = business
                                    selectTab(DashboardTab.COMPONENTS.ordinal)
                                },
                                pendingAction.takeIf { selectedTab == DashboardTab.CARDS },
                            ) { pendingAction = null }

                            DashboardTab.COMPONENTS -> componentContent(
                                focusComponentBusiness,
                                { focusComponentBusiness = null },
                                { cardId ->
                                    focusCardId = cardId
                                    selectTab(DashboardTab.CARDS.ordinal)
                                },
                                pendingAction.takeIf { selectedTab == DashboardTab.COMPONENTS },
                            ) { pendingAction = null }

                            DashboardTab.WALLPAPERS -> wallpaperContent(
                                pendingAction.takeIf { selectedTab == DashboardTab.WALLPAPERS },
                            ) { pendingAction = null }

                            DashboardTab.MORE -> MoreTab(
                                categories = moreCategories,
                                bottomPadding = contentPadding.calculateBottomPadding(),
                                onOpenCategory = onOpenCategory,
                                onOpenScriptManagement = onOpenScriptManagement,
                                onOpenPresetPackDialog = onOpenPresetPackDialog,
                                initialScrollIndex = moreScrollIndex,
                                initialScrollOffset = moreScrollOffset,
                                onScrollChanged = onMoreScrollChanged,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardAction(icon: String, @StringRes label: Int, onClick: () -> Unit) {
    MochiBarIconButton(onClick = onClick) {
        MochiIonicon(icon, contentDescription = stringResource(label))
    }
}

@Composable
private fun MoreTab(
    categories: List<MoreCategory>,
    bottomPadding: Dp,
    onOpenCategory: (ConfigCategory) -> Unit,
    onOpenScriptManagement: () -> Unit,
    onOpenPresetPackDialog: (() -> Unit)?,
    initialScrollIndex: Int,
    initialScrollOffset: Int,
    onScrollChanged: (Int, Int) -> Unit,
) {
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialScrollIndex,
        initialFirstVisibleItemScrollOffset = initialScrollOffset,
    )
    val saveScroll by rememberUpdatedState(onScrollChanged)
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) saveScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        }
    }
    DisposableEffect(listState) {
        onDispose { saveScroll(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
    }

    val spacing = 12.dp
    val groupsTitle = stringResource(R.string.configuration_title)
    CompositionLocalProvider(LocalMochiListItemSpacing provides spacing) {
        ProvideMochiOverscroll {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = ConfigUi.HorizontalInset, end = ConfigUi.HorizontalInset, top = 4.dp, bottom = bottomPadding + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(spacing),
                flingBehavior = MochiScrollDefaults.flingBehavior(),
            ) {
                mochiListSection("more_tools") {
                    if (onOpenPresetPackDialog != null) {
                        row(key = "preset_pack") {
                            MochiListRow(
                                title = stringResource(R.string.preset_pack_title),
                                subtitle = stringResource(R.string.preset_pack_summary),
                                leading = { MochiIonicon("download") },
                                onClick = onOpenPresetPackDialog,
                                chevron = true,
                            )
                        }
                    }
                    row(key = "scripts") {
                        MochiListRow(
                            title = stringResource(R.string.config_more_scripts_title),
                            subtitle = stringResource(R.string.config_more_scripts_desc),
                            leading = { MochiIonicon("code-slash") },
                            onClick = onOpenScriptManagement,
                            chevron = true,
                        )
                    }
                }
                mochiListSection("more_categories", header = groupsTitle) {
                    rows(categories, key = { it.group.id }) { entry ->
                        MochiListRow(
                            title = stringResource(entry.group.titleRes),
                            subtitle = stringResource(entry.group.descriptionRes),
                            leading = {
                                Icon(entry.group.icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = LocalContentColor.current)
                            },
                            onClick = { onOpenCategory(entry.category) },
                            chevron = true,
                        )
                    }
                }
            }
        }
    }
}
