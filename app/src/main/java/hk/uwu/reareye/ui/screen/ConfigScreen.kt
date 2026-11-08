package hk.uwu.reareye.ui.screen

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import hk.uwu.reareye.ui.components.navigation.BACKGROUND_PARALLAX
import hk.uwu.reareye.ui.components.navigation.BACKGROUND_SCALE_REDUCTION
import hk.uwu.reareye.ui.components.navigation.LAYER_ENTER_DURATION
import hk.uwu.reareye.ui.components.navigation.LAYER_EXIT_DURATION
import hk.uwu.reareye.ui.components.navigation.PREDICTIVE_CANCEL_DURATION
import hk.uwu.reareye.ui.components.navigation.PredictiveBackMotionTracker
import hk.uwu.reareye.ui.components.navigation.predictiveExitProgress
import hk.uwu.reareye.ui.components.navigation.predictiveSettleDuration
import hk.uwu.reareye.ui.components.navigation.predictiveSettleEasing
import hk.uwu.reareye.ui.components.navigation.predictiveTranslationFraction
import hk.uwu.reareye.ui.components.navigation.smootherStep
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import ui.feice.mochi.list.MochiListRow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.repository.rearwallpaper.RearWallpaperInfo
import hk.uwu.reareye.ui.components.config.AppListSelectorScreen
import hk.uwu.reareye.ui.components.config.BusinessExtraConfigManagerScreen
import hk.uwu.reareye.ui.components.config.BusinessExtraConfigScreen
import hk.uwu.reareye.ui.components.config.BusinessManagerScreen
import hk.uwu.reareye.ui.components.config.CardManagerScreen
import hk.uwu.reareye.ui.components.config.ConfigDashboard
import hk.uwu.reareye.ui.components.config.ConfigNodeRow
import hk.uwu.reareye.ui.components.config.ConfigUi
import hk.uwu.reareye.ui.components.config.ConfigBackupPage
import hk.uwu.reareye.ui.components.config.LocalConfigOwnsBack
import hk.uwu.reareye.ui.components.config.CustomBoundsCompatManagerScreen
import hk.uwu.reareye.ui.components.config.MoreSearchBottomSheet
import hk.uwu.reareye.ui.components.config.RearAppDisplayManagerScreen
import hk.uwu.reareye.ui.components.config.RearWallpaperManagerScreen
import hk.uwu.reareye.ui.components.config.SceneRouteManagerScreen
import hk.uwu.reareye.ui.components.config.buildMoreCategories
import hk.uwu.reareye.ui.components.config.rememberRearWallpaperManagerState
import hk.uwu.reareye.ui.components.config.template.RearWallpaperTemplateConfigScreen
import hk.uwu.reareye.ui.components.script.ScriptManagementScreen
import hk.uwu.reareye.ui.config.ConfigCategory
import hk.uwu.reareye.ui.config.ConfigGroup
import hk.uwu.reareye.ui.config.ConfigItem
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.ConfigNode
import hk.uwu.reareye.ui.config.ConfigType
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import hk.uwu.reareye.ui.config.REAREyeConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Scaffold
import ui.feice.mochi.effect.overscroll.MochiScrollDefaults
import ui.feice.mochi.effect.overscroll.ProvideMochiOverscroll
import ui.feice.mochi.list.LocalMochiListItemSpacing
import ui.feice.mochi.list.MochiListCard
import ui.feice.mochi.list.mochiListSection
import ui.feice.mochi.navigation.MochiBackButton
import ui.feice.mochi.navigation.MochiTitleBar
import ui.feice.mochi.theme.MochiDefaults

private sealed interface ConfigRoute {
    data object Root : ConfigRoute
    data object Backup : ConfigRoute
    data class Category(val category: ConfigCategory) : ConfigRoute
    data class AppList(val item: ConfigItem) : ConfigRoute
    data object RearWallpaperManager : ConfigRoute
    data object BusinessManager : ConfigRoute
    data object SceneRouteManager : ConfigRoute
    data object CardManager : ConfigRoute
    data object BusinessExtraManager : ConfigRoute
    data class BusinessExtraDetail(val business: String) : ConfigRoute
    data object CustomBoundsCompatManager : ConfigRoute
    data object RearAppDisplayManager : ConfigRoute
    data object LyricsManager : ConfigRoute
    data object ScriptManagement : ConfigRoute
    data class WallpaperTemplate(val wallpaper: RearWallpaperInfo) : ConfigRoute
}

private const val PREDICTIVE_TRANSLATION_PERCENT = 50L
private const val NAV_BAR_EXIT_DURATION_MS = 220L
private const val OVERLAY_ROUTE_EXIT_DURATION_MS = 220L

private data class ConfigAnimatedRoute(
    val route: ConfigRoute,
    val depth: Int,
)

private fun ConfigRoute.isOverlayRoute(): Boolean {
    return this is ConfigRoute.AppList ||
            this is ConfigRoute.RearWallpaperManager ||
            this is ConfigRoute.BusinessManager ||
            this is ConfigRoute.SceneRouteManager ||
            this is ConfigRoute.CardManager ||
            this is ConfigRoute.BusinessExtraManager ||
            this is ConfigRoute.BusinessExtraDetail ||
            this is ConfigRoute.CustomBoundsCompatManager ||
            this is ConfigRoute.RearAppDisplayManager
}

@Composable
fun ConfigScreen(
    bottomInnerPadding: Dp = 0.dp,
    quickManagerTarget: ConfigType.ManagerType? = null,
    onQuickManagerTargetHandled: () -> Unit = {},
    onAppListModeChange: (Boolean) -> Unit = {},
    onAppearanceChanged: () -> Unit = {},
    onOpenRearStore: () -> Unit = {},
    onOpenRearStoreDetail: (String) -> Unit = {},
    onOpenPresetPackDialog: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefsManager = remember { context.getPrefsManager() }
    val wallpaperManagerState = rememberRearWallpaperManagerState()

    fun managerRoute(managerType: ConfigType.ManagerType?): ConfigRoute? {
        return when (managerType) {
            ConfigType.ManagerType.REAR_WALLPAPER -> ConfigRoute.RearWallpaperManager
            ConfigType.ManagerType.BUSINESS -> ConfigRoute.BusinessManager
            ConfigType.ManagerType.SCENE_ROUTE -> ConfigRoute.SceneRouteManager
            ConfigType.ManagerType.CARD -> ConfigRoute.CardManager
            ConfigType.ManagerType.BUSINESS_EXTRA -> ConfigRoute.BusinessExtraManager
            ConfigType.ManagerType.BOUNDS -> ConfigRoute.CustomBoundsCompatManager
            ConfigType.ManagerType.REAR_APP_DISPLAY -> ConfigRoute.RearAppDisplayManager
            ConfigType.ManagerType.LYRICS -> ConfigRoute.LyricsManager
            null -> null
        }
    }

    var routeStack by remember {
        mutableStateOf(listOf<ConfigRoute>(ConfigRoute.Root))
    }
    var dashboardTabIndex by rememberSaveable { mutableStateOf(0) }
    var moreScrollIndex by rememberSaveable { mutableStateOf(0) }
    var moreScrollOffset by rememberSaveable { mutableStateOf(0) }
    val moreCategories = remember(REAREyeConfig) { buildMoreCategories(REAREyeConfig) }
    var moreSearchSessionActive by rememberSaveable { mutableStateOf(false) }
    var moreSearchQuery by rememberSaveable { mutableStateOf("") }
    var pendingMoreSearchCategory by remember { mutableStateOf<ConfigCategory?>(null) }
    val currentRoute = routeStack.last()
    val isOverlayMode = currentRoute.isOverlayRoute()
    val animatedRoute = remember(currentRoute, routeStack.size) {
        ConfigAnimatedRoute(
            route = currentRoute,
            depth = routeStack.size,
        )
    }
    val routeScope = rememberCoroutineScope()
    val routeTransitionState = remember { SeekableTransitionState(animatedRoute) }
    val routeTransition = rememberTransition(routeTransitionState, label = "ConfigRouteTransition")
    LaunchedEffect(animatedRoute) {
        routeTransitionState.animateTo(animatedRoute)
    }

    val handlePreferenceChanged: (ConfigItem) -> Unit = { item ->
        when (item.key) {
            ConfigKeys.MODULE_THEME_MODE,
            ConfigKeys.MODULE_MOCHI_COLOR_MODE,
            ConfigKeys.MODULE_MOCHI_PALETTE_STYLE,
            ConfigKeys.MODULE_MOCHI_SEED_COLOR,
            ConfigKeys.MODULE_MOCHI_GRAPHICS_LEVEL,
            ConfigKeys.MODULE_NAVIGATION_BAR_MODE,
            ConfigKeys.MODULE_SEARCH_BAR_STYLE -> onAppearanceChanged()
        }
    }

    fun navigateBack() {
        if (routeStack.size <= 1) return
        val leavingOverlay = routeStack.last().isOverlayRoute()
        routeStack = routeStack.dropLast(1)
        if (leavingOverlay && !routeStack.last().isOverlayRoute()) {
            routeScope.launch {
                delay(OVERLAY_ROUTE_EXIT_DURATION_MS)
                if (!routeStack.last().isOverlayRoute()) onAppListModeChange(false)
            }
        }
    }

    val backMotion = remember { PredictiveBackMotionTracker() }
    val backProgress = remember { Animatable(0f) }
    var backDestination by remember { mutableStateOf<ConfigAnimatedRoute?>(null) }
    var settlingBack by remember { mutableStateOf(false) }

    PredictiveBackHandler(enabled = routeStack.size > 1) { events ->
        val stack = routeStack
        if (stack.size <= 1 || settlingBack) return@PredictiveBackHandler
        val previous = ConfigAnimatedRoute(stack[stack.lastIndex - 1], stack.size - 1)
        var animatingCancellation = false
        try {
            events.collect { event ->
                if (routeStack == stack) {
                    if (backDestination == null) {
                        backMotion.reset(event.progress)
                        backDestination = previous
                    } else {
                        backMotion.update(event.progress)
                    }
                    backProgress.snapTo(event.progress)
                }
            }
            if (routeStack == stack) {
                settlingBack = true
                if (backDestination == null) {
                    backMotion.reset()
                    backDestination = previous
                }
                val progress = backProgress.value
                val target = predictiveExitProgress(PREDICTIVE_TRANSLATION_PERCENT)
                val duration = predictiveSettleDuration(progress, PREDICTIVE_TRANSLATION_PERCENT)
                val easing = predictiveSettleEasing(backMotion.releaseVelocity(), progress, target, duration)
                backProgress.animateTo(target, tween(duration, easing = easing))
                if (routeStack == stack) {
                    routeTransitionState.snapTo(previous)
                    navigateBack()
                }
            }
        } catch (cancelled: CancellationException) {
            if (backDestination != null && routeStack == stack && !settlingBack) {
                settlingBack = true
                animatingCancellation = true
                routeScope.launch {
                    try {
                        backProgress.animateTo(0f, tween(PREDICTIVE_CANCEL_DURATION, easing = FastOutSlowInEasing))
                    } finally {
                        backDestination = null
                        settlingBack = false
                    }
                }
                throw cancelled
            }
            throw cancelled
        } finally {
            // During a cancelled gesture the page scope owns the return animation.
            if (!animatingCancellation) {
                backDestination = null
                settlingBack = false
                withContext(NonCancellable) { backProgress.snapTo(0f) }
            }
        }
    }
    BackHandler(enabled = settlingBack) {}

    fun openOverlayRoute(route: ConfigRoute) {
        routeScope.launch {
            onAppListModeChange(true)
            delay(NAV_BAR_EXIT_DURATION_MS)
            routeStack = routeStack + route
        }
    }

    fun closeOverlayRoute() {
        routeScope.launch {
            routeStack = listOf(ConfigRoute.Root)
            delay(OVERLAY_ROUTE_EXIT_DURATION_MS)
            onAppListModeChange(false)
        }
    }

    fun openManagerRoute(managerType: ConfigType.ManagerType?) {
        val route = managerRoute(managerType)
        if (route != null) {
            openOverlayRoute(route)
        }
    }

    fun openManagerItem(item: ConfigItem) {
        openManagerRoute((item.type as? ConfigType.Manager)?.managerType)
    }

    fun openQuickManagerTarget(managerType: ConfigType.ManagerType) {
        when (managerType) {
            // These managers now live directly in the dashboard. Keeping the route at Root
            // avoids opening the legacy full-screen manager on top of the new workflow.
            ConfigType.ManagerType.CARD -> {
                dashboardTabIndex = 0
                routeStack = listOf(ConfigRoute.Root)
                onAppListModeChange(false)
            }

            ConfigType.ManagerType.BUSINESS,
            ConfigType.ManagerType.BUSINESS_EXTRA -> {
                dashboardTabIndex = 1
                routeStack = listOf(ConfigRoute.Root)
                onAppListModeChange(false)
            }

            ConfigType.ManagerType.REAR_WALLPAPER -> {
                dashboardTabIndex = 2
                routeStack = listOf(ConfigRoute.Root)
                onAppListModeChange(false)
            }

            // These entries are still dedicated pages, but their parent is the More tab so
            // returning from them lands in the same part of the redesigned configuration.
            ConfigType.ManagerType.SCENE_ROUTE -> {
                dashboardTabIndex = 3
                routeStack = listOf(ConfigRoute.Root, ConfigRoute.SceneRouteManager)
                onAppListModeChange(true)
            }

            ConfigType.ManagerType.BOUNDS -> {
                dashboardTabIndex = 3
                routeStack = listOf(ConfigRoute.Root, ConfigRoute.CustomBoundsCompatManager)
                onAppListModeChange(true)
            }

            ConfigType.ManagerType.REAR_APP_DISPLAY -> {
                dashboardTabIndex = 3
                routeStack = listOf(ConfigRoute.Root, ConfigRoute.RearAppDisplayManager)
                onAppListModeChange(true)
            }

            ConfigType.ManagerType.LYRICS -> {
                dashboardTabIndex = 3
                routeStack = listOf(ConfigRoute.Root, ConfigRoute.LyricsManager)
                onAppListModeChange(false)
            }
        }
    }

    LaunchedEffect(quickManagerTarget) {
        val managerType = quickManagerTarget ?: return@LaunchedEffect
        openQuickManagerTarget(managerType)
        onQuickManagerTargetHandled()
    }

    val previewLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val previewBackOwner = remember(previewLifecycle) {
        object : androidx.activity.OnBackPressedDispatcherOwner {
            override val lifecycle = previewLifecycle
            override val onBackPressedDispatcher = androidx.activity.OnBackPressedDispatcher()
        }
    }
    val renderRoute: @Composable (ConfigAnimatedRoute, PaddingValues) -> Unit = { target, paddingValues ->
        when (val route = target.route) {
            ConfigRoute.Backup -> ConfigSubpageLayout(
                title = stringResource(R.string.seine_backup_title),
                onBack = ::navigateBack,
            ) {
                ConfigBackupPage(prefsManager, onAppearanceChanged)
            }
            ConfigRoute.Root -> ConfigDashboard(
                moreCategories = moreCategories,
                contentPadding = PaddingValues(
                    top = paddingValues.calculateTopPadding(),
                    bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
                ),
                onOpenCategory = { category ->
                    // More owns these categories, so returning keeps the More tab selected.
                    dashboardTabIndex = 3
                    routeStack = routeStack + ConfigRoute.Category(category)
                },
                onOpenScriptManagement = {
                    dashboardTabIndex = 3
                    routeStack = routeStack + ConfigRoute.ScriptManagement
                },
                onMoreSearchRequested = {
                    moreSearchQuery = ""
                    moreSearchSessionActive = true
                },
                onOpenStore = onOpenRearStore,
                onOpenPresetPackDialog = onOpenPresetPackDialog,
                selectedTabIndex = dashboardTabIndex,
                onSelectedTabIndexChange = { dashboardTabIndex = it },
                moreScrollIndex = moreScrollIndex,
                moreScrollOffset = moreScrollOffset,
                onMoreScrollChanged = { index, offset ->
                    moreScrollIndex = index
                    moreScrollOffset = offset
                },
                cardContent = { focusCardId, onFocusHandled, onComponentRequested, actionRequest, onActionHandled ->
                    CardManagerScreen(
                        prefsManager = prefsManager,
                        onBack = {},
                        embedded = true,
                        contentPadding = PaddingValues(
                            bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
                        ),
                        focusCardId = focusCardId,
                        onFocusCardHandled = onFocusHandled,
                        onOpenComponent = onComponentRequested,
                        onOpenStoreDetail = onOpenRearStoreDetail,
                        actionRequest = actionRequest,
                        onActionHandled = onActionHandled,
                    )
                },
                componentContent = { focusBusiness, onFocusHandled, onCardRequested, actionRequest, onActionHandled ->
                    BusinessManagerScreen(
                        prefsManager = prefsManager,
                        onBack = {},
                        embedded = true,
                        contentPadding = PaddingValues(
                            bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
                        ),
                        focusBusiness = focusBusiness,
                        onFocusBusinessHandled = onFocusHandled,
                        onOpenCard = onCardRequested,
                        onOpenStoreDetail = onOpenRearStoreDetail,
                        onOpenBusinessExtra = { business ->
                            openOverlayRoute(ConfigRoute.BusinessExtraDetail(business))
                        },
                        actionRequest = actionRequest,
                        onActionHandled = onActionHandled,
                    )
                },
                wallpaperContent = { actionRequest, onActionHandled ->
                    RearWallpaperManagerScreen(
                        prefsManager = prefsManager,
                        state = wallpaperManagerState,
                        onBack = {},
                        embedded = true,
                        contentPadding = PaddingValues(
                            bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
                        ),
                        onOpenStoreDetail = onOpenRearStoreDetail,
                        onEditTemplate = { wallpaper ->
                            dashboardTabIndex = 2
                            routeStack = routeStack + ConfigRoute.WallpaperTemplate(wallpaper)
                        },
                        actionRequest = actionRequest,
                        onActionHandled = onActionHandled,
                    )
                },
            )

            is ConfigRoute.Category -> ConfigSubpageLayout(
                title = stringResource(route.category.titleRes),
                onBack = ::navigateBack,
            ) {
                val pageBottom = paddingValues.calculateBottomPadding() + bottomInnerPadding
                val moreCategory = moreCategories.firstOrNull { it.category.key == route.category.key }
                if (route.category.titleRes == R.string.rear_app_settings_title) {
                    RearAppDisplayManagerScreen(
                        prefsManager = prefsManager,
                        onBack = ::navigateBack,
                        embedded = true,
                        bottomPadding = pageBottom,
                        extraSettings = {
                            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                moreCategory?.sections.orEmpty()
                                    .filterNot { section ->
                                        section.children.any { it.key == ConfigKeys.CFG_REAR_APP_DISPLAY }
                                    }
                                    .forEach { section ->
                                        MochiListCard {
                                            section.children.forEach { node ->
                                                ConfigNodeRow(
                                                    node = node,
                                                    prefsManager = prefsManager,
                                                    onOpenCategory = {},
                                                    onOpenAppList = { openOverlayRoute(ConfigRoute.AppList(it)) },
                                                    onOpenManager = { openManagerItem(it) },
                                                    onPreferenceChanged = handlePreferenceChanged,
                                                )
                                            }
                                        }
                                    }
                            }
                        },
                    )
                } else {
                    ConfigNodeList(
                        nodes = moreCategory?.sections ?: route.category.children,
                        prefsManager = prefsManager,
                        contentPadding = PaddingValues(
                            bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
                        ),
                        onOpenCategory = { category ->
                            routeStack = routeStack + ConfigRoute.Category(category)
                        },
                        onOpenAppList = { item ->
                            openOverlayRoute(ConfigRoute.AppList(item))
                        },
                        onOpenManager = { item -> openManagerItem(item) },
                        onPreferenceChanged = handlePreferenceChanged,
                        onOpenBackup = if (moreCategory?.group?.id == "reareye_settings") {
                            { routeStack = routeStack + ConfigRoute.Backup }
                        } else null,

                    )
                }
            }

            ConfigRoute.LyricsManager -> {
                val lyricsCategory = findConfigCategoryByTitleRes(
                    REAREyeConfig,
                    R.string.subcategory_lyrics,
                )
                ConfigSubpageLayout(
                    title = stringResource(R.string.subcategory_lyrics),
                    onBack = ::navigateBack,
                ) {
                    ConfigNodeList(
                        nodes = lyricsCategory?.children.orEmpty(),
                        prefsManager = prefsManager,
                        contentPadding = PaddingValues(
                            bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
                        ),
                        onOpenCategory = { category ->
                            routeStack = routeStack + ConfigRoute.Category(category)
                        },
                        onOpenAppList = { item ->
                            openOverlayRoute(ConfigRoute.AppList(item))
                        },
                        onOpenManager = { item -> openManagerItem(item) },
                        onPreferenceChanged = handlePreferenceChanged,

                    )
                }
            }

            is ConfigRoute.WallpaperTemplate -> ConfigSubpageLayout(
                title = stringResource(R.string.rear_wallpaper_template_title),
                onBack = ::navigateBack,
            ) {
                CompositionLocalProvider(LocalConfigOwnsBack provides true) {
                    RearWallpaperTemplateConfigScreen(
                        wallpaper = route.wallpaper,
                        embedded = true,
                        allowContentPresentation = !routeTransition.isRunning ||
                                routeTransition.targetState.route !is ConfigRoute.WallpaperTemplate,
                        contentPadding = PaddingValues(
                            bottom = paddingValues.calculateBottomPadding() + bottomInnerPadding,
                        ),
                        onBack = ::navigateBack,
                        onSaved = ::navigateBack,
                    )
                }
            }

            is ConfigRoute.AppList -> AppListSelectorScreen(
                configItem = route.item,
                prefsManager = prefsManager,
                onCancel = { closeOverlayRoute() },
                onSave = { closeOverlayRoute() }
            )

            ConfigRoute.RearWallpaperManager -> RearWallpaperManagerScreen(
                prefsManager = prefsManager,
                onBack = { closeOverlayRoute() },
                onOpenStoreDetail = onOpenRearStoreDetail,
            )

            ConfigRoute.BusinessManager -> BusinessManagerScreen(
                prefsManager = prefsManager,
                onBack = { closeOverlayRoute() },
                onOpenStoreDetail = onOpenRearStoreDetail,
                onOpenBusinessExtra = { business ->
                    routeStack = routeStack + ConfigRoute.BusinessExtraDetail(business)
                },
            )

            ConfigRoute.SceneRouteManager -> SceneRouteManagerScreen(
                prefsManager = prefsManager,
                onBack = { closeOverlayRoute() },
            )

            ConfigRoute.CardManager -> CardManagerScreen(
                prefsManager = prefsManager,
                onBack = { closeOverlayRoute() },
                onOpenStoreDetail = onOpenRearStoreDetail,
            )

            ConfigRoute.BusinessExtraManager -> BusinessExtraConfigManagerScreen(
                prefsManager = prefsManager,
                onBack = { closeOverlayRoute() },
            )

            is ConfigRoute.BusinessExtraDetail -> BusinessExtraConfigScreen(
                prefsManager = prefsManager,
                business = route.business,
                onBack = { routeStack = routeStack.dropLast(1) },
            )

            ConfigRoute.CustomBoundsCompatManager -> CustomBoundsCompatManagerScreen(
                prefsManager = prefsManager,
                onBack = { closeOverlayRoute() },
            )

            ConfigRoute.RearAppDisplayManager -> CompositionLocalProvider(LocalConfigOwnsBack provides true) {
                RearAppDisplayManagerScreen(
                    prefsManager = prefsManager,
                    onBack = { closeOverlayRoute() },
                )
            }

            ConfigRoute.ScriptManagement -> ScriptManagementScreen(
                onBack = ::navigateBack,
                bottomInnerPadding = bottomInnerPadding,
            )
        }
    }
    Scaffold { paddingValues ->
        Box(Modifier.fillMaxSize().graphicsLayer { clip = true }) {
            backDestination?.let { previous ->
                Box(Modifier.fillMaxSize().graphicsLayer {
                    val progress = backProgress.value
                    val depth = 1f - smootherStep(progress)
                    scaleX = 1f - depth * BACKGROUND_SCALE_REDUCTION
                    scaleY = scaleX
                    translationX = -size.width * depth * BACKGROUND_PARALLAX
                    val intensity = (1f - 0.5f * smootherStep(progress)) * (2f - progress).coerceIn(0f, 1f)
                    val radius = 12f * intensity
                    renderEffect = if (radius > 0f) BlurEffect(radius, radius, TileMode.Clamp) else null
                }.drawWithContent {
                    drawContent()
                    val progress = backProgress.value
                    val intensity = (1f - 0.5f * smootherStep(progress)) * (2f - progress).coerceIn(0f, 1f)
                    drawRect(Color.Black.copy(alpha = 0.16f * intensity))
                }) {
                    CompositionLocalProvider(androidx.activity.compose.LocalOnBackPressedDispatcherOwner provides previewBackOwner) {
                        renderRoute(previous, paddingValues)
                    }
                }
            }
            routeTransition.AnimatedContent(
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    if (backDestination != null) {
                        translationX = size.width * backProgress.value *
                            predictiveTranslationFraction(PREDICTIVE_TRANSLATION_PERCENT)
                    }
                },
                contentKey = { it.route },
                transitionSpec = {
                    val forward = targetState.depth > initialState.depth
                    slideInHorizontally(
                        tween(if (forward) LAYER_ENTER_DURATION else LAYER_EXIT_DURATION, easing = FastOutSlowInEasing),
                    ) { width -> if (forward) width else -(width * BACKGROUND_PARALLAX).toInt() } togetherWith
                        slideOutHorizontally(
                            tween(if (forward) LAYER_ENTER_DURATION else LAYER_EXIT_DURATION, easing = FastOutSlowInEasing),
                        ) { width -> if (forward) -(width * BACKGROUND_PARALLAX).toInt() else width }
                },
            ) { target ->
                renderRoute(target, paddingValues)
            }
        }
    }
    MoreSearchBottomSheet(
        show = moreSearchSessionActive && currentRoute == ConfigRoute.Root &&
                dashboardTabIndex == 3 && pendingMoreSearchCategory == null,
        categories = moreCategories,
        query = moreSearchQuery,
        onQueryChange = { moreSearchQuery = it },
        onDismissRequest = {
            moreSearchSessionActive = false
            moreSearchQuery = ""
        },
        onDismissFinished = {
            pendingMoreSearchCategory?.let { category ->
                pendingMoreSearchCategory = null
                dashboardTabIndex = 3
                routeStack = routeStack + ConfigRoute.Category(category)
            }
        },
        onResultSelected = { pendingMoreSearchCategory = it },
    )
}

@Composable
private fun ConfigSubpageLayout(
    title: String,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize().background(MochiDefaults.groupedBackground())) {
        MochiTitleBar(
            title = title,
            navigationIcon = { MochiBackButton(onClick = onBack) },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

private fun findConfigCategoryByTitleRes(
    nodes: List<ConfigNode>,
    titleRes: Int,
): ConfigCategory? {
    nodes.forEach { node ->
        if (node is ConfigCategory) {
            if (node.titleRes == titleRes) return node
            findConfigCategoryByTitleRes(node.children, titleRes)?.let { return it }
        } else if (node is ConfigGroup) {
            findConfigCategoryByTitleRes(node.children, titleRes)?.let { return it }
        }
    }
    return null
}

@Composable
private fun ConfigNodeList(
    nodes: List<ConfigNode>,
    prefsManager: PrefsManager,
    contentPadding: PaddingValues,
    onOpenCategory: (ConfigCategory) -> Unit,
    onOpenAppList: (ConfigItem) -> Unit,
    onOpenManager: (ConfigItem) -> Unit,
    onPreferenceChanged: (ConfigItem) -> Unit = {},
    onOpenBackup: (() -> Unit)? = null,
) {
    val renderNode: @Composable (ConfigNode) -> Unit = { node ->
        ConfigNodeRow(
            node = node,
            prefsManager = prefsManager,
            onOpenCategory = onOpenCategory,
            onOpenAppList = onOpenAppList,
            onOpenManager = onOpenManager,
            onPreferenceChanged = onPreferenceChanged,
        )
    }
    val sections = nodes.map { node ->
        if (node is ConfigGroup) {
            node.titleRes?.let { stringResource(it) } to node.children
        } else {
            null to listOf(node)
        }
    }
    val spacing = 12.dp
    CompositionLocalProvider(LocalMochiListItemSpacing provides spacing) {
        ProvideMochiOverscroll {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = ConfigUi.HorizontalInset,
                    end = ConfigUi.HorizontalInset,
                    top = 8.dp,
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(spacing),
                flingBehavior = MochiScrollDefaults.flingBehavior(),
            ) {
                if (onOpenBackup != null) {
                    mochiListSection("backup_restore") {
                        row {
                            MochiListRow(
                                title = stringResource(R.string.seine_backup_title),
                                subtitle = stringResource(R.string.seine_backup_summary),
                                chevron = true,
                                onClick = onOpenBackup,
                            )
                        }
                    }
                }
                sections.forEachIndexed { index, section ->
                    val (header, entries) = section
                    mochiListSection(entries.firstOrNull()?.key ?: "node_" + index, header = header) {
                        rows(entries.size, key = { entries[it].key ?: it }) { childIndex ->
                            renderNode(entries[childIndex])
                        }
                    }
                }
            }
        }
    }
}
