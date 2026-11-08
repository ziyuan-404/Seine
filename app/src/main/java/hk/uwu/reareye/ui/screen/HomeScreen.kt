package hk.uwu.reareye.ui.screen

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.components.SeineMenuDefaults
import hk.uwu.reareye.hook.support.ModuleActivationState
import hk.uwu.reareye.hook.support.XposedModuleStatus
import hk.uwu.reareye.ui.components.SeineSupportCard
import hk.uwu.reareye.ui.components.SeineWordmark
import hk.uwu.reareye.ui.components.config.ConfigCard
import hk.uwu.reareye.ui.config.RearAppDefaults
import hk.uwu.reareye.ui.config.RearNotificationDefaults
import hk.uwu.reareye.ui.config.rememberRemotePrefsStatusRevision
import hk.uwu.reareye.utils.other.DeviceConfigTools
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.PrefsManager.Companion.getPrefsManager
import hk.uwu.reareye.ui.easteregg.EasterEggManager
import hk.uwu.reareye.ui.easteregg.EasterEggType
import hk.uwu.reareye.ui.featureGuideAnchor
import hk.uwu.reareye.ui.theme.AppThemeMode
import hk.uwu.reareye.utils.RootHelper
import hk.uwu.reareye.version.SeineProject
import hk.uwu.reareye.version.SeineRelease
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme
import ui.feice.mochi.basic.MochiDropdown
import ui.feice.mochi.basic.MochiItem
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListCard
import ui.feice.mochi.list.MochiListDefaults
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSection
import ui.feice.mochi.navigation.LocalMochiBottomBarInset
import ui.feice.mochi.navigation.MochiLazyCompactTitleScaffold
import ui.feice.mochi.shape.MochiCapsule
import ui.feice.mochi.shape.MochiRoundedRectangle
import ui.feice.mochi.theme.MochiTheme

private val HomeComponentPackages = listOf(
    R.string.subsceen_version to "com.xiaomi.subscreencenter",
    R.string.seine_home_assistant_version to "com.miui.personalassistant",
    R.string.seine_home_themes_version to "com.android.thememanager",
)

private object UpdateInfoCache {
    val lock = Mutex()
    var latestRelease: SeineRelease? = null
}

private class ToastHolder {
    var toast: Toast? = null
}

private data class FrameNotice(
    val title: String,
    val summary: String,
)

private object FrameNoticeCache {
    var loaded = false
    var value: FrameNotice? = null
    var pending = false
    val callbacks = mutableListOf<(FrameNotice?) -> Unit>()
}

fun preloadHomeFrameNotice(context: Context) {
    resolveFrameNotice(context) {}
}

@Immutable
private data class StatusCardPalette(
    val container: Color,
    val icon: Color,
    val title: Color,
)

private fun customStatusCardPalette(
    container: Color,
    icon: Color,
    title: Color,
): StatusCardPalette {
    return StatusCardPalette(
        container = container,
        icon = icon,
        title = title,
    )
}

@Composable
private fun rememberStatusCardPalette(
    accent: Color,
    containerStrength: Float = 0.22f,
    iconStrength: Float = 0.86f,
    titleStrength: Float = 0.46f,
): StatusCardPalette {
    val colorScheme = MiuixTheme.colorScheme

    return remember(
        accent,
        colorScheme.surface,
        colorScheme.onSurface,
        colorScheme.onSurfaceVariantSummary,
        containerStrength,
        iconStrength,
        titleStrength,
    ) {
        StatusCardPalette(
            container = lerp(colorScheme.surface, accent, containerStrength),
            icon = lerp(colorScheme.onSurfaceVariantSummary, accent, iconStrength),
            title = lerp(colorScheme.onSurface, accent, titleStrength),
        )
    }
}

private fun showSingleToast(context: Context, holder: ToastHolder, message: String) {
    holder.toast?.cancel()
    holder.toast = Toast.makeText(context.applicationContext, message, Toast.LENGTH_SHORT)
    holder.toast?.show()
}

private fun resolveFrameNotice(context: Context, onResolved: (FrameNotice?) -> Unit) {
    synchronized(FrameNoticeCache) {
        if (FrameNoticeCache.loaded) {
            onResolved(FrameNoticeCache.value)
            return
        }

        FrameNoticeCache.callbacks += onResolved
        if (FrameNoticeCache.pending) return
        FrameNoticeCache.pending = true
    }

    val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
        override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
            val lines = resultData?.getStringArray(decodeFrameToken(3)).orEmpty()
            val notice = if (resultCode == 2 && lines.size >= 2 &&
                lines[0].isNotBlank() && lines[1].isNotBlank()
            ) {
                FrameNotice(title = lines[0], summary = lines[1])
            } else {
                null
            }
            val callbacks = synchronized(FrameNoticeCache) {
                FrameNoticeCache.value = notice
                FrameNoticeCache.loaded = true
                FrameNoticeCache.pending = false
                FrameNoticeCache.callbacks.toList().also {
                    FrameNoticeCache.callbacks.clear()
                }
            }
            callbacks.forEach { it(notice) }
        }
    }

    context.applicationContext.sendBroadcast(
        Intent(decodeFrameToken(0)).apply {
            setPackage(context.packageName)
            putExtra(decodeFrameToken(1), receiver)
        }
    )
}

private fun decodeFrameToken(index: Int): String {
    val value = when (index) {
        0 -> "aGsudXd1LnJlYXJleWUuYWN0aW9uLlBVTFNF"
        1 -> "aGsudXd1LnJlYXJleWUuZXh0cmEuQ0FMTEJBQ0s="
        2 -> "aGsudXd1LnJlYXJleWUuZXh0cmEuQ09ERQ=="
        3 -> "aGsudXd1LnJlYXJleWUuZXh0cmEuTElORVM="
        else -> ""
    }
    return String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8)
}

@SuppressLint("LocalContextGetResourceValueCall")
@Composable
fun HomeScreen(
    bottomInnerPadding: Dp = 0.dp,
    onOpenSponsor: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var activationState by remember { mutableStateOf(XposedModuleStatus.current()) }
    val isActivated = activationState == ModuleActivationState.ACTIVE
    val showTopMenu = remember { mutableStateOf(false) }
    val prefsManager = remember { context.getPrefsManager() }
    val coroutineScope = rememberCoroutineScope()
    val easterEggToastHolder = remember { ToastHolder() }
    var latestRelease by remember { mutableStateOf<SeineRelease?>(null) }
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var hasRootAccess by remember { mutableStateOf<Boolean?>(null) }
    val frameNotice = FrameNoticeCache.value
    var easterEggType by remember {
        mutableStateOf(EasterEggManager.getCurrentEasterEggType(context))
    }
    val themeMode = remember(prefsManager) {
        AppThemeMode.fromValue(
            prefsManager.getInt(
                ConfigKeys.MODULE_THEME_MODE,
                AppThemeMode.default.value,
            )
        )
    }
    val useMonetStatusColors = remember(themeMode) {
        themeMode == AppThemeMode.MONET_SYSTEM ||
                themeMode == AppThemeMode.MONET_LIGHT ||
                themeMode == AppThemeMode.MONET_DARK
    }

    DisposableEffect(lifecycleOwner) {
        val statusObserver: (ModuleActivationState) -> Unit = { activationState = it }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) XposedModuleStatus.refreshAsync()
        }
        XposedModuleStatus.observe(statusObserver)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        XposedModuleStatus.refreshAsync()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            XposedModuleStatus.removeObserver(statusObserver)
        }
    }

    LaunchedEffect(Unit) {
        isCheckingUpdate = true
        latestRelease = UpdateInfoCache.lock.withLock {
            UpdateInfoCache.latestRelease ?: SeineProject.latestRelease()?.also {
                UpdateInfoCache.latestRelease = it
            }
        }
        isCheckingUpdate = false
    }

    LaunchedEffect(Unit) {
        hasRootAccess = withContext(Dispatchers.IO) {
            RootHelper.hasRootAccess()
        }
    }

    val statusTitle = if (isActivated) {
        when (easterEggType) {
            EasterEggType.APRIL_FOOLS -> stringResource(R.string.home_easter_egg_april_fools_working)
            EasterEggType.EASTER -> stringResource(R.string.home_easter_egg_easter_working)
            EasterEggType.MI_FANS -> stringResource(R.string.home_easter_egg_mifans_working)
            EasterEggType.HUAWEI_ULTIMATE_DESIGN -> stringResource(R.string.home_easter_egg_huawei_ultimate_design_working)
            else -> stringResource(R.string.seine_home_active)
        }
    } else {
        stringResource(
            when (activationState) {
                ModuleActivationState.SERVICE_UNAVAILABLE -> R.string.home_status_service_unavailable
                ModuleActivationState.SCOPE_NOT_AUTHORIZED -> R.string.home_status_scope_not_authorized
                ModuleActivationState.NO_RUNNING_TARGET -> R.string.home_status_no_running_target
                ModuleActivationState.ACTIVE -> R.string.home_status_working
            }
        )
    }
    val showRootWarning = hasRootAccess == false

    val revision = rememberRemotePrefsStatusRevision()
    var counts by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var framework by remember { mutableStateOf<String?>(null) }
    val systemVersion = remember {
        DeviceConfigTools.getSystemProperties("ro.mi.os.version.incremental")
            .ifBlank { android.os.Build.VERSION.INCREMENTAL }
    }
    val componentVersions by produceState<Map<Int, String?>>(emptyMap(), context) {
        value = withContext(Dispatchers.IO) {
            HomeComponentPackages.associate { (label, packageName) ->
                label to DeviceConfigTools.getPackageVersion(context, packageName)
            }
        }
    }
    LaunchedEffect(revision, activationState) {
        withContext(Dispatchers.IO) {
            val applications = if (prefsManager.getBoolean(ConfigKeys.REAR_APP_ENABLED, RearAppDefaults.ENABLED)) {
                prefsManager.getStringSet(ConfigKeys.ACTIVITIES_WHITELIST_APPS, RearAppDefaults.ALLOWED_APPS).size
            } else 0
            val notifications = if (prefsManager.getBoolean(ConfigKeys.REAR_NOTIFICATION_SYNC, RearNotificationDefaults.ENABLED)) {
                RearNotificationDefaults.resolve(
                    prefsManager.getStringSet(ConfigKeys.REAR_NOTIFICATION_SYNC_APPS, RearNotificationDefaults.APPS),
                    context.packageManager,
                ).size
            } else 0
            (applications to notifications) to XposedModuleStatus.frameworkDescription()
        }.let { (appCounts, xposed) ->
            counts = appCounts
            framework = xposed
        }
    }
    val quickActionLabels = listOf(
        stringResource(R.string.quick_stop_subscreencenter),
        stringResource(R.string.quick_stop_thememanager),
        stringResource(R.string.quick_stop_personalassistant),
        stringResource(R.string.quick_stop_systemui),
    )
    val quickActions = remember(quickActionLabels) {
        quickActionLabels.map { MochiItem(it) }.toPersistentList()
    }

    val compactOverview = LocalConfiguration.current.screenWidthDp >= 350 &&
        LocalDensity.current.fontScale <= 1.3f
    val listState = rememberLazyListState()
    val wordmarkHeight = 48.dp
    val wordmarkHeightPx = with(LocalDensity.current) { wordmarkHeight.roundToPx() }
    val titleCollapsed by remember(listState, wordmarkHeightPx) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset >= wordmarkHeightPx
        }
    }
    CompositionLocalProvider(LocalMochiBottomBarInset provides bottomInnerPadding) {
        MochiLazyCompactTitleScaffold(
            title = "",
            listState = listState,
            navigationIcon = {
                if (titleCollapsed) {
                    SeineWordmark(width = 76.dp, color = MochiTheme.colorScheme.onSurface)
                }
            },
            actions = {
                MochiDropdown(
                    behavior = SeineMenuDefaults.Trailing,
                    expanded = showTopMenu.value,
                    onExpandedChange = { showTopMenu.value = it },
                    items = quickActions,
                    menuWidth = 256.dp,
                    buttonMinWidth = 48.dp,
                    buttonMinHeight = 48.dp,
                    buttonPadding = PaddingValues(0.dp),
                    onItemClick = { index ->
                        coroutineScope.launch {
                            when (index) {
                                0 -> forceStopPackageByRoot(
                                    context, "com.xiaomi.subscreencenter",
                                    context.getString(R.string.category_subscreencenter),
                                )
                                1 -> forceStopPackageByRoot(
                                    context, "com.android.thememanager",
                                    context.getString(R.string.category_thememanager),
                                )
                                2 -> forceStopPackageByRoot(
                                    context, "com.miui.personalassistant",
                                    context.getString(R.string.category_personalassistant),
                                )
                                3 -> forceKillSystemUI(context)
                            }
                        }
                    },
                ) {
                    MochiIonicon("ellipsis-horizontal", contentDescription = stringResource(R.string.config_tab_more))
                }
            },
        ) {
            item(key = "wordmark") {
                SeineWordmark(
                    width = 180.dp,
                    color = MochiTheme.colorScheme.onSurface,
                    modifier = Modifier.height(wordmarkHeight),
                )
            }
            item(key = "status") {
                val status: @Composable (Modifier) -> Unit = { cardModifier ->
                    WorkingStatusCard(
                        modifier = cardModifier,
                        compact = compactOverview,
                        statusTitle = statusTitle,
                        frameworkVersion = framework?.takeIf { isActivated },
                        activated = isActivated,
                        easterEggType = easterEggType,
                        useMonetColors = useMonetStatusColors,
                        onLongPress = {
                            val result = EasterEggManager.toggleTodayEasterEggEnabled(context)
                            if (!result.matchedToday) {
                                showSingleToast(
                                    context,
                                    easterEggToastHolder,
                                    context.getString(R.string.home_easter_egg_no_today),
                                )
                                return@WorkingStatusCard
                            }
                            easterEggType = EasterEggManager.getCurrentEasterEggType(context)
                            val eggName = context.getString(result.type.toTitleRes())
                            val messageRes = if (result.isEnabled) {
                                R.string.home_easter_egg_enabled
                            } else {
                                R.string.home_easter_egg_disabled
                            }
                            showSingleToast(context, easterEggToastHolder, context.getString(messageRes, eggName))
                        },
                    )
                }
                val countCards: @Composable (Modifier) -> Unit = { modifier ->
                    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        HomeCountTile(counts?.first, stringResource(R.string.seine_home_rear_apps))
                        HomeCountTile(counts?.second, stringResource(R.string.seine_home_notification_apps))
                    }
                }
                if (compactOverview) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        status(Modifier.weight(1f).fillMaxHeight())
                        countCards(Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        status(Modifier.fillMaxWidth())
                        countCards(Modifier.fillMaxWidth())
                    }
                }
            }
            frameNotice?.let { notice ->
                item(key = "frame_notice") {
                    HomeNotice(
                        title = notice.title,
                        summary = notice.summary,
                        icon = "information-circle",
                    )
                }
            }
            if (showRootWarning) {
                item(key = "root_warning") {
                    HomeNotice(
                        title = stringResource(R.string.home_root_warning_title),
                        summary = stringResource(R.string.home_root_warning_desc),
                        icon = "shield",
                        isError = true,
                    )
                }
            }
            item(key = "system_info") {
                ConfigCard {
                    MochiListRow(stringResource(R.string.seine_home_system_version), subtitle = systemVersion)
                    HomeComponentPackages.forEach { (label, _) ->
                        MochiListRow(stringResource(label), subtitle = componentVersions[label] ?: "—")
                    }
                }
            }

            item(key = "support_seine") { SeineSupportCard(onOpenSponsor) }
            item(key = "version_update") {
                UpdateInfoCard(
                    currentVersion = SeineProject.CURRENT_RELEASE.removePrefix("v"),
                    latestVersion = latestRelease?.tag,
                    checking = isCheckingUpdate,
                    onOpenReleases = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, SeineProject.RELEASES.toUri()))
                    },
                )
            }
        }
    }
}

private suspend fun forceStopPackageByRoot(
    context: Context,
    packageName: String,
    appName: String,
) {
    withContext(Dispatchers.IO) {
        if (!RootHelper.hasRootAccess()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_need_root_permission),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return@withContext
        }

        val success = RootHelper.executeRootCommandSuccess("am force-stop $packageName")
        withContext(Dispatchers.Main) {
            Toast.makeText(
                context,
                context.getString(
                    if (success) R.string.quick_stop_success else R.string.quick_stop_failed,
                    appName,
                ),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
}

private suspend fun forceKillSystemUI(context: Context) {
    withContext(Dispatchers.IO) {
        if (!RootHelper.hasRootAccess()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_need_root_permission),
                    Toast.LENGTH_SHORT,
                ).show()
            }
            return@withContext
        }

        RootHelper.executeRootCommandSuccess("kill -9 $(pgrep systemui)")
    }
}

@Composable
private fun WorkingStatusCard(
    modifier: Modifier,
    compact: Boolean,
    statusTitle: String,
    frameworkVersion: String?,
    activated: Boolean,
    easterEggType: EasterEggType,
    useMonetColors: Boolean,
    onLongPress: () -> Unit,
) {
    val palette = when {
        useMonetColors && !activated -> rememberStatusCardPalette(
            accent = MiuixTheme.colorScheme.error,
            containerStrength = 0.24f,
        )

        !activated -> MochiTheme.colorScheme.run {
            StatusCardPalette(errorContainer, onErrorContainer, onErrorContainer)
        }

        easterEggType == EasterEggType.NEW_YEAR -> customStatusCardPalette(
            container = Color(0xFFFFECEC),
            icon = Color(0xFFE64B4B),
            title = Color(0xFF7F1E1E),
        )

        easterEggType == EasterEggType.APRIL_FOOLS -> customStatusCardPalette(
            container = Color(0xFFFFF6D8),
            icon = Color(0xFFEBB027),
            title = Color(0xFF7A5100),
        )

        easterEggType == EasterEggType.EASTER -> customStatusCardPalette(
            container = Color(0xFFFFF4E6),
            icon = Color(0xFFED9A9A),
            title = Color(0xFF6A4C93),
        )

        easterEggType == EasterEggType.MI_FANS -> customStatusCardPalette(
            container = Color(0xFFFFF2E0),
            icon = Color(0xFFFF6900),
            title = Color(0xFFB34700),
        )

        easterEggType == EasterEggType.HUAWEI_ULTIMATE_DESIGN -> customStatusCardPalette(
            container = Color(0xFF5C2325),
            icon = Color(0xFFE3B86A),
            title = Color(0xFFF8E2B3),
        )

        MochiTheme.colorScheme.isDark -> StatusCardPalette(
            container = Color(0xFF183724),
            icon = Color(0xFF72CD7D),
            title = Color(0xFFECFFF0),
        )

        else -> StatusCardPalette(
            container = Color(0xFFE1F7E4),
            icon = Color(0xFF6BC874),
            title = Color(0xFF101610),
        )
    }

    val statusIcon = when {
        !activated -> "alert-circle"
        easterEggType == EasterEggType.APRIL_FOOLS -> "bug"
        easterEggType == EasterEggType.EASTER -> "egg"
        easterEggType == EasterEggType.MI_FANS -> "lock-closed"
        else -> "checkmark-circle"
    }
    Box(
        modifier = modifier
            .heightIn(min = if (compact) 144.dp else 124.dp)
            .featureGuideAnchor("home_status")
            .clip(MochiRoundedRectangle(16.dp))
            .background(palette.container)
            .semantics {
                onLongClick {
                    onLongPress()
                    true
                }
            }
            .pointerInput(onLongPress) {
                detectTapGestures(onLongPress = { onLongPress() })
            },
    ) {
        val markModifier = Modifier
            .align(Alignment.BottomEnd)
            .offset(x = 13.dp, y = 14.dp)
            .size(76.dp)
        if (statusIcon == "checkmark-circle") {
            StatusCheckMark(markModifier, palette.icon)
        } else {
            MochiIonicon(statusIcon, modifier = markModifier, size = 76.dp, tint = palette.icon)
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(start = 15.dp, end = 15.dp, top = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(statusTitle, color = palette.title, style = MochiTheme.textStyles.titleLarge)
            if (!frameworkVersion.isNullOrBlank()) {
                Text(
                    frameworkVersion.replace("API ", "API\u00A0"),
                    color = palette.title.copy(alpha = 0.76f),
                    style = MochiTheme.textStyles.footnote,
                )
            }
        }
    }
}

@Composable
private fun StatusCheckMark(modifier: Modifier, tint: Color) {
    Spacer(
        modifier.drawWithCache {
            val w = size.minDimension
            val stroke = w * .10f
            val tick = Path().apply {
                moveTo(w * .27f, w * .53f)
                lineTo(w * .41f, w * .67f)
                lineTo(w * .73f, w * .35f)
            }
            onDrawBehind {
                drawCircle(tint, radius = (w - stroke) / 2f, style = Stroke(stroke))
                drawPath(tick, tint, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        },
    )
}

@Composable
private fun HomeCountTile(count: Int?, label: String) {
    ConfigCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(label, color = MochiTheme.colorScheme.onSurfaceVariant, style = MochiTheme.textStyles.bodySmall)
            Text(count?.toString() ?: "—", style = MochiTheme.textStyles.titleLarge)
        }
    }
}

private fun EasterEggType.toTitleRes(): Int {
    return when (this) {
        EasterEggType.NONE -> R.string.home_easter_egg_none
        EasterEggType.NEW_YEAR -> R.string.home_easter_egg_new_year
        EasterEggType.APRIL_FOOLS -> R.string.home_easter_egg_april_fools
        EasterEggType.EASTER -> R.string.home_easter_egg_easter
        EasterEggType.MI_FANS -> R.string.home_easter_egg_mifans
        EasterEggType.HUAWEI_ULTIMATE_DESIGN -> R.string.home_easter_egg_huawei_ultimate_design
    }
}

@Composable
private fun HomeNotice(
    title: String,
    summary: String,
    icon: String,
    isError: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = MochiTheme.colorScheme
    val noticeColors = if (isError) {
        MochiListDefaults.colors(
            container = colors.errorContainer,
            content = colors.onErrorContainer,
            secondary = colors.onErrorContainer,
            accent = colors.onErrorContainer,
        )
    } else {
        MochiListDefaults.colors(
            container = colors.tertiaryContainer,
            content = colors.onTertiaryContainer,
            secondary = colors.onTertiaryContainer,
            accent = colors.onTertiaryContainer,
        )
    }
    MochiListCard(colors = noticeColors) {
        MochiListRow(
            title = title,
            subtitle = summary,
            leading = { MochiIonicon(icon) },
            onClick = onClick,
            chevron = onClick != null,
            colors = noticeColors,
        )
    }
}

@Composable
private fun UpdateInfoCard(
    currentVersion: String,
    latestVersion: String?,
    checking: Boolean,
    onOpenReleases: () -> Unit,
) {
    MochiListSection(header = stringResource(R.string.home_update_title)) {
        MochiListRow(stringResource(R.string.home_update_current), subtitle = currentVersion)
        MochiListRow(
            stringResource(R.string.home_update_latest),
            subtitle = when {
                checking -> stringResource(R.string.home_update_checking)
                latestVersion == null -> stringResource(R.string.home_update_unknown)
                else -> latestVersion
            },
            onClick = onOpenReleases,
            chevron = true,
        )
    }
}
