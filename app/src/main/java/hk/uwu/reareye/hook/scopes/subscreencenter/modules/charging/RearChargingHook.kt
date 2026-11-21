package hk.uwu.reareye.hook.scopes.subscreencenter.modules.charging

import android.app.Activity
import android.service.dreams.DreamService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.InputEvent
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import com.highcapable.kavaref.KavaRef.Companion.resolve
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.RearAppFaceAuth
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppContext
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.hook.utils.resolveDexKitMethodInjectionPoint
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.RearChargingDefaults
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.roxyhook.android.lifecycle.lifecycle
import kotlin.math.abs

@OptIn(DexKitExperimentalApi::class)
class RearChargingHook : RoxyHooker() {
    companion object {
        // SubScreenLauncher's Z1.d compares the first DOWN with the second UP.
        private const val NATIVE_DOUBLE_TAP_MS = 400L
        private const val TAP_CONFIRM_MS = 416L
        private const val NATIVE_DOUBLE_TAP_DISTANCE = 100f
        private const val DISPLAY_HANDOFF_MS = 650L
        private const val DOZE_RESTORE_DELAY_MS = 120L
        @Volatile
        internal var blocksFaceAuth = false
            private set
    }

    private val main = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var displays: DisplayManager? = null
    private var receiver: BroadcastReceiver? = null
    private var chargeReceiver: BroadcastReceiver? = null
    private var carChargeMode = false
    private var chargeSpeed = 0
    private var wireless = false
    private var socDecimal: Int? = null
    private var socRate = 0
    private var socSampleAt = 0L
    private var chargingWatts = -1
    private var doubleChargeClosed = false
    private val exiting = LinkedHashSet<RearChargingView>()
    private var launcher: Activity? = null
    private var overlayHost: ViewGroup? = null
    private var findLauncher: () -> Activity? = { null }
    private var liquid: RearChargingView? = null
    @Volatile private var plugged = false
    @Volatile private var level = -1
    private var status = BatteryManager.BATTERY_STATUS_UNKNOWN
    @Volatile private var deadline = 0L
    @Volatile private var suppressed = false
    private var rearWasOn = false
    private var tapReadyAt = 0L
    private var lastTapDown = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var allowChargeTap = false
    private var pendingChargeTap: Runnable? = null
    private var dozeService: DreamService? = null
    private var setDozeState: Method? = null
    private var nativeDozeState = Display.STATE_UNKNOWN
    private var appliedDozeState = Display.STATE_UNKNOWN
    private var showingInDoze = false
    private var enabled: () -> Boolean = { RearChargingDefaults.ENABLED }
    private var style: () -> Int = { RearChargingDefaults.defaultStyle() }
    private var shownStyle = -1
    private var preferencesSubscription: AutoCloseable? = null
    private var keepVisible: () -> Boolean = { RearChargingDefaults.PERSISTENT }
    private var keepWhenAsleep: () -> Boolean = { RearChargingDefaults.SCREEN_OFF }
    @Volatile private var nativeNotificationManager: Any? = null
    private var reloadNativeNotifications: ((Any) -> Unit)? = null
    private val deferredNotificationRefreshes = LinkedHashMap<Any, () -> Unit>()
    private val timeout = Runnable { finishEntryPriority() }
    private val awaitHost = Runnable { refresh() }
    private val finishDisplayHandoff = Runnable { refresh(allowHandoff = false) }
    private val restoreDozeState = Runnable { synchronizeDozeState(allowDelay = false) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) { if (displayId != Display.DEFAULT_DISPLAY) refresh() }
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == rearDisplay()?.displayId) refresh()
        }
    }

    override fun PackageScope.onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            enabled = { hookPrefs.getBoolean(ConfigKeys.REAR_LIQUID_CHARGING, RearChargingDefaults.ENABLED) }
            keepVisible = { hookPrefs.getBoolean(ConfigKeys.REAR_LIQUID_CHARGING_PERSISTENT, RearChargingDefaults.PERSISTENT) }
            keepWhenAsleep = { hookPrefs.getBoolean(ConfigKeys.REAR_LIQUID_CHARGING_SCREEN_OFF, RearChargingDefaults.SCREEN_OFF) }
            style = {
                hookPrefs.getInt(
                    ConfigKeys.REAR_CHARGING_STYLE,
                    RearChargingDefaults.defaultStyle(hookAppContext ?: this@RearChargingHook.context),
                ).coerceIn(0, 3)
            }
            preferencesSubscription = hookPrefs.observe { key ->
                if (key == null || key in setOf(ConfigKeys.REAR_CHARGING_STYLE, ConfigKeys.REAR_LIQUID_CHARGING,
                        ConfigKeys.REAR_LIQUID_CHARGING_PERSISTENT, ConfigKeys.REAR_LIQUID_CHARGING_SCREEN_OFF)) {
                    main.post {
                        if (key == ConfigKeys.REAR_LIQUID_CHARGING || key == ConfigKeys.REAR_LIQUID_CHARGING_PERSISTENT ||
                            key == ConfigKeys.REAR_LIQUID_CHARGING_SCREEN_OFF) {
                            suppressed = false
                        }
                        if (!enabled() && deadline != 0L) finishEntryPriority() else refresh()
                    }
                }
            }
            val launcherClass = "com.xiaomi.subscreencenter.SubScreenLauncher".toClass()
            val appClass = "com.xiaomi.subscreencenter.SubScreenCenterApp".toClass()
            val getter = appClass.declaredMethods.singleOrNull {
                Modifier.isStatic(it.modifiers) && it.parameterCount == 0 && it.returnType == launcherClass
            }?.apply { isAccessible = true }
            findLauncher = { runCatching { getter?.invoke(null) as? Activity }.getOrNull() }
            listOf("onCreate" to 1, "onResume" to 0).forEach { (method, count) ->
                launcherClass.resolve().firstMethod { name = method; parameterCount = count }.hook {
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    after { (instance as? Activity)?.let { host -> main.post { adoptLauncher(host); refresh() } } }
                }
            }
            launcherClass.resolve().firstMethod { name = "onDestroy"; parameterCount = 0 }.hook {
                hotReloadPolicy = HotReloadPolicy.REINSTALL
                before {
                    if (instance === launcher) {
                        dismiss(animate = false)
                        exiting.toList().forEach { view ->
                            view.stopTransitions()
                            runCatching { (view.parent as? ViewGroup)?.removeView(view) }
                        }
                        exiting.clear()
                        releaseFaceBlockIfHidden()
                        launcher = null
                    }
                }
            }
            launcherClass.resolve().firstMethod {
                name = "dispatchTouchEvent"; parameters(MotionEvent::class.java)
            }.hook {
                hotReloadPolicy = HotReloadPolicy.REINSTALL
                before {
                    val view = liquid ?: return@before
                    if (instance === launcher && isAwakeChargeView(view)) {
                        observeChargeTouch(args(0).value as MotionEvent)
                    }
                }
            }
            installDozePlayback()
            installNotificationPriority()
            lifecycle {
                onAttach(replay = true) {
                    val ctx = hookAppContext ?: this.context
                    main.post { attach(ctx.applicationContext ?: ctx) }
                }
            }
        }
    }

    private fun attach(ctx: Context) {
        if (context != null) return
        context = ctx
        YLog.info("[REAREye-Charging] attached enabled=${enabled()} persistent=${keepVisible()}")
        displays = ctx.getSystemService(DisplayManager::class.java)
        displays?.registerDisplayListener(displayListener, main)
        receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val raw = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                        val updatedLevel = if (raw >= 0 && scale > 0) ((raw.toLong() * 100L) / scale).toInt().coerceIn(0, 100) else -1
                        if (updatedLevel != level) {
                            socDecimal = null; socRate = 0; socSampleAt = 0L
                        }
                        level = updatedLevel
                        plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                        wireless = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == BatteryManager.BATTERY_PLUGGED_WIRELESS
                        status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                    }
                    Intent.ACTION_POWER_CONNECTED -> {
                        plugged = true
                        suppressed = false
                        YLog.info("[REAREye-Charging] power connected enabled=${enabled()}")
                        if (enabled()) {
                            deadline = SystemClock.uptimeMillis() + 8_000L
                            nativeNotificationManager?.let(::deferNativeNotification)
                            YLog.info("[REAREye-Charging] priority acquired durationMs=8000")
                            if (!keepWhenAsleep() || dozeService == null) rearDisplay()?.let(::wakeRearDisplay)
                            main.removeCallbacks(timeout)
                            main.postDelayed(timeout, 8_000L)
                        }
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> { plugged = false; chargeSpeed = 0; carChargeMode = false; chargingWatts = -1; socDecimal = null; socRate = 0; socSampleAt = 0L; finishEntryPriority() }
                }
                refresh()
            }
        }
        ctx.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }, Context.RECEIVER_NOT_EXPORTED)
        chargeReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "hk.uwu.reareye.REAR_CHARGING_STATUS") {
                    chargeSpeed = intent.getIntExtra("speed", 0).coerceIn(0, 3)
                    carChargeMode = intent.getBooleanExtra("carMode", false)
                    chargingWatts = intent.getIntExtra("watts", -1)
                    doubleChargeClosed = intent.getBooleanExtra("doubleClosed", false)
                    val fraction = intent.getIntExtra("socDecimal", -1)
                    val sampledAt = intent.getLongExtra("socAt", 0L)
                    if (fraction in 0..99 && intent.getIntExtra("socLevel", -1) == level &&
                        sampledAt > socSampleAt) {
                        socDecimal = fraction
                        socRate = intent.getIntExtra("socRate", 0).coerceAtLeast(0)
                        socSampleAt = sampledAt
                    }
                } else {
                    // The OEM decimal is measured; never invent a progressing percentage.
                    val fraction = intent.getIntExtra("miui.intent.extra.soc_decimal", -1)
                    if (fraction !in 0..99) return
                    socDecimal = fraction
                    socRate = intent.getIntExtra("miui.intent.extra.soc_decimal_rate", 0).coerceAtLeast(0)
                    socSampleAt = SystemClock.elapsedRealtime()
                }
                refresh()
            }
        }
        ctx.registerReceiver(chargeReceiver, IntentFilter("hk.uwu.reareye.REAR_CHARGING_STATUS"),
            "android.permission.DEVICE_POWER", main, Context.RECEIVER_EXPORTED)
        // The vendor SOC sender uses the same receiver contract as the OEM
        // controller; it need not hold the module bridge's DEVICE_POWER permission.
        ctx.registerReceiver(chargeReceiver, IntentFilter("miui.intent.action.ACTION_SOC_DECIMAL"),
            null, main, Context.RECEIVER_EXPORTED)
        ctx.sendBroadcast(Intent("hk.uwu.reareye.REAR_CHARGING_STATUS_REQUEST").setPackage("com.android.systemui"))
    }

    private fun PackageScope.installDozePlayback() {
        runCatching {
            val type = "com.xiaomi.subscreencenter.doze.DozeService".toClass()
            val service = type.resolve()
            setDozeState = type.getMethod("setDozeScreenState", Int::class.javaPrimitiveType!!)
            service.firstMethod { name = "setDozeScreenState"; parameters(Int::class.javaPrimitiveType!!) }.hook {
                hotReloadPolicy = HotReloadPolicy.REINSTALL
                before {
                    if (instance !== dozeService) return@before
                    main.removeCallbacks(restoreDozeState)
                    nativeDozeState = args(0).value as Int
                    appliedDozeState = playbackDozeState()
                    args(0).value = appliedDozeState
                }
                after { if (instance === dozeService) main.post { refresh() } }
            }
            service.firstMethod { name = "onDreamingStarted"; parameterCount = 0 }.hook {
                hotReloadPolicy = HotReloadPolicy.REINSTALL
                before {
                    dozeService = instance as? DreamService ?: return@before
                    cancelChargeTap()
                    suppressed = false
                }
                after { refresh() }
            }
            listOf("onDreamingStopped", "onDestroy").forEach { name ->
                service.firstMethod { this.name = name; parameterCount = 0 }.hook {
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    before {
                        if (instance !== dozeService) return@before
                        main.removeCallbacks(restoreDozeState)
                        dozeService = null
                        nativeDozeState = Display.STATE_UNKNOWN
                        appliedDozeState = Display.STATE_UNKNOWN
                        main.post { refresh() }
                    }
                }
            }
            main.post {
                if (dozeService != null) return@post
                // A reload can occur after the factory dream has already started.
                runCatching {
                    val threadType = Class.forName("android.app.ActivityThread")
                    val thread = threadType.getDeclaredMethod("currentActivityThread").apply { isAccessible = true }.invoke(null)
                    val services = threadType.getDeclaredField("mServices").apply { isAccessible = true }
                        .get(thread) as? Map<*, *>
                    val isDozing = type.getMethod("isDozing")
                    val current = services?.values?.filterIsInstance<DreamService>()
                        ?.firstOrNull { type.isInstance(it) && isDozing.invoke(it) == true } ?: return@runCatching
                    dozeService = current
                    val recovered = type.getMethod("getDozeScreenState").invoke(current) as Int
                    nativeDozeState = if (recovered == Display.STATE_DOZE) Display.STATE_DOZE_SUSPEND else recovered
                    appliedDozeState = recovered
                    refresh()
                }.onFailure { YLog.warn("Live rear AOD service recovery unavailable", it) }
            }
        }.onFailure { YLog.warn("Rear charging AOD unavailable", it) }
    }

    private fun playbackDozeState(): Int = when {
        enabled() && keepWhenAsleep() && plugged && level >= 0 &&
            (nativeDozeState == Display.STATE_ON || nativeDozeState == Display.STATE_DOZE_SUSPEND) -> Display.STATE_DOZE
        else -> nativeDozeState
    }

    private fun synchronizeDozeState(allowDelay: Boolean = false) {
        val service = dozeService ?: return
        val target = playbackDozeState()
        if (nativeDozeState == Display.STATE_UNKNOWN || appliedDozeState == target) {
            main.removeCallbacks(restoreDozeState)
            return
        }
        if (allowDelay && appliedDozeState == Display.STATE_DOZE && target == Display.STATE_DOZE_SUSPEND) {
            if (!main.hasCallbacks(restoreDozeState)) main.postDelayed(restoreDozeState, DOZE_RESTORE_DELAY_MS)
            return
        }
        main.removeCallbacks(restoreDozeState)
        runCatching { setDozeState?.invoke(service, nativeDozeState) }
            .onFailure { YLog.warn("Rear charging could not update the AOD display state", it) }
    }

    private fun rearDisplay(): Display? = displays?.displays?.firstOrNull {
        it.displayId != Display.DEFAULT_DISPLAY && runCatching {
            val internal = Display::class.java.getDeclaredField("TYPE_INTERNAL").getInt(null)
            Display::class.java.getMethod("getType").invoke(it) == internal
        }.getOrDefault(false)
    }

    private fun refresh(allowHandoff: Boolean = true) {
        if (context == null) return
        if (playbackDozeState() == Display.STATE_DOZE) synchronizeDozeState(allowDelay = false)
        val display = rearDisplay()
        val inDoze = dozeService != null
        val rearOn = !inDoze && display?.state == Display.STATE_ON
        val rearSlept = rearWasOn && !rearOn
        if (rearOn && !rearWasOn) tapReadyAt = SystemClock.uptimeMillis() + TAP_CONFIRM_MS
        rearWasOn = rearOn
        if (suppressed && rearSlept) {
            suppressed = false
            YLog.info("[REAREye-Charging] rearmed after display sleep")
        }
        if (rearOn && showingInDoze && liquid != null) {
            suppressed = true
            YLog.info("[REAREye-Charging] fading out after wake from AOD")
            dismiss(animate = true)
            if (deadline != 0L) finishEntryPriority()
            return
        }
        val awakePlayback = keepVisible() || ownsEntryPriority()
        val ambientPlayback = keepWhenAsleep()
        val requested = if (inDoze) ambientPlayback && display?.state == Display.STATE_DOZE
            && appliedDozeState == Display.STATE_DOZE else rearOn && awakePlayback
        if (!enabled() || suppressed || !plugged || level < 0 || display == null) {
            val hadView = liquid != null
            dismiss(animate = !inDoze)
            synchronizeDozeState(allowDelay = hadView)
            return
        }
        if (!requested) {
            val view = liquid
            val wakingFromDoze = showingInDoze && ((!inDoze && display.state == Display.STATE_DOZE) ||
                (inDoze && display.state == Display.STATE_ON))
            val enteringDoze = awakePlayback && ambientPlayback && appliedDozeState != Display.STATE_OFF
            if (allowHandoff && (wakingFromDoze || enteringDoze) &&
                view?.isAttachedToWindow == true && view.display?.displayId == display.displayId) {
                // DisplayManager and the factory dream callback can arrive in either order.
                // Keep the charge view covering the same launcher throughout that gap.
                cancelChargeTap()
                view.isClickable = false
                view.keepScreenOn = false
                if (!main.hasCallbacks(finishDisplayHandoff)) {
                    main.postDelayed(finishDisplayHandoff, DISPLAY_HANDOFF_MS)
                }
            } else {
                val hadView = view != null
                if (showingInDoze && display.state == Display.STATE_ON) suppressed = true
                dismiss(animate = display.state == Display.STATE_ON)
                synchronizeDozeState(allowDelay = hadView)
            }
            return
        }
        main.removeCallbacks(finishDisplayHandoff)
        val host = launcher?.takeUnless { it.isFinishing || it.isDestroyed } ?: findLauncher()?.also(::adoptLauncher)
        val root = host?.takeIf { it.display?.displayId == display.displayId }?.window?.decorView as? ViewGroup
        if (root == null || !root.isAttachedToWindow) {
            dismiss()
            main.removeCallbacks(awaitHost)
            if (requested) main.postDelayed(awaitHost, 150L)
            return
        }
        if (overlayHost !== root || shownStyle != style()) dismiss(animate = false)
        val label = when (status) {
            BatteryManager.BATTERY_STATUS_FULL -> "已充满"
            BatteryManager.BATTERY_STATUS_CHARGING -> {
                val prefix = if (wireless) "无线" else ""
                when {
                    chargeSpeed >= 2 -> "${prefix}超级快充"
                    chargeSpeed == 1 -> "${prefix}快速充电"
                    else -> "${prefix}正在充电"
                }
            }
            else -> "已连接电源 · 暂停充电"
        }
        if (liquid == null) {
            runCatching {
                if (SystemClock.elapsedRealtime() - socSampleAt > 10_000L) {
                    socDecimal = null; socRate = 0; socSampleAt = 0L
                }
                // Use the existing rear launcher window. Android 17 can reject a new
                // Presentation window on this internal secondary display after wake.
                val selectedStyle = style()
                val view: RearChargingView = when (selectedStyle) {
                    0 -> RearMrssChargingView(root.context)
                    2, 3 -> RearLeicaChargingView(root.context, green = selectedStyle == 3)
                    else -> RearChargingLiquidView(root.context)
                }
                view.tag = "reareye_charging_overlay"
                shownStyle = selectedStyle
                showingInDoze = inDoze
                view.elevation = 32_000f
                view.setOnClickListener { confirmChargeTap(view, host) }
                view.isClickable = rearOn
                view.keepScreenOn = rearOn && ownsEntryPriority()
                overlayHost = root
                liquid = view
                if (!blocksFaceAuth) {
                    blocksFaceAuth = true
                    view.quickEntry = RearAppFaceAuth.cancelForCharging?.invoke() == true
                }
                root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                view.updateNativeCharge(chargeSpeed, chargingWatts, doubleChargeClosed, socRate, socSampleAt, carChargeMode)
                view.updateBattery(level, label, animateEntry = true, socDecimal = socDecimal)
                YLog.info("[REAREye-Charging] shown style=$selectedStyle level=$level hostDisplay=${host.display?.displayId} doze=$inDoze")
            }.onFailure {
                dismiss(animate = false)
                YLog.warn("Rear liquid charging host unavailable: ${it.javaClass.simpleName}")
            }
        } else {
            if (showingInDoze != inDoze) {
                cancelChargeTap()
                lastTapDown = 0L
                showingInDoze = inDoze
                YLog.info("[REAREye-Charging] continued on existing view doze=$inDoze")
            }
            liquid?.isClickable = rearOn
            liquid?.keepScreenOn = rearOn && ownsEntryPriority()
            liquid?.updateNativeCharge(chargeSpeed, chargingWatts, doubleChargeClosed, socRate, socSampleAt, carChargeMode)
            liquid?.updateBattery(level, label, socDecimal = socDecimal)
        }
    }

    private fun isAwakeChargeView(view: RearChargingView): Boolean =
        liquid === view && view.isAttachedToWindow && !showingInDoze && dozeService == null &&
            view.display?.state == Display.STATE_ON

    private fun observeChargeTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelChargeTap()
                val doubleTap = lastTapDown != 0L && event.eventTime - lastTapDown < NATIVE_DOUBLE_TAP_MS &&
                    abs(event.rawX - lastTapX) < NATIVE_DOUBLE_TAP_DISTANCE &&
                    abs(event.rawY - lastTapY) < NATIVE_DOUBLE_TAP_DISTANCE
                lastTapDown = event.eventTime
                lastTapX = event.rawX
                lastTapY = event.rawY
                // A wake gesture can still be finishing when the launcher receives touch again.
                allowChargeTap = !doubleTap && event.downTime >= tapReadyAt
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancelChargeTap()
        }
    }

    private fun confirmChargeTap(view: RearChargingView, host: Activity) {
        if (!allowChargeTap || !isAwakeChargeView(view)) return
        cancelChargeTap()
        val tap = Runnable {
            pendingChargeTap = null
            if (isAwakeChargeView(view)) {
                suppressed = true
                YLog.info("[REAREye-Charging] dismissed by confirmed single tap")
                returnToDesktop(host)
                finishEntryPriority()
            }
        }
        pendingChargeTap = tap
        main.postDelayed(tap, TAP_CONFIRM_MS)
    }

    private fun cancelChargeTap() {
        allowChargeTap = false
        pendingChargeTap?.let(main::removeCallbacks)
        pendingChargeTap = null
    }

    private fun ownsEntryPriority(): Boolean = enabled() && plugged && !suppressed &&
        SystemClock.uptimeMillis() < deadline

    private fun deferNativeNotification(manager: Any): Boolean {
        val reload = reloadNativeNotifications ?: return false
        return synchronized(deferredNotificationRefreshes) {
            if (!ownsEntryPriority()) return@synchronized false
            deferredNotificationRefreshes[manager] = { reload(manager) }
            true
        }
    }

    private fun finishEntryPriority() {
        main.removeCallbacks(timeout)
        val pending = synchronized(deferredNotificationRefreshes) {
            deadline = 0L
            deferredNotificationRefreshes.values.toList().also { deferredNotificationRefreshes.clear() }
        }
        refresh()
        YLog.info("[REAREye-Charging] priority released notificationRefreshes=${pending.size}")
        pending.forEach { reload -> main.post {
            runCatching(reload).onFailure { YLog.warn("Deferred rear notification refresh unavailable: ${it.javaClass.simpleName}") }
        } }
    }

    private fun PackageScope.installNotificationPriority() {
        runCatching {
            val bridge = runtime.manage(createDexKitCacheBridge(
                packageName = hookAppInfo.packageName,
                packageVersionCode = resolveHookPackageVersionCode(hookSystemContext, hookAppInfo.packageName, hookAppInfo.sourceDir),
                sourceDir = hookAppInfo.sourceDir,
                dataDir = hookAppInfo.dataDir,
            ))
            val show = requireNotNull(resolveDexKitMethodInjectionPoint(bridge, "SSC_CHARGE_PRIORITY_NOTIFICATION_SHOW") {
                findMethod { matcher { paramCount(1); returnType = "void"; usingStrings("post notification: ") } }.singleOrNull()
            })
            val reload = requireNotNull(resolveDexKitMethodInjectionPoint(bridge, "SSC_CHARGE_PRIORITY_NOTIFICATION_RELOAD") {
                findMethod { matcher { declaredClass = show.className; paramCount(0); returnType = "void";
                    usingStrings("load keyguard notifications cursor is null") } }.singleOrNull()
            })
            val reloadMethod = reload.className.toClass().getDeclaredMethod(reload.methodName).apply { isAccessible = true }
            reloadNativeNotifications = { manager -> reloadMethod.invoke(manager) }
            show.className.toClass().resolve().firstMethod { name = show.methodName; parameterCount = 1 }.hook {
                hotReloadPolicy = HotReloadPolicy.REINSTALL
                before {
                    val manager = instance ?: return@before
                    nativeNotificationManager = manager
                    if (deferNativeNotification(manager)) result = null
                }
            }
            YLog.info("[REAREye-Charging] notification priority installed")
        }.onFailure { YLog.warn("Rear notification priority unavailable: ${it.javaClass.simpleName}") }
    }

    private fun returnToDesktop(host: Activity) {
        fun panel(name: String): android.view.View? =
            host.findViewById(host.resources.getIdentifier(name, "id", host.packageName))
        runCatching {
            panel("smart_assistant_panel")?.let {
                it.javaClass.getMethod("setAssistantVisibleImpl", Boolean::class.javaPrimitiveType)
                    .invoke(it, false)
            }
            panel("notification_panel")?.let {
                it.javaClass.getMethod("e", String::class.java).invoke(it, "rearChargingDismissed")
            }
        }.onFailure { YLog.warn("Rear charging desktop return unavailable", it) }
    }

    private fun adoptLauncher(host: Activity) {
        if (host.display?.displayId == Display.DEFAULT_DISPLAY) return
        if (launcher !== host) dismiss()
        launcher = host
    }

    private fun wakeRearDisplay(display: Display) {
        // Target only the rear display; never wake the main display or keep a wake loop.
        // HyperOS' own rear launcher uses this display-aware PowerManager overload.
        // An injected KEYCODE_WAKEUP alone may leave the rear in DOZE on Android 17.
        val nativeWake = runCatching {
            val power = context?.getSystemService(PowerManager::class.java) ?: return@runCatching false
            PowerManager::class.java.getMethod(
                "wakeUp", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                String::class.java, Int::class.javaPrimitiveType,
            ).invoke(power, SystemClock.uptimeMillis(), 2, "REAREye:rearCharging", display.displayId)
            true
        }.getOrDefault(false)
        if (nativeWake) return
        runCatching {
            val managerClass = Class.forName("android.hardware.input.InputManagerGlobal")
            val manager = managerClass.getMethod("getInstance").invoke(null)
            val inject = managerClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            val setDisplay = InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            val now = SystemClock.uptimeMillis()
            for (action in arrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
                val event = KeyEvent(now, now, action, KeyEvent.KEYCODE_WAKEUP, 0)
                event.source = InputDevice.SOURCE_KEYBOARD
                setDisplay.invoke(event, display.displayId)
                inject.invoke(manager, event, 0)
            }
        }.onFailure { YLog.warn("Rear charge wake unavailable: ${it.javaClass.simpleName}") }
    }

    private fun dismiss(animate: Boolean = true) {
        main.removeCallbacks(awaitHost)
        main.removeCallbacks(finishDisplayHandoff)
        val view = liquid
        val root = overlayHost
        liquid = null
        overlayHost = null
        showingInDoze = false
        if (view != null) {
            cancelChargeTap()
            lastTapDown = 0L
            if (animate && view.isAttachedToWindow && view.display?.state == Display.STATE_ON) {
                exiting.add(view)
                view.exit {
                    exiting.remove(view)
                    releaseFaceBlockIfHidden()
                    runCatching { root?.removeView(view) }
                }
            } else { view.stopTransitions(); runCatching { root?.removeView(view) } }
            YLog.info("[REAREye-Charging] dismissed")
        }
        releaseFaceBlockIfHidden()
    }

    private fun releaseFaceBlockIfHidden() {
        if (liquid == null && exiting.isEmpty()) blocksFaceAuth = false
    }

    override fun onHotReloadQuiesce() {
        val done = CountDownLatch(1)
        val cleanup = Runnable {
            try {
                preferencesSubscription?.close()
                preferencesSubscription = null
                main.removeCallbacksAndMessages(null)
                keepWhenAsleep = { false }
                dismiss(animate = false)
                exiting.toList().forEach { view -> view.stopTransitions(); runCatching { (view.parent as? ViewGroup)?.removeView(view) } }
                exiting.clear()
                releaseFaceBlockIfHidden()
                synchronizeDozeState(allowDelay = false)
                dozeService = null
                setDozeState = null
                displays?.unregisterDisplayListener(displayListener)
                receiver?.let { context?.unregisterReceiver(it) }
                chargeReceiver?.let { context?.unregisterReceiver(it) }
                receiver = null
                chargeReceiver = null
                displays = null
                context = null
                launcher = null
                deadline = 0L
                synchronized(deferredNotificationRefreshes) { deferredNotificationRefreshes.clear() }
                nativeNotificationManager = null
                reloadNativeNotifications = null
                findLauncher = { null }
                enabled = { false }
                keepVisible = { false }
            } finally { done.countDown() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
        else { main.post(cleanup); check(done.await(3, TimeUnit.SECONDS)) { "Charge cleanup timed out" } }
    }
}
