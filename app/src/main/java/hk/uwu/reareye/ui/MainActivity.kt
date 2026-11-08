package hk.uwu.reareye.ui

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import ui.feice.mochi.effect.glass.mochiBackdropSource
import ui.feice.mochi.effect.glass.rememberMochiBackdrop
import hk.uwu.reareye.ui.components.PresetPackDialog
import hk.uwu.reareye.ui.components.SubscreenNotificationAccessPrompt
import hk.uwu.reareye.ui.components.TargetAppVersionDialog
import hk.uwu.reareye.ui.components.motion.ArtVisibilityMotion
import hk.uwu.reareye.ui.components.navigation.NavigationQuickTarget
import hk.uwu.reareye.ui.components.navigation.RearNavigationBar
import hk.uwu.reareye.ui.components.navigation.encodeNavigationQuickActionIds
import hk.uwu.reareye.ui.components.navigation.parseNavigationQuickActionIds
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.ConfigType
import hk.uwu.reareye.ui.config.ModuleMochiGraphicsLevel
import hk.uwu.reareye.ui.config.ModuleNavigationBarMode
import hk.uwu.reareye.ui.config.ModuleSettingsController
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import hk.uwu.reareye.ui.config.rememberRemotePrefsStatusRevision
import hk.uwu.reareye.ui.screen.AboutScreen
import hk.uwu.reareye.ui.screen.ConfigScreen
import hk.uwu.reareye.ui.screen.HomeScreen
import hk.uwu.reareye.ui.screen.SeineSponsorScreen
import hk.uwu.reareye.ui.screen.LocalMarySidebarPageGestureHost
import hk.uwu.reareye.ui.screen.MarySidebarPageGestureHost
import hk.uwu.reareye.ui.screen.MarySidebarPagePreview
import hk.uwu.reareye.ui.screen.marySidebarPageGesture
import hk.uwu.reareye.ui.screen.marySidebarBackground
import hk.uwu.reareye.ui.screen.MaryScreen
import hk.uwu.reareye.ui.screen.MarySettingsScreen
import hk.uwu.reareye.ui.screen.RearStoreScreen
import hk.uwu.reareye.ui.screen.preloadHomeFrameNotice
import hk.uwu.reareye.ui.theme.AppTheme
import hk.uwu.reareye.ui.theme.MochiColorSettings
import hk.uwu.reareye.ui.theme.AppThemeMode
import hk.uwu.reareye.version.AppVersionGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.MiuixTheme
import ui.feice.mochi.theme.MochiGraphics

/**
 * Screen order drives the horizontal transition direction. The Rear Store is reachable from the
 * configuration header rather than the bottom bar, so it keeps a slot here for its transition
 * while [RearNavigationBar] renders only the four bottom-bar destinations.
 */
private val MainScreenOrder = listOf("home", "support", "store", "mary", "config", "about")

private data class RemoteUiSettings(
    val themeModeValue: Int,
    val mochiGraphicsValue: Int,
    val mochiColors: MochiColorSettings,
    val navigationBarModeValue: Int,
    val searchBarStyleValue: Int,
    val navigationQuickActionIds: List<String>,
    val launcherHidden: Boolean,
)

class MainActivity : ComponentActivity() {
    private companion object {
        const val OOBE_PREFS = "reareye_oobe"
        const val OOBE_COMPLETED = "completed"
        const val FEATURE_GUIDE_COMPLETED = "feature_guide_completed"
    }

    private var versionGateClosed = false

    /**
     * Leaves the app after the target app version reminder. Guarded so the countdown and the
     * button cannot both finish the activity.
     */
    private fun closeAfterVersionGate() {
        if (versionGateClosed) return
        versionGateClosed = true
        finishAndRemoveTask()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the window edge-to-edge: Compose, not decor resizing/panning, owns IME avoidance.
        enableEdgeToEdge()
        MochiGraphics.level = ModuleMochiGraphicsLevel.default.level

        try {
            val permissionInfo = applicationContext.packageManager
                .getPermissionInfo("com.android.permission.GET_INSTALLED_APPS", 0)
            if (permissionInfo != null && permissionInfo.packageName == "com.lbe.security.miui") {
                if (ContextCompat.checkSelfPermission(
                        applicationContext,
                        "com.android.permission.GET_INSTALLED_APPS"
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    ActivityCompat.requestPermissions(
                        this@MainActivity,
                        arrayOf("com.android.permission.GET_INSTALLED_APPS"),
                        999,
                    )
                }
            }
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }

        preloadHomeFrameNotice(applicationContext)

        // Target app versions are checked before any UI comes up: an outdated rear screen
        // container silently rejects the hooks and template fields this module writes, so it is
        // safer to explain the requirement and leave than to half work.
        val versionViolations = AppVersionGate.evaluate(applicationContext)

        setContent {
            if (versionViolations.isNotEmpty()) {
                AppTheme {
                    TargetAppVersionDialog(
                        violations = versionViolations,
                        onClose = { closeAfterVersionGate() },
                    )
                }
                return@setContent
            }
            val onboardingPrefs = remember {
                applicationContext.getSharedPreferences(OOBE_PREFS, MODE_PRIVATE)
            }
            /*onboardingPrefs.edit {
                putBoolean(OOBE_COMPLETED, false)
                putBoolean(FEATURE_GUIDE_COMPLETED, false)
            }*/
            var onboardingCompleted by remember {
                mutableStateOf(onboardingPrefs.getBoolean(OOBE_COMPLETED, false))
            }
            var startFeatureGuideAfterOobe by remember { mutableStateOf(false) }

            if (!onboardingCompleted) {
                AppTheme {
                    OnboardingScreen(
                        onFinished = { startFeatureGuide ->
                            onboardingPrefs.edit {
                                putBoolean(OOBE_COMPLETED, true)
                                putBoolean(FEATURE_GUIDE_COMPLETED, !startFeatureGuide)
                            }
                            startFeatureGuideAfterOobe = startFeatureGuide
                            onboardingCompleted = true
                        },
                    )
                }
                return@setContent
            }

            val remotePrefsManager = remember { applicationContext.getPrefsManager() }
            val remoteRevision = rememberRemotePrefsStatusRevision()
            var remoteUiSettings by remember { mutableStateOf<RemoteUiSettings?>(null) }

            var appearanceRevision by remember { mutableIntStateOf(0) }
            LaunchedEffect(remoteRevision, appearanceRevision) {
                val loadedRemoteUiSettings = withContext(Dispatchers.IO) {
                    if (!remotePrefsManager.isRemoteReady()) {
                        null
                    } else {
                        hk.uwu.reareye.ui.config.InitialSettingsMigration.apply(applicationContext, remotePrefsManager)
                        val rawQuickActionIds = remotePrefsManager.getString(
                            ConfigKeys.MODULE_NAVIGATION_QUICK_ACTIONS
                        )
                        val normalizedQuickActionIds = parseNavigationQuickActionIds(
                            rawQuickActionIds
                        ).toList()
                        // Drop quick actions removed by a newer configuration model while
                        // preserving the user's remaining order during upgrade.
                        if (rawQuickActionIds.isNotBlank()) {
                            val normalizedValue = encodeNavigationQuickActionIds(
                                normalizedQuickActionIds
                            )
                            if (rawQuickActionIds != normalizedValue) {
                                remotePrefsManager.putString(
                                    ConfigKeys.MODULE_NAVIGATION_QUICK_ACTIONS,
                                    normalizedValue,
                                )
                            }
                        }
                        RemoteUiSettings(
                            themeModeValue = remotePrefsManager.getInt(
                                ConfigKeys.MODULE_THEME_MODE,
                                AppThemeMode.default.value,
                            ),
                            mochiColors = MochiColorSettings(
                                mode = remotePrefsManager.getInt(ConfigKeys.MODULE_MOCHI_COLOR_MODE, -1),
                                palette = remotePrefsManager.getInt(ConfigKeys.MODULE_MOCHI_PALETTE_STYLE, 0),
                                seed = if (remotePrefsManager.prefs.contains(ConfigKeys.MODULE_MOCHI_SEED_COLOR)) {
                                    remotePrefsManager.getInt(ConfigKeys.MODULE_MOCHI_SEED_COLOR, 0)
                                } else null,
                            ),
                            mochiGraphicsValue = remotePrefsManager.getInt(
                                ConfigKeys.MODULE_MOCHI_GRAPHICS_LEVEL,
                                ModuleMochiGraphicsLevel.default.value,
                            ),
                            navigationBarModeValue = remotePrefsManager.getInt(
                                ConfigKeys.MODULE_NAVIGATION_BAR_MODE,
                                ModuleNavigationBarMode.default.value,
                            ),
                            searchBarStyleValue = remotePrefsManager.getInt(
                                ConfigKeys.MODULE_SEARCH_BAR_STYLE,
                                hk.uwu.reareye.ui.config.ModuleSearchBarStyle.default.value,
                            ),
                            navigationQuickActionIds = normalizedQuickActionIds,
                            launcherHidden = remotePrefsManager.getBoolean(
                                ConfigKeys.MODULE_HIDE_LAUNCHER_ENTRY,
                                false,
                            ),
                        )
                    }
                }

                loadedRemoteUiSettings?.let { settings ->
                    MochiGraphics.level = ModuleMochiGraphicsLevel.fromValue(settings.mochiGraphicsValue).level
                    remoteUiSettings = settings
                    ModuleSettingsController.syncLauncherEntryVisibility(
                        context = applicationContext,
                        hidden = settings.launcherHidden,
                    )
                }
            }

            val settings = remoteUiSettings
            if (settings == null) {
                Box(modifier = Modifier.fillMaxSize())
                return@setContent
            }

            var currentScreen by remember { mutableStateOf("home") }
            // The Rear Store is a configuration-level destination, so the system back gesture
            // returns to Configuration instead of finishing the activity. Deeper store routes
            // (widget detail) consume back themselves before this handler sees it.
            BackHandler(enabled = currentScreen == "store") {
                currentScreen = "config"
            }
            BackHandler(enabled = currentScreen == "support") { currentScreen = "home" }
            var showPresetPackDialog by remember { mutableStateOf(false) }
            var navBarVisible by remember { mutableStateOf(false) }
            var configInAppListMode by remember { mutableStateOf(false) }
            var pendingConfigQuickManagerTarget by remember {
                mutableStateOf<ConfigType.ManagerType?>(null)
            }
            var pendingQuickActionTransition by remember { mutableStateOf(false) }
            var pendingRearStoreWidgetId by remember { mutableStateOf<String?>(null) }
            var navigationQuickActionIds by remember {
                mutableStateOf(settings.navigationQuickActionIds)
            }
            var featureGuideVisible by rememberSaveable {
                mutableStateOf(
                    startFeatureGuideAfterOobe ||
                            !onboardingPrefs.getBoolean(FEATURE_GUIDE_COMPLETED, false),
                )
            }
            var featureGuideStep by rememberSaveable { mutableIntStateOf(0) }
            val guideAnchors = remember { FeatureGuideAnchors() }
            val guideDemoState =
                rememberSaveable(featureGuideVisible, saver = FeatureGuideDemoState.Saver) {
                    FeatureGuideDemoState(getString(hk.uwu.reareye.R.string.guide_demo_card_name))
                }
            val guideStep = featureGuideSteps[featureGuideStep]

            LaunchedEffect(featureGuideVisible, featureGuideStep) {
                if (!featureGuideVisible) return@LaunchedEffect
                currentScreen = if (guideStep.dashboardTab == null) "home" else "config"
                pendingConfigQuickManagerTarget = null
                configInAppListMode = false
            }

            LaunchedEffect(Unit) {
                navBarVisible = true
            }

            AppTheme(
                themeMode = AppThemeMode.fromValue(settings.themeModeValue),
                colorSettings = settings.mochiColors,
            ) {
                val navigationBarMode = ModuleNavigationBarMode.fromValue(settings.navigationBarModeValue)
                val glassNavigation = navigationBarMode == ModuleNavigationBarMode.FLOATING_GLASS ||
                    navigationBarMode == ModuleNavigationBarMode.SEMI_TRANSPARENT
                val showNavigation =
                    navBarVisible && currentScreen != "support" && !(currentScreen == "config" && configInAppListMode)
                val density = LocalDensity.current
                val surfaceColor = MiuixTheme.colorScheme.surface
                val backdrop = if (glassNavigation) rememberMochiBackdrop(surfaceColor) else null
                var stableBottomInset by remember { mutableStateOf(0.dp) }
                val marySidebarGestureHost = remember { MarySidebarPageGestureHost() }
                val launchReveal = remember { androidx.compose.animation.core.Animatable(0f) }
                LaunchedEffect(Unit) {
                    launchReveal.animateTo(
                        1f,
                        androidx.compose.animation.core.tween(
                            420,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing,
                        ),
                    )
                }

                CompositionLocalProvider(
                    hk.uwu.reareye.ui.components.LocalRearSearchBarStyle provides
                        hk.uwu.reareye.ui.config.ModuleSearchBarStyle.fromValue(settings.searchBarStyleValue),
                    LocalFeatureGuideAnchors provides guideAnchors.takeIf { featureGuideVisible },
                    LocalMarySidebarPageGestureHost provides marySidebarGestureHost,
                ) {
                    // Keep the popup host full-window: MIUIX dialogs own their IME avoidance.
                    Scaffold { _ ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(surfaceColor)
                                .graphicsLayer {
                                    alpha = launchReveal.value
                                    translationY = 14.dp.toPx() * (1f - launchReveal.value)
                                }
                                .marySidebarPageGesture(marySidebarGestureHost,
                                    enabled = currentScreen == "mary" && !showPresetPackDialog && !featureGuideVisible)
                                // Navigation stays at the physical window bottom. Screens own
                                // IME avoidance; never shrink this shared root or its popup host.
                                .graphicsLayer { clip = true }
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .marySidebarBackground(marySidebarGestureHost)
                                    .mochiBackdropSource(backdrop)
                            ) {
                                AnimatedContent(
                                    targetState = currentScreen,
                                    contentKey = { it },
                                    transitionSpec = {
                                        if (pendingQuickActionTransition && targetState == "config") {
                                            pendingQuickActionTransition = false
                                            return@AnimatedContent ContentTransform(
                                                targetContentEnter = EnterTransition.None,
                                                initialContentExit = ExitTransition.None,
                                            )
                                        }

                                        val initialIndex =
                                            MainScreenOrder.indexOf(initialState).coerceAtLeast(0)
                                        val targetIndex =
                                            MainScreenOrder.indexOf(targetState).coerceAtLeast(0)
                                        val forward = targetIndex >= initialIndex

                                        fadeIn(
                                            animationSpec = tween(
                                                durationMillis = 210,
                                                delayMillis = 50,
                                                easing = LinearOutSlowInEasing,
                                            )
                                        ) + slideInHorizontally(
                                            animationSpec = tween(
                                                durationMillis = 280,
                                                easing = FastOutSlowInEasing,
                                            )
                                        ) { fullWidth ->
                                            if (forward) fullWidth / 9 else -fullWidth / 9
                                        } togetherWith (
                                                fadeOut(
                                                    animationSpec = tween(
                                                        durationMillis = 110,
                                                        easing = FastOutLinearInEasing,
                                                    )
                                                ) + slideOutHorizontally(
                                                    animationSpec = tween(
                                                        durationMillis = 190,
                                                        easing = FastOutLinearInEasing,
                                                    )
                                                ) { fullWidth ->
                                                    if (forward) -fullWidth / 12 else fullWidth / 12
                                                }
                                                )
                                    },
                                    label = "ScreenTransition"
                                ) { screen ->
                                    when (screen) {
                                        "home" -> Box(modifier = Modifier.fillMaxSize()) {
                                            HomeScreen(
                                                bottomInnerPadding = stableBottomInset,
                                                onOpenSponsor = { currentScreen = "support" },
                                            )
                                        }

                                        "store" -> RearStoreScreen(
                                            bottomInnerPadding = stableBottomInset,
                                            initialWidgetId = pendingRearStoreWidgetId,
                                            onInitialWidgetHandled = {
                                                pendingRearStoreWidgetId = null
                                            },
                                            onBack = {
                                                configInAppListMode = false
                                                currentScreen = "config"
                                            },
                                        )

                                        "mary" -> Box(modifier = Modifier.fillMaxSize()) {
                                            var marySettingsOpen by remember {
                                                mutableStateOf(false)
                                            }
                                            BackHandler(enabled = marySettingsOpen) {
                                                marySettingsOpen = false
                                            }
                                            if (marySettingsOpen) {
                                                MarySettingsScreen(
                                                    bottomInnerPadding = stableBottomInset,
                                                    onBack = { marySettingsOpen = false },
                                                )
                                            } else {
                                                MaryScreen(
                                                    bottomInnerPadding = stableBottomInset,
                                                    onOpenSettings = { marySettingsOpen = true },
                                                )
                                            }
                                        }

                                        "config" -> Box(modifier = Modifier.fillMaxSize()) {
                                            if (featureGuideVisible) {
                                                FeatureGuideDemoScreen(
                                                    step = guideStep,
                                                    state = guideDemoState,
                                                    bottomPadding = stableBottomInset,
                                                    onCancelEdit = {
                                                        featureGuideStep =
                                                            (featureGuideStep - 1).coerceAtLeast(0)
                                                    },
                                                    onAction = { action ->
                                                        if (featureGuideSteps[featureGuideStep].action == action) {
                                                            featureGuideStep += 1
                                                        }
                                                    },
                                                )
                                            } else ConfigScreen(
                                                onOpenPresetPackDialog = { showPresetPackDialog = true },
                                                bottomInnerPadding = stableBottomInset,
                                                quickManagerTarget = pendingConfigQuickManagerTarget,
                                                onQuickManagerTargetHandled = {
                                                    pendingConfigQuickManagerTarget = null
                                                },
                                                onAppListModeChange = {
                                                    configInAppListMode = it
                                                },
                                                onAppearanceChanged = { appearanceRevision++ },
                                                onOpenRearStore = {
                                                    configInAppListMode = false
                                                    currentScreen = "store"
                                                },
                                                onOpenRearStoreDetail = { widgetId ->
                                                    pendingRearStoreWidgetId = widgetId
                                                    configInAppListMode = false
                                                    currentScreen = "store"
                                                },
                                            )
                                        }

                                        "support" -> SeineSponsorScreen(onBack = { currentScreen = "home" })

                                        "about" -> AboutScreen(bottomInnerPadding = stableBottomInset)
                                    }
                                }
                            }

                            val navShadowProgress by animateFloatAsState(
                                targetValue = if (showNavigation) 1f else 0f,
                                animationSpec = tween(
                                    durationMillis = if (showNavigation) 380 else 240,
                                    easing = if (showNavigation) {
                                        FastOutSlowInEasing
                                    } else {
                                        FastOutLinearInEasing
                                    },
                                ),
                                label = "NavigationShadowProgress",
                            )
                            if (showNavigation || navShadowProgress > 0.001f) {
                                // This bar does not avoid the IME: it stays put behind the keyboard.
                                // Measure only the bar; Mary combines this gap with (not adds it to) IME.
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomCenter)
                                        .marySidebarBackground(marySidebarGestureHost),
                                ) {
                                    ArtVisibilityMotion(
                                        visible = showNavigation,
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .featureGuideAnchor("bottom_navigation")
                                            .onGloballyPositioned { coordinates ->
                                                val totalHeight = with(density) {
                                                    coordinates.size.height.toDp()
                                                }
                                                if (totalHeight != stableBottomInset) {
                                                    stableBottomInset = totalHeight
                                                }
                                            },
                                        enterAlphaDurationMillis = 260,
                                        enterTransformDurationMillis = 380,
                                        exitAlphaDurationMillis = 180,
                                        exitTransformDurationMillis = 240,
                                        hiddenEnterScale = 1f,
                                        hiddenExitScale = 1f,
                                        slideDivisor = 3,
                                        hiddenOffsetFallback = 28.dp,
                                    ) {
                                        RearNavigationBar(
                                            // The Rear Store is a configuration-level destination,
                                            // so the Configuration tab stays highlighted while it is open.
                                            currentScreen = if (currentScreen == "store") "config" else currentScreen,
                                            navigationBarMode = navigationBarMode,
                                            backdrop = backdrop,
                                            quickActionIds = navigationQuickActionIds,
                                            onQuickActionIdsChanged = { nextIds ->
                                                val normalizedIds = parseNavigationQuickActionIds(
                                                    encodeNavigationQuickActionIds(nextIds)
                                                )
                                                navigationQuickActionIds = normalizedIds
                                                remotePrefsManager.putString(
                                                    ConfigKeys.MODULE_NAVIGATION_QUICK_ACTIONS,
                                                    encodeNavigationQuickActionIds(normalizedIds),
                                                )
                                            },
                                            onScreenSelected = { currentScreen = it },
                                            onQuickActionSelected = { target ->
                                                when (target) {
                                                    is NavigationQuickTarget.ConfigManager -> {
                                                        pendingConfigQuickManagerTarget =
                                                            target.managerType
                                                        pendingQuickActionTransition = true
                                                        // Dashboard managers stay inside the new configuration
                                                        // workbench and keep the navigation bar visible. Only
                                                        // dedicated More pages use the full-screen overlay mode.
                                                        configInAppListMode =
                                                            target.managerType in setOf(
                                                                ConfigType.ManagerType.SCENE_ROUTE,
                                                                ConfigType.ManagerType.BOUNDS,
                                                                ConfigType.ManagerType.REAR_APP_DISPLAY,
                                                            )
                                                        currentScreen = "config"
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            }

                            MarySidebarPagePreview(marySidebarGestureHost,
                                enabled = currentScreen == "mary" && !showPresetPackDialog && !featureGuideVisible)

                            SubscreenNotificationAccessPrompt(
                                enabled = !featureGuideVisible && !showPresetPackDialog,
                            )

                            PresetPackDialog(
                                show = showPresetPackDialog,
                                onDismissRequest = { showPresetPackDialog = false },
                            )

                            if (featureGuideVisible && guideStep.action !in setOf(
                                    FeatureGuideAction.SAVE_CARD, FeatureGuideAction.SAVE_COMPONENT,
                                )
                            ) {
                                FeatureGuideOverlay(
                                    stepIndex = featureGuideStep,
                                    anchors = guideAnchors,
                                    onPrevious = {
                                        featureGuideStep = (featureGuideStep - 1).coerceAtLeast(0)
                                    },
                                    onNext = {
                                        if (featureGuideStep == featureGuideSteps.lastIndex) {
                                            onboardingPrefs.edit()
                                                .putBoolean(FEATURE_GUIDE_COMPLETED, true).apply()
                                            featureGuideVisible = false
                                        } else {
                                            featureGuideStep += 1
                                        }
                                    },
                                    onSkip = {
                                        onboardingPrefs.edit()
                                            .putBoolean(FEATURE_GUIDE_COMPLETED, true)
                                            .apply()
                                        featureGuideVisible = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
