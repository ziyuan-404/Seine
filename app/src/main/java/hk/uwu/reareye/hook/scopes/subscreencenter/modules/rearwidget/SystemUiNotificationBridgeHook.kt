package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import com.highcapable.kavaref.KavaRef.Companion.asResolver
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppContext
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.RearNotificationDefaults
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.roxyhook.android.lifecycle.lifecycle
import java.util.concurrent.ConcurrentHashMap

class SystemUiNotificationBridgeHook : RoxyHooker() {
    companion object {
        private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
        private const val TAG = "REAREye-NotifBridge"
        private const val CHARGE_SNAPSHOT = "hk.uwu.reareye.native-charge-snapshot"
        private const val CHARGE_DECIMAL = "hk.uwu.reareye.native-charge-decimal"
        private const val SNAPSHOT = "hk.uwu.reareye.visible-notification-keys"
    }

    private val activeSnapshots = ConcurrentHashMap<String, NotificationRouteSnapshot>()
    private val routeClient = NotificationRouteBridgeClient()
    private val main = Handler(Looper.getMainLooper())
    private var visibleKeys: List<String>? = null
    private var visibleGroups: Map<String, String> = emptyMap()
    private var syncEnabled: () -> Boolean = { false }
    private var chargingStatus: android.content.Intent? = null
    private var chargeRequestReceiver: android.content.BroadcastReceiver? = null
    private var decimalReceiver: android.content.BroadcastReceiver? = null
    private val chargingCapture = ThreadLocal.withInitial { false }
    private fun dispatchChargingStatus() {
        val status = chargingStatus ?: return
        listOf("socLevel", "socDecimal", "socRate", "socAt").forEach(status::removeExtra)
        val decimal = System.getProperties()[CHARGE_DECIMAL] as? Bundle
        if (decimal != null && decimal.getInt("socLevel", -1) == status.getIntExtra("level", -2) &&
            android.os.SystemClock.elapsedRealtime() - decimal.getLong("socAt") in 0L..10_000L)
            status.putExtras(decimal)
        hostContext?.sendBroadcast(status)
    }
    private val dispatchCharging = Runnable { dispatchChargingStatus() }

    private fun updateNativeCharging(status: Any, controller: Any?) {
        if (chargingCapture.get()) return
        chargingCapture.set(true)
        try {
            fun field(name: String) = runCatching { status.javaClass.getField(name).getInt(status) }.getOrDefault(-1)
            fun controllerFlag(name: String) = runCatching { controller?.javaClass?.getField(name)?.getBoolean(controller) == true }.getOrDefault(false)
            val wire = field("wireState")
            // Pure equivalent of ChargeUtils.getChargeSpeed. Never call helpers
            // which resolve MiuiChargeController's DI provider from inside its
            // constructor-triggered battery callback: that recursively constructs
            // the controller and stalls SystemUI/credential input.
            val speed = if (wire == 10 || wire == 11) when (field("chargeDeviceType")) {
                4 -> 3; 2, 3 -> 2; 1 -> 1; else -> 0
            } else 0
            val doubleSupported = runCatching {
                Class.forName("android.os.SystemProperties").getMethod("getBoolean", String::class.java, Boolean::class.javaPrimitiveType)
                    .invoke(null, "persist.vendor.accelerate.charge", false) as Boolean
            }.getOrDefault(false)
            val doubleClosed = doubleSupported && (!controllerFlag("mIsFastCharge") || field("miuiChargerVerified") == 0) &&
                !controllerFlag("mWirelessCharging")
            chargingStatus = android.content.Intent("hk.uwu.reareye.REAR_CHARGING_STATUS")
                .setPackage("com.xiaomi.subscreencenter")
                .putExtra("speed", speed).putExtra("watts", field("maxChargingWattage"))
                .putExtra("carMode", field("carChargeMode") == 1).putExtra("doubleClosed", doubleClosed)
                .putExtra("plugged", field("plugged"))
                .putExtra("level", field("level"))
            System.getProperties()[CHARGE_SNAPSHOT] = chargingStatus
            // No IPC, class initialization, or dependency resolution on the
            // native callback stack. Coalesce delivery onto the next main turn.
            main.removeCallbacks(dispatchCharging)
            main.post(dispatchCharging)
        } finally { chargingCapture.set(false) }
    }
    private val visibilityHeartbeat = object : Runnable {
        override fun run() {
            dispatchVisibleKeys()
            dispatchChargingStatus()
            main.postDelayed(this, 5_000L)
        }
    }

    @Volatile
    private var hostContext: Context? = null

    override fun onHotReloadQuiesce() {
        val unbound = routeClient.unbind()
        if (!unbound) {
            throw IllegalStateException("$TAG failed to unbind route bridge during reload")
        }
        activeSnapshots.clear()
        main.removeCallbacks(visibilityHeartbeat)
        main.removeCallbacks(dispatchCharging)
        visibleKeys = null
        visibleGroups = emptyMap()
        chargeRequestReceiver?.let { hostContext?.unregisterReceiver(it) }
        chargeRequestReceiver = null
        decimalReceiver?.let { hostContext?.unregisterReceiver(it) }
        decimalReceiver = null
        chargingStatus = null
        syncEnabled = { false }
        hostContext = null
    }

    override fun PackageScope.onHook() {
        loadApp(SYSTEM_UI_PACKAGE) {
            runCatching {
                "com.miui.charge.MiuiChargeController".toClass().resolve().firstMethod {
                    name = "checkBatteryStatus"; parameterCount = 2
                }.hook {
                    after {
                        val status = args.firstOrNull() ?: return@after
                        runCatching { updateNativeCharging(status, instance) }
                            .onFailure { YLog.warn("Native rear charging classification unavailable", it) }
                    }
                }
            }.onFailure { YLog.warn("Native rear charging classification unavailable", it) }
            syncEnabled = { hookPrefs.getBoolean(ConfigKeys.REAR_NOTIFICATION_SYNC, RearNotificationDefaults.ENABLED) }
            // Bundle/String survive replacement of the module classloader. Without
            // this, a reload clears every rear card until ShadeListBuilder runs again.
            (System.getProperties()[SNAPSHOT] as? Bundle)?.let { saved ->
                visibleKeys = saved.getStringArrayList("keys")?.toList()
                val groups = saved.getStringArrayList("groups").orEmpty()
                visibleGroups = saved.getStringArrayList("keys").orEmpty().zip(groups).toMap()
            }
            debugLog("loadApp process=$processName package=$packageName")

            this.lifecycle {
                // Rebind from the restored attach event after the old generation unbinds.
                onAttach(replay = true) {
                    val context = hookAppContext ?: this.context
                    hostContext = context.applicationContext ?: context
                    chargingStatus = System.getProperties()[CHARGE_SNAPSHOT] as? android.content.Intent
                    if (decimalReceiver == null) {
                        decimalReceiver = object : android.content.BroadcastReceiver() {
                            override fun onReceive(context: Context, intent: android.content.Intent) {
                                val fraction = intent.getIntExtra("miui.intent.extra.soc_decimal", -1)
                                if (fraction !in 0..99) return
                                val level = context.getSystemService(android.os.BatteryManager::class.java)
                                    .getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                                System.getProperties()[CHARGE_DECIMAL] = Bundle().apply {
                                    putInt("socLevel", level)
                                    putInt("socDecimal", fraction)
                                    putInt("socRate", intent.getIntExtra("miui.intent.extra.soc_decimal_rate", 0).coerceAtLeast(0))
                                    putLong("socAt", android.os.SystemClock.elapsedRealtime())
                                }
                                main.post(dispatchCharging)
                            }
                        }.also { receiver ->
                            // Match MiuiChargeController's original receiver. This
                            // vendor broadcast is not sticky; retain its timestamp
                            // for a rear launcher which attaches after it was sent.
                            context.registerReceiver(receiver, android.content.IntentFilter("miui.intent.action.ACTION_SOC_DECIMAL"),
                                null, main, Context.RECEIVER_EXPORTED)
                        }
                    }
                    if (chargeRequestReceiver == null) {
                        chargeRequestReceiver = object : android.content.BroadcastReceiver() {
                            override fun onReceive(context: Context, intent: android.content.Intent) { main.post(dispatchCharging) }
                        }.also { receiver ->
                            context.registerReceiver(receiver, android.content.IntentFilter("hk.uwu.reareye.REAR_CHARGING_STATUS_REQUEST"),
                                "android.permission.DEVICE_POWER", main, Context.RECEIVER_EXPORTED)
                        }
                    }
                    debugLog(
                        "onAttach hostContext=${hostContext?.packageName} action=${NotificationRouteBridgeContract.Action.REQUEST_BINDER} target=${NotificationRouteBridgeContract.HOOK_HOST_PACKAGE}"
                    )
                    bindRouteBridge("app_attach")
                    main.removeCallbacks(visibilityHeartbeat)
                    main.post(visibilityHeartbeat)
                }
            }

            runCatching {
                "com.android.systemui.statusbar.notification.collection.ShadeListBuilder".toClass().resolve()
                    .firstMethod { name = "buildList"; parameterCount = 0 }.hook {
                        after {
                            if (!hookPrefs.getBoolean(ConfigKeys.REAR_NOTIFICATION_SYNC, RearNotificationDefaults.ENABLED)) return@after
                            runCatching {
                                val entries = instance!!.asResolver().firstField { name = "mReadOnlyNotifList" }.get<List<*>>()
                                    ?: return@runCatching
                                val keys = linkedSetOf<String>()
                                val groups = linkedMapOf<String, String>()
                                fun collect(entry: Any?, depth: Int, parent: String? = null, keyguardOnly: Boolean = false) {
                                    if (entry == null || depth > 8) return
                                    var group = parent
                                    val representative = entry.javaClass.methods.firstOrNull {
                                        it.name == "getRepresentativeEntry" && it.parameterCount == 0
                                    }?.invoke(entry)
                                    if (representative != null) {
                                        val field = representative.javaClass.getField("mSbn")
                                        val sbn = field.get(representative) as? StatusBarNotification
                                        if (sbn != null) {
                                            keys.add(sbn.key)
                                            if (group == null) group = if (keyguardOnly && sbn.isGroup) sbn.groupKey else sbn.key
                                            // Keyguard recency filtering is a visibility fallback,
                                            // not permission to flatten a group already built by the shade.
                                            if (!keyguardOnly || sbn.key !in groups) groups[sbn.key] = group!!
                                        }
                                    }
                                    // Group children survive summary filtering; walk those too.
                                    val children = (entry.javaClass.methods.firstOrNull {
                                        it.name == "getRawChildren" && it.parameterCount == 0
                                    }?.invoke(entry) ?: entry.javaClass.fields.firstOrNull {
                                        it.name == "rawChildren"
                                    }?.get(entry)) as? List<*>
                                    children?.forEach { collect(it, depth + 1, group, keyguardOnly) }
                                }
                                entries.forEach { collect(it, 0) }
                                val reasons = linkedMapOf<String, Int>()
                                val allEntries = instance!!.asResolver().firstField { name = "mAllEntries" }.get<Collection<*>>()
                                    .orEmpty()
                                allEntries.filterNotNull().forEach { entry ->
                                    val state = entry.javaClass.getField("attachState").get(entry)
                                    val filter = state.javaClass.getField("excludingFilter").get(state)
                                    val name = if (filter == null) "visible" else
                                        filter.javaClass.getField("mName").get(filter) as String
                                    reasons[name] = (reasons[name] ?: 0) + 1
                                    // Locking the front screen changes its shade filters. The
                                    // rear listener applies its own lock-screen privacy rules;
                                    // preserve notifications hidden only by keyguard recency.
                                    if (name == "KeyguardCoordinator" || name == "OriginalUnseenKeyguardCoordinator")
                                        collect(entry, 0, keyguardOnly = true)
                                }
                                visibleKeys = keys.toList()
                                visibleGroups = groups
                                System.getProperties()[SNAPSHOT] = Bundle().apply {
                                    putStringArrayList("keys", ArrayList(keys))
                                    putStringArrayList("groups", ArrayList(keys.map { groups[it] ?: it }))
                                }
                                if (hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false))
                                    YLog.debug("[REAREye-NotifBridge] visible shade keys=${keys.size} filters=$reasons")
                                dispatchVisibleKeys()
                            }.onFailure { YLog.warn("Rear visible notification snapshot unavailable: ${it.javaClass.simpleName}") }
                        }
                    }
                YLog.info("[REAREye-NotifBridge] shade visibility hook installed")
            }.onFailure { YLog.warn("Rear shade visibility hook unavailable: ${it.javaClass.simpleName}") }

            runCatching {
                val runnableClz =
                    $$$"com.android.systemui.statusbar.notification.MiuiNotificationListener$$ExternalSyntheticLambda2".toClass()
                        .resolve()
                runnableClz.firstMethod {
                    name = "run"
                }.hook {
                    after {
                        val sbn = instance!!.asResolver().firstField {
                            type(StatusBarNotification::class.java)
                        }.get<StatusBarNotification>()
                        handleNotificationPosted(sbn)
                    }
                }
            }.onFailure {
                debugLog("hook onNotificationPosted failed err=${it.message}")
            }.onSuccess {
                debugLog("hook onNotificationPosted installed")
            }

            runCatching {
                val runnableClz =
                    $$$"com.android.systemui.statusbar.notification.MiuiNotificationListener$$ExternalSyntheticLambda1".toClass()
                        .resolve()
                runnableClz.firstMethod {
                    name = "run"
                }.hook {
                    after {
                        val sbn = instance!!.asResolver().firstField {
                            type(StatusBarNotification::class.java)
                        }.get<StatusBarNotification>()
                        handleNotificationRemoved(
                            sbn = sbn,
                            removeReason = instance!!.asResolver().lastField {
                                type(Int::class.java)
                            }.get<Int>() ?: 1,
                        )
                    }
                }
            }.onFailure {
                debugLog("hook onNotificationRemoved failed err=${it.message}")
            }.onSuccess {
                debugLog("hook onNotificationRemoved installed")
            }
        }
    }

    private fun dispatchVisibleKeys() {
        if (!syncEnabled()) return
        val keys = visibleKeys ?: return
        routeClient.dispatch(NotificationRouteBridgeContract.Subchannel.VISIBLE_NOTIFICATION_KEYS, Bundle().apply {
            putStringArrayList(NotificationRouteBridgeContract.Keys.VISIBLE_KEYS, ArrayList(keys))
            putStringArrayList("shadeGroupKeys", ArrayList(keys.map { visibleGroups[it] ?: it }))
        })
    }

    private fun PackageScope.bindRouteBridge(reason: String): Boolean {
        val context = hostContext ?: run {
            debugLog("route bridge bind skipped reason=$reason hostContext=null")
            return false
        }
        debugLog(
            "route bridge bind start reason=$reason connected=${routeClient.isConnected()} action=${NotificationRouteBridgeContract.Action.REQUEST_BINDER}"
        )
        val ok = routeClient.bind(
            context = context,
            onConnected = {
                debugLog("route bridge connected reason=$reason")
            },
            onClosed = {
                debugLog("route bridge closed reason=$it")
            },
        )
        if (!ok) {
            debugLog(
                "route bridge handshake pending reason=$reason action=${NotificationRouteBridgeContract.Action.REQUEST_BINDER}"
            )
        }
        return ok
    }

    private fun PackageScope.handleNotificationPosted(sbn: StatusBarNotification?) {
        val current = sbn ?: return
        val snapshot = NotificationRouteSnapshot.fromStatusBarNotification(current)
        if (snapshot == null) {
            NotificationRouteSnapshot.identityKeyFor(current)?.let { key ->
                activeSnapshots.remove(key)?.let { removed ->
                    dispatchRemoved(removed, removeReason = 1, reason = "filtered_post")
                }
            }
            return
        }

        activeSnapshots[snapshot.stableKey()] = snapshot
        debugLog(
            "posted accepted key=${snapshot.stableKey()} cacheSize=${activeSnapshots.size} channel=${snapshot.channelId} pkg=${snapshot.packageName}"
        )
        dispatchPosted(snapshot, reason = "live_post")
    }

    private fun PackageScope.handleNotificationRemoved(
        sbn: StatusBarNotification?,
        removeReason: Int
    ) {
        val current = sbn ?: return
        val snapshot = NotificationRouteSnapshot.identityKeyFor(current)
            ?.let(activeSnapshots::remove)
            ?: NotificationRouteSnapshot.fromStatusBarNotification(
                current,
                requirePlainExtras = false,
            )
            ?: run {
                return
            }
        debugLog(
            "removed accepted key=${snapshot.stableKey()} cacheSize=${activeSnapshots.size} reason=$removeReason channel=${snapshot.channelId} pkg=${snapshot.packageName}"
        )
        dispatchRemoved(snapshot, removeReason, reason = "live_remove")
    }

    private fun PackageScope.dispatchPosted(snapshot: NotificationRouteSnapshot, reason: String) {
        val ok = routeClient.dispatch(
            NotificationRouteBridgeContract.Subchannel.NOTIFICATION_POSTED,
            snapshot.toBundle(),
        )
        if (!ok) {
            debugLog("dispatch posted failed key=${snapshot.stableKey()} reason=$reason")
        }
    }

    private fun PackageScope.dispatchRemoved(
        snapshot: NotificationRouteSnapshot,
        removeReason: Int,
        reason: String,
    ) {
        val ok = routeClient.dispatch(
            NotificationRouteBridgeContract.Subchannel.NOTIFICATION_REMOVED,
            snapshot.toRemovalBundle(removeReason),
        )
        if (!ok) {
            debugLog("dispatch removed failed key=${snapshot.stableKey()} reason=$reason")
        }
    }

    private fun PackageScope.debugLog(message: String) {
        if (hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false)) {
            YLog.debug("[$TAG] $message")
        }
    }
}
