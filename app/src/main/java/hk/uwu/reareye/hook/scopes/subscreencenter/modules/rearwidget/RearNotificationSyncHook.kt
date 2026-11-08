package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.app.KeyguardManager
import android.app.Notification
import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.MotionEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewConfiguration
import android.view.VelocityTracker
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Display
import android.view.Gravity
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.RemoteViews
import android.widget.TextView
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.BuildConfig
import hk.uwu.reareye.R
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.reareye.hook.utils.resolveDexKitMethodInjectionPoint
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.charging.RearChargingHook
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.NativeRearAppLaunch
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.RearAppFaceAuth
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.RearFaceSession
import hk.uwu.reareye.utils.NativeNotificationPrivacy
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.RearNotificationDefaults
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.roxyhook.android.lifecycle.lifecycle
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/** Uses SubscreenCenter's already-authorized listener, not a second notification listener. */
@OptIn(DexKitExperimentalApi::class)
class RearNotificationSyncHook : RoxyHooker() {
    private var launchPrefs: hk.uwu.reareye.hook.support.HookPrefs?=null
    private var preferencesSubscription:AutoCloseable?=null
    private val main = Handler(Looper.getMainLooper())
    private var listener: NotificationListenerService? = null
    private var enabled: () -> Boolean = { false }
    private var apps: () -> Set<String> = { emptySet() }
    private var debugEnabled: () -> Boolean = { false }
    private val active = LinkedHashMap<String, StatusBarNotification>()
    private val tokens = LinkedHashMap<String, String>()
    private val shown = LinkedHashSet<String>()
    private data class RenderVersion(val postTime: Long, val redacted: Boolean, val group: String)
    private val rendered = LinkedHashMap<String, RenderVersion>()
    private var receiverContext: Context? = null
    private var screenLocked = false
    private var visibleSourceKeys: List<String>? = null
    private var lastSyncSummary = ""
    private var sourceGroups: Map<String, String> = emptyMap()
    private var groupedSources: Map<String, List<StatusBarNotification>> = emptyMap()
    private val expandedGroups = linkedSetOf<String>()
    private val dismissing = linkedSetOf<String>()
    private val faceAttempted = mutableSetOf<String>()
    private var privacyPolicy: NativeNotificationPrivacy.Policy? = null
    private data class PrivacyPass(val locked: Boolean, val ranking: NotificationListenerService.RankingMap?)
    private var privacyPass: PrivacyPass? = null
    private var settingsObserver: android.database.ContentObserver? = null
    private val faceObservers = java.util.WeakHashMap<View, Pair<ViewTreeObserver, ViewTreeObserver.OnPreDrawListener>>()
    private var rearInAod = false
    private var aodChangedAt = 0L
    private var popup = java.lang.ref.WeakReference<ViewGroup>(null)
    private var popupSource: String? = null
    private var popupBinding: String? = null
    private var popupRetry: Runnable? = null
    private val groupReconciles = mutableMapOf<String, Runnable>()
    private var panelOwnsGesture = false
    private var continueOnFront: (StatusBarNotification) -> Boolean = { false }
    private val frontContinuations = linkedSetOf<String>()
    private val rankingRefresh = Runnable { reconcile() }
    private val privacyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                RearNotificationGestures.cancelTap()
                screenLocked = true
                faceAttempted.clear()
            }
            if (intent.action == Intent.ACTION_USER_PRESENT) {
                screenLocked = false
                frontContinuations.forEach { rendered.remove(it) }
                frontContinuations.clear()
            }
            if (intent.action == "android.intent.action.USER_SWITCHED" || intent.action == "miui.intent.action.SUB_SCREEN_OFF") {
                faceAttempted.clear(); privacyPolicy = null
            }
            main.post { reconcile() }
        }
    }

    override fun PackageScope.onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            launchPrefs=hookPrefs
            enabled = { hookPrefs.getBoolean(ConfigKeys.REAR_NOTIFICATION_SYNC, RearNotificationDefaults.ENABLED) }
            apps = { hookPrefs.getStringSet(ConfigKeys.REAR_NOTIFICATION_SYNC_APPS, RearNotificationDefaults.APPS) }
            debugEnabled = { hookPrefs.getBoolean(ConfigKeys.MORE_DEBUG, false) }
            continueOnFront = { sbn ->
                !NativeRearAppLaunch.canOpenNotification(sbn,launchPrefs?:hookPrefs,hookSystemContext)
            }
            preferencesSubscription=launchPrefs?.observe { key ->
                if(key==null||key==ConfigKeys.ACTIVITIES_WHITELIST_APPS||key==ConfigKeys.REAR_APP_ENABLED)
                    main.post { if(listener!=null) { rendered.clear();reconcile() } }
            }
            runCatching { installNativePanelBehavior() }.onFailure {
                YLog.warn("Rear native panel behavior unavailable: ${it.javaClass.simpleName}")
            }
            YLog.info("[REAREye-NotificationSync] installed enabled=${enabled()} selectedApps=${apps().size}")
            RearNotificationCards.onReady = { main.post { reconcile() } }
            RearNotificationCards.setVisibleSourceKeys = { keys, groups -> main.post {
                if (visibleSourceKeys != keys || sourceGroups != groups) {
                    visibleSourceKeys = keys
                    sourceGroups = groups
                    reconcile()
                }
            } }
            val service = "com.xiaomi.subscreencenter.smartassistant.SmartAssistantNotificationListenerService".toClass().resolve()
            service.firstMethod { name = "onListenerConnected"; parameterCount = 0 }.hook {
                after { (instance as? NotificationListenerService)?.let(::adopt) }
            }
            service.firstMethod { name = "onNotificationPosted"; parameterCount = 1 }.hook {
                before {
                    if (!enabled()) return@before
                    val host = instance as? NotificationListenerService ?: return@before
                    val sbn = args.firstOrNull() as? StatusBarNotification ?: return@before
                    if (Looper.myLooper() == Looper.getMainLooper()) {
                        attach(host)
                        reconcile()
                        if (sbn.key in shown) RearChargingHook.notificationAvailable?.invoke()
                    } else main.post {
                        attach(host)
                        reconcile()
                        if (sbn.key in shown) RearChargingHook.notificationAvailable?.invoke()
                    }
                }
                after {
                    val sbn = args.firstOrNull() as? StatusBarNotification ?: return@after
                    if (enabled() && (sbn.notification.extras.containsKey("miui.focus.param") ||
                                sbn.notification.extras.containsKey("miui.rear.param"))) {
                        // Let the stock handler finish its queued insertion. If it supplied
                        // a real persistent native card, remove our redundant fallback.
                        main.postDelayed({ active[sbn.key]?.let(::publish) }, 150L)
                    }
                }
            }
            service.firstMethod { name = "onNotificationRemoved"; parameterCount = 1 }.hook {
                after {
                    val sbn = args.firstOrNull() as? StatusBarNotification ?: return@after
                    main.post {
                        // A late removal must not remove a newer replacement with the same key.
                        if ((active[sbn.key]?.postTime ?: Long.MIN_VALUE) <= sbn.postTime) {
                            remove(sbn.key)
                            reconcile()
                        }
                    }
                }
            }
            service.firstMethod { name = "onListenerDisconnected"; parameterCount = 0 }.hook {
                after { main.post { clear(); listener = null } }
            }
            NotificationListenerService::class.java.resolve().firstMethod {
                name = "onNotificationRankingUpdate"; parameterCount = 1
            }.hook {
                after {
                    if (instance === listener && enabled()) {
                        main.removeCallbacks(rankingRefresh)
                        main.postDelayed(rankingRefresh, 150L)
                    }
                }
            }
            RemoteViews::class.java.resolve().firstMethod { name = "reapply"; parameterCount = 2 }.hook {
                after {
                    val view = args.getOrNull(1) as? View ?: return@after
                    if (view.id == R.id.rear_notification_card) bindCard(view)
                }
            }
            // q2.o.I normally calls apply(), adds a new root and removes E on
            // every notification update. Our reveal must keep the mounted root.
            // Scope this to our mirror layout; all stock widgets keep their path.
            runCatching {
                "q2.o".toClass().resolve().firstMethod { name = "I"; parameterCount = 1 }.hook {
                    before {
                        if (!enabled()) return@before
                        val widget = instance ?: return@before
                        fun field(name: String): Any? = generateSequence(widget.javaClass as Class<*>?) { it.superclass }
                            .firstNotNullOfOrNull { type -> runCatching {
                                type.getDeclaredField(name).apply { isAccessible = true }.get(widget)
                            }.getOrNull() }
                        val current = field("E") as? View ?: return@before
                        if (current.id != R.id.rear_notification_card || !current.isAttachedToWindow) return@before
                        val remote = field(if (field("k") == true) "D" else "C") as? RemoteViews ?: return@before
                        if (remote.layoutId != R.layout.rear_notification_card || remote.`package` != BuildConfig.APPLICATION_ID) return@before
                        runCatching { remote.reapply(current.context, current) }.onSuccess { result = null }
                            .onFailure { YLog.warn("Rear notification retained update unavailable", it) }
                    }
                }
            }.onFailure { YLog.warn("Rear notification retained host unavailable", it) }
            View::class.java.resolve().firstMethod { name = "onAttachedToWindow"; parameterCount = 0 }.hook {
                after {
                    val view = instance as? View ?: return@after
                    if (view.id == R.id.rear_notification_card) bindCard(view)
                }
            }
            this.lifecycle {
                onAttach(replay = true) {
                    // Hot reload does not reconnect an already-bound NotificationListener.
                    // Adopt the live service instead of waiting for the next notification.
                    main.post {
                        runCatching {
                            val threadType = Class.forName("android.app.ActivityThread")
                            val thread = threadType.getDeclaredMethod("currentActivityThread")
                                .apply { isAccessible = true }.invoke(null)
                            val services = threadType.getDeclaredField("mServices")
                                .apply { isAccessible = true }.get(thread) as? Map<*, *>
                            services?.values?.filterIsInstance<NotificationListenerService>()
                                ?.firstOrNull { it.javaClass.name ==
                                    "com.xiaomi.subscreencenter.smartassistant.SmartAssistantNotificationListenerService" }
                                ?.let(::adopt)
                        }.onFailure { YLog.warn("Live rear notification listener recovery unavailable", it) }
                    }
                }
            }
        }
    }

    private fun adopt(host: NotificationListenerService) { main.post {
        YLog.info("[REAREye-NotificationSync] listener connected enabled=${enabled()}")
        attach(host); reconcile()
    } }

    private fun attach(host: NotificationListenerService) {
        listener = host
        NativeRearMotion.hostLoader = host.classLoader
        if (receiverContext != null) return
        val ctx = host.applicationContext
        ctx.registerReceiver(privacyReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction("android.intent.action.USER_SWITCHED")
            addAction("miui.intent.action.SUB_SCREEN_OFF")
        }, Context.RECEIVER_NOT_EXPORTED)
        receiverContext = ctx
        RearFaceSession.attach(ctx)
        RearFaceSession.onChanged = {
            main.post {
                val refresh = {
                    faceAttempted.clear()
                    mountedCards.toList().forEach(::refreshMountedPrivacy)
                    reconcile()
                }
                // Authorization is wake-wide. Preloaded neighbor pages must
                // update now, independently of the current page's visual tail.
                refresh()
            }
        }
        settingsObserver = object : android.database.ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                privacyPolicy = null
                RearFaceSession.reset()
            }
        }.also { observer ->
            NativeNotificationPrivacy.settings.forEach { name ->
                ctx.contentResolver.registerContentObserver(Settings.Secure.getUriFor(name), false, observer)
            }
        }
    }

    private fun reconcile() {
        val host = listener
        val previous = privacyPass
        if (host != null) privacyPass = PrivacyPass(screenLocked ||
            host.getSystemService(KeyguardManager::class.java).isKeyguardLocked, host.currentRanking)
        try { reconcileCurrent() } finally { privacyPass = previous }
    }

    private fun reconcileCurrent() {
        if (!enabled()) {
            clear()
            RearNotificationCards.reconcile?.invoke(emptySet())
            return
        }
        val host = listener ?: return
        val snapshot = runCatching { host.activeNotifications?.toList() }.getOrNull() ?: return
        val keys = snapshot.mapTo(HashSet()) { it.key }
        snapshot.forEach { active[it.key] = it }
        val frontRanks = visibleSourceKeys.orEmpty().withIndex().associate { it.value to it.index }
        groupedSources = snapshot.filter(::sourceEligible).sortedBy { frontRanks[it.key] ?: Int.MAX_VALUE }.groupBy(::groupId)
        expandedGroups.retainAll(groupedSources.keys)
        // Also discard restored mirror records that are outside the current
        // whitelist, even before this process has populated its `shown` set.
        RearNotificationCards.reconcile?.invoke(snapshot.filter(::eligible).mapTo(HashSet()) { it.key })
        (active.keys.toList() + shown.toList()).filterNot { it in keys }.distinct().forEach(::remove)
        snapshot.forEach { publish(it) }
        refreshPopup()
        val candidates = snapshot.filter(::eligible)
        val summary = "source=${snapshot.size} visible=${visibleSourceKeys?.size} eligible=${candidates.size}" +
            " native=${candidates.count { RearNotificationCards.hasNativeCard?.invoke(it.key) == true }}" +
            " privateBlocked=${candidates.count { privacy(it) == null }} mirror=${shown.size}"
        if (lastSyncSummary != summary) {
            lastSyncSummary = summary
            YLog.info("[REAREye-NotificationSync] $summary")
        }
        if (debugEnabled()) YLog.debug("[REAREye-NotificationSync] reconciled sourceCount=${snapshot.size} mirrorCount=${shown.size} selectedApps=${apps().size} sourceUsers=${snapshot.groupingBy { it.uid / 100_000 }.eachCount()}")
    }

    private fun eligible(sbn: StatusBarNotification): Boolean {
        if (!sourceEligible(sbn)) return false
        val group = groupId(sbn)
        val members = groupedSources[group].orEmpty()
        return group in expandedGroups || members.firstOrNull()?.key == sbn.key
    }

    private fun groupId(sbn: StatusBarNotification): String = sourceGroups[sbn.key]
        ?: if (sbn.isGroup) sbn.groupKey else sbn.key

    private fun sourceEligible(sbn: StatusBarNotification): Boolean {
        if (!enabled()) return false
        val selected = apps()
        if (!RearNotificationDefaults.includes(selected, sbn.packageName)) return false
        // Only mirror keys admitted by the current user's SystemUI, from the
        // native listener's authorized snapshot. Visible cloned/work-profile
        // messages can belong to another UserHandle and must not be dropped.
        if (visibleSourceKeys?.contains(sbn.key) != true) return false
        val n = sbn.notification
        // SystemUI's "More notifications" shelf is a virtual navigation entry,
        // not a message. Mirroring it produces an empty "System UI" card.
        if (sbn.packageName == "com.android.systemui" && n.channelId == "id_aggregate") return false
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        // Keep the native call, alarm, countdown, and media controls.
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
            n.category == Notification.CATEGORY_CALL || n.category == Notification.CATEGORY_ALARM) return false
        val params = runCatching {
            val raw = n.extras.getString("miui.rear.param") ?: n.extras.getString("miui.focus.param")
            raw?.let(::JSONObject)?.let { it.optJSONObject("rear_param_v1") ?: it.optJSONObject("param_v2") ?: it }
        }.getOrNull()
        val business = params?.optString("business").orEmpty().ifBlank {
            params?.optJSONObject("baseInfo")?.optString("business").orEmpty()
        }
        return business !in setOf("incall", "alarm", "countdown", "music")
    }

    private fun usesTimerScene(sbn: StatusBarNotification) =
        sbn.packageName == "com.android.soundrecorder" ||
            NativeRearStopwatchCard.isStopwatch(sbn.packageName, sbn.notification)

    private fun privacy(sbn: StatusBarNotification): Boolean? {
        val host = listener ?: return null
        val locked = privacyPass?.locked ?: (screenLocked || host.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        val policy = privacyPolicy ?: NativeNotificationPrivacy.policy(host).also { privacyPolicy = it }
        val n = sbn.notification
        val ranking = NotificationListenerService.Ranking()
        val hasRanking = (privacyPass?.ranking ?: host.currentRanking)?.getRanking(sbn.key, ranking) == true
        val override = if (hasRanking) runCatching {
            ranking.javaClass.getMethod("getLockscreenVisibilityOverride").invoke(ranking) as Int
        }.getOrDefault(-1000) else -1000
        val visibility = if (override != -1000) override else n.visibility
        if (!policy.showNotifications || (hasRanking && ranking.isSuspended) || locked && visibility == Notification.VISIBILITY_SECRET) {
            return null
        }
        if (usesTimerScene(sbn)) return false
        if (RearFaceSession.isAuthorized()) return false
        if (policy.hidesDetails) return true
        return locked && visibility != Notification.VISIBILITY_PUBLIC &&
            (override == Notification.VISIBILITY_PRIVATE || !policy.allowPrivate)
    }

    private fun publish(sbn: StatusBarNotification, force: Boolean = false): Boolean {
        if (sbn.key in dismissing) return false
        if (!eligible(sbn)) { hide(sbn.key); return false }
        if (RearNotificationCards.hasNativeCard?.invoke(sbn.key) == true) {
            hide(sbn.key)
            return false
        }
        val host = listener ?: return false
        val redacted = privacy(sbn) ?: run { hide(sbn.key); return false }
        val token = tokens.getOrPut(sbn.key) { UUID.randomUUID().toString() }
        // Progress/ranking refreshes must not restart a settled handoff cue.
        // Keep recording the latest source in `active`; returning to the card
        // publishes that fresh content. Privacy changes still rebuild the view.
        if (!force && sbn.key in frontContinuations && rendered[sbn.key]?.redacted == redacted &&
            sbn.key in shown && RearNotificationCards.hasMirrorCard?.invoke(sbn.key) == true) return true
        val group = groupId(sbn)
        val version = RenderVersion(sbn.postTime, redacted,
            "${group in expandedGroups}:" + groupedSources[group].orEmpty().joinToString { "${it.key}:${it.postTime}" })
        if (!force && rendered[sbn.key] == version && sbn.key in shown &&
            RearNotificationCards.hasMirrorCard?.invoke(sbn.key) == true) return true
        return runCatching {
            val views = buildViews(host, sbn, token, redacted)
            val accepted = RearNotificationCards.post?.invoke(sbn.key, views) == true
            if (debugEnabled())
                YLog.debug("[REAREye-NotificationSync] publish accepted=$accepted redacted=$redacted")
            if (accepted) { shown.add(sbn.key); rendered[sbn.key] = version }
            accepted
        }.onFailure { YLog.warn("Rear notification unavailable: ${it.javaClass.simpleName}") }.getOrDefault(false)
    }

    private fun buildViews(context: Context, sbn: StatusBarNotification, token: String, redacted: Boolean): RemoteViews {
        val n = sbn.notification
        val extras = n.extras
        require(context.resources.getIdentifier("notification_panel", "layout", context.packageName) != 0) {
            "Native rear notification layout unavailable"
        }
        return RemoteViews(BuildConfig.APPLICATION_ID, R.layout.rear_notification_card).apply {
            setTextViewText(R.id.rear_notification_token, token)
            setTextViewText(R.id.rear_notification_redacted, if (redacted) "1" else "0")
            listOf(R.id.rear_notification_footer, R.id.rear_notification_progress,
                R.id.rear_notification_subtext, R.id.rear_notification_chronometer,
                R.id.rear_notification_actions, R.id.rear_notification_action_one,
                R.id.rear_notification_action_two).forEach { setViewVisibility(it, View.GONE) }
            if (!redacted) {
                val maximum = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
                val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
                val hasProgress = maximum > 0 || indeterminate
                val hasChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) && n.`when` > 0L
                val actions = if (sbn.packageName == "com.tencent.mm") emptyList() else
                    n.actions.orEmpty().filter { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() }.take(2)
                if (hasProgress || hasChronometer || actions.isNotEmpty()) {
                    setViewVisibility(R.id.rear_notification_footer, View.VISIBLE)
                    extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.takeIf { it.isNotBlank() }?.let {
                        setViewVisibility(R.id.rear_notification_subtext, View.VISIBLE)
                        setTextViewText(R.id.rear_notification_subtext, it)
                    }
                }
                if (hasProgress) {
                    setViewVisibility(R.id.rear_notification_progress, View.VISIBLE)
                    setProgressBar(R.id.rear_notification_progress, maximum.coerceAtLeast(1), extras.getInt(Notification.EXTRA_PROGRESS, 0), indeterminate)
                }
                if (hasChronometer) {
                    setViewVisibility(R.id.rear_notification_chronometer, View.VISIBLE)
                    setChronometer(R.id.rear_notification_chronometer, SystemClock.elapsedRealtime() + n.`when` - System.currentTimeMillis(), null, true)
                    setChronometerCountDown(R.id.rear_notification_chronometer, extras.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN, false))
                }
                if (actions.isNotEmpty()) setViewVisibility(R.id.rear_notification_actions, View.VISIBLE)
                val ids = listOf(R.id.rear_notification_action_one, R.id.rear_notification_action_two)
                ids.forEachIndexed { index, id ->
                    val action = actions.getOrNull(index)
                    setViewVisibility(id, if (action == null) View.GONE else View.VISIBLE)
                    if (action != null) { setTextViewText(id, action.title); setOnClickPendingIntent(id, action.actionIntent) }
                }
            }
        }
    }

    private fun populateNativeContent(view: View, sbn: StatusBarNotification, redacted: Boolean, animateContent: Boolean = false) {
        val host = listener ?: return
        val context = host.createDisplayContext(view.display)
        NativeRearCardTheme.bind(view, sbn)
        val nativeHost = view.findViewById<ViewGroup>(R.id.rear_notification_native_host) ?: return
        // Keep the live MAML renderer across notification updates. Replacing it
        // while AOD was active replayed its desk state for a frame and moved digits.
        val recordingScene = sbn.packageName == "com.android.soundrecorder"
        val timedScene = usesTimerScene(sbn)
        // Timer cards keep their dedicated scene through wake, sleep and face
        // session changes. The shared privacy template belongs to other cards.
        val privacyLayout = !timedScene && (redacted ||
            (privacyPolicy ?: NativeNotificationPrivacy.policy(context)).hidesDetails ||
            RearPopupReveal.usesDownloadLayout(sbn))
        val retainedRoot = nativeHost.getChildAt(0)
        if (!privacyLayout && !redacted && timedScene && retainedRoot?.findViewWithTag<NativeRearTimerScene>(NativeRearTimerScene.TAG)?.recording == recordingScene) {
            NativeRearTimerScene.bind(context, retainedRoot, sbn.notification, recordingScene) { index ->
                val latest = active[sbn.key]
                fun actions(notification: Notification) = if (recordingScene) NativeRearRecorder.actions(notification) else NativeRearStopwatchCard.actions(notification)
                val bound = actions(sbn.notification).getOrNull(index)
                val action = latest?.notification?.let(::actions)?.getOrNull(index)
                if (latest != null && privacy(latest) == false && action?.actionIntent == bound?.actionIntent && !rearInAod)
                    runCatching { action?.actionIntent?.send() }.onFailure { YLog.warn("Rear native timer action unavailable", it) }
            }
            view.findViewById<View>(R.id.rear_notification_footer)?.visibility = View.GONE
            return
        }
        fun hostId(name: String, type: String = "id"): Int =
            context.resources.getIdentifier(name, type, context.packageName).also {
                require(it != 0) { "Native rear notification resource unavailable: $name" }
            }
        // notification_panel contains an ordinary View camera placeholder, which Android
        // forbids in RemoteViews. Inflate it normally inside the authorized host process;
        // never loosen RemoteViews' framework-wide class filter.
        val nativeRoot = LayoutInflater.from(context).inflate(hostId("notification_panel", "layout"), nativeHost, false)
        val n = sbn.notification
        val extras = n.extras
        if (!privacyLayout && nativeHost.parent is FrameLayout) {
            val overlay = nativeHost.parent as FrameLayout
            val parent = overlay.parent as ViewGroup
            val footer = view.findViewById<View>(R.id.rear_notification_footer)
            val index = parent.indexOfChild(overlay); val params = overlay.layoutParams
            overlay.removeView(nativeHost); overlay.removeView(footer); parent.removeView(overlay)
            val stack = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            stack.addView(nativeHost, LinearLayout.LayoutParams(-1, 0, 1f))
            stack.addView(footer, LinearLayout.LayoutParams(-1, -2))
            parent.addView(stack, index, params)
        }
        val call = n.category in setOf(Notification.CATEGORY_CALL, Notification.CATEGORY_MISSED_CALL) ||
            sbn.packageName in setOf("com.android.server.telecom", "com.android.phone", "com.android.incallui")
        val group = groupId(sbn)
        val members = groupedSources[group].orEmpty()
        val app = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            ?: focusText(n, listOf("title", "titleText", "primaryText")).orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.joinToString("\n")
            ?: focusText(n, listOf("text", "content", "subText", "description")).orEmpty()
        nativeRoot.findViewById<TextView>(hostId("notification_title")).text =
            if (redacted) app else title.ifBlank { app }.take(160)
        nativeRoot.findViewById<TextView>(hostId("notification_content")).text =
            if (redacted) NativeNotificationPrivacy.placeholder(context) else body.take(1200)
        val appIcon = runCatching {
            val icon = context.packageManager.getApplicationIcon(sbn.packageName)
            if (sbn.user == Process.myUserHandle()) icon else context.packageManager.getUserBadgedIcon(icon, sbn.user)
        }.getOrNull()
            ?: n.smallIcon?.loadDrawable(context)
        val avatar = if (redacted || !privacyLayout) null else RearNotificationImages.avatar(n, context)
        nativeRoot.findViewById<ImageView>(hostId("notification_icon")).apply {
            NativeRearAppLaunch.registerNotificationOrigin(view,this)
            setImageDrawable(avatar ?: appIcon)
            if (avatar != null) {
                val radius = context.resources.getDimension(hostId("notification_large_icon_radius", "dimen"))
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, radius)
                    }
                }
                clipToOutline = true
            }
        }
        nativeRoot.findViewById<ImageView>(hostId("notification_small_icon")).apply {
            visibility = if (avatar == null || call) View.GONE else View.VISIBLE
            setImageDrawable(if (avatar == null || call) null else appIcon)
        }
        // Follow NotificationPanel's own constructor: reserve the camera strip in
        // its XML placeholder once, then let the native pager transform the card.
        val cameraId = hostId("camera_place_holder")
        val factoryPanel = view.rootView.findViewById<ViewGroup>(hostId("notification_panel"))
        val cameraWidth = factoryPanel?.findViewById<View>(cameraId)?.layoutParams?.width
            ?.takeIf { it >= 0 } ?: (view.display?.cutout?.safeInsetLeft ?: 0)
        nativeRoot.findViewById<View>(cameraId).let {
            it.layoutParams = it.layoutParams.apply { width = cameraWidth }
        }
        val margin = context.resources.getDimensionPixelSize(hostId("notification_container_margin_horizontal", "dimen"))
        // Visibility includes our group toggle after an earlier bind. Derive the
        // layout from notification data so rebinding cannot change its geometry.
        val stopwatch = !privacyLayout && !redacted && NativeRearStopwatchCard.isStopwatch(sbn.packageName, n)
        val hasTaskControls = sbn.packageName != "com.tencent.mm" && (call ||
            extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
                extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false) ||
                extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false) && n.`when` > 0L ||
                n.actions.orEmpty().any { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() })
        view.findViewById<View>(R.id.rear_notification_footer)?.let {
            it.setPadding(cameraWidth + margin, it.paddingTop, margin, it.paddingBottom)
            if (hasTaskControls && !privacyLayout) {
                // Keep the native notification layout; compact only task cards
                // that have actual progress or action controls.
                fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
                // The OEM food_delivery/car_hailing cards use system-font weights
                // 380/450; notification_panel's bold+medium heading is intended for
                // its large, centered message layout. Use a quieter hierarchy here.
                // Keep all task text in rear-display pixels, including the footer:
                // RemoteViews may otherwise resolve sp against the main display.
                fun TextView.taskText(size: Int, weight: Int, lineHeight: Int) {
                    typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), weight, false)
                    paint.isFakeBoldText = false
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(size).toFloat())
                    includeFontPadding = false
                    setLineSpacing(0f, 1f)
                    setLineHeight(dp(lineHeight))
                    letterSpacing = 0f
                }
                // RemoteViews can be inflated with the main-display density.
                // Size the footer in the same rear metrics as the native body.
                it.layoutParams = (it.layoutParams as LinearLayout.LayoutParams).apply { bottomMargin = dp(10) }
                view.findViewById<View>(R.id.rear_notification_actions)?.apply {
                    layoutParams = (layoutParams as LinearLayout.LayoutParams).apply {
                        height = dp(38); topMargin = dp(2)
                    }
                }
                val icon = nativeRoot.findViewById<ImageView>(hostId("notification_icon"))
                icon.apply {
                    layoutParams = layoutParams.apply { width = dp(40); height = dp(40) }
                }
                nativeRoot.findViewById<ImageView>(hostId("notification_small_icon")).apply {
                    layoutParams = (layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                        width = dp(14); height = dp(14); topMargin = dp(28); marginStart = dp(16)
                    }
                }
                val titleView = nativeRoot.findViewById<TextView>(hostId("notification_title")).apply {
                    // A two-line sender plus actions left less than one body
                    // line in the native host, clipping the glyph descenders.
                    maxLines = if (call || NativeNotificationPrivacy.isMessage(sbn.packageName, n)) 1 else 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    taskText(20, 500, 25)
                    gravity = Gravity.START
                    if (call) {
                        // Country-code numbers must not orphan their last digit
                        // beneath the header. Names still ellipsize at this limit.
                        setAutoSizeTextTypeUniformWithConfiguration(dp(13), dp(20), 1,
                            android.util.TypedValue.COMPLEX_UNIT_PX)
                    }
                }
                val bodyView = nativeRoot.findViewById<TextView>(hostId("notification_content")).apply {
                    taskText(15, 400, 21)
                    includeFontPadding = true
                    isFallbackLineSpacing = true
                    setPadding(0, 0, 0, dp(2))
                    gravity = Gravity.START
                    val hasProgress = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
                        extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
                    maxLines = if (hasProgress || call) 1 else 2
                    if (hasProgress) {
                        val parts = Regex("^已下载\\s*(.+?)[,，]\\s*共\\s*(.+)$").matchEntire(body)
                        if (parts != null) text = "${parts.groupValues[1]} / ${parts.groupValues[2]}"
                        contentDescription = body
                    }
                }
                val container = nativeRoot.findViewById<LinearLayout>(hostId("notification_container"))
                val imageFrame = icon.parent as View
                container.removeAllViews()
                val header = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                header.addView(imageFrame, LinearLayout.LayoutParams(dp(42), dp(42)))
                val labels = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                labels.addView(TextView(context).apply {
                    text = app
                    setTextColor(0x99FFFFFF.toInt())
                    taskText(12, 400, 16)
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                labels.addView(titleView, LinearLayout.LayoutParams(-1, -2))
                header.addView(labels, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
                container.addView(header, LinearLayout.LayoutParams(-1, -2))
                container.addView(bodyView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
                // Task headers must start below the factory clock. The footer
                // consumes height, so centerVertical previously pushed the
                // heading into the clock while leaving a large gap below it.
                container.layoutParams = (container.layoutParams as android.widget.RelativeLayout.LayoutParams).apply {
                    removeRule(android.widget.RelativeLayout.CENTER_VERTICAL)
                    addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
                    topMargin = dp(if (NativeNotificationPrivacy.isMessage(sbn.packageName, n)) 48 else 50)
                }
                bodyView.layoutParams = (bodyView.layoutParams as LinearLayout.LayoutParams).apply {
                    topMargin = dp(if (call || NativeNotificationPrivacy.isMessage(sbn.packageName, n)) 8 else 10)
                }
                listOf(R.id.rear_notification_action_one, R.id.rear_notification_action_two).forEach { id ->
                    view.findViewById<TextView>(id)?.apply {
                    taskText(14, 500, 20)
                        setPadding(dp(12), 0, dp(12), 0)
                    }
                }
                view.findViewById<TextView>(R.id.rear_notification_subtext)?.taskText(11, 400, 16)
                view.findViewById<TextView>(R.id.rear_notification_chronometer)?.taskText(18, 500, 24)
            }
        }
        val recorder = !privacyLayout && !redacted && sbn.packageName == "com.android.soundrecorder" &&
            NativeRearRecorder.bind(context, nativeRoot, n) { index ->
                val latest = active[sbn.key]
                if (latest != null && privacy(latest) == false && !NativeRearMotion.isFlipping(view)) {
                    val action = NativeRearRecorder.actions(latest.notification).getOrNull(index)
                    // A button from an old bind must never execute a new state's
                    // opposite action (pause becoming resume, for example).
                    val bound = NativeRearRecorder.actions(n).getOrNull(index)
                    if (action?.actionIntent == bound?.actionIntent) runCatching {
                        RearNotificationGestures.cancelTap()
                        action?.actionIntent?.send()
                    }.onFailure { YLog.warn("Rear recorder action unavailable", it) }
                }
            }
        val stopwatchBound = stopwatch && NativeRearStopwatchCard.bind(context, nativeRoot, n,
            NativeRearStopwatchCard.actions(n)) { index ->
            val latest = active[sbn.key]
            val bound = NativeRearStopwatchCard.actions(n).getOrNull(index)
            val action = latest?.notification?.let(NativeRearStopwatchCard::actions)?.getOrNull(index)
            if (latest != null && privacy(latest) == false && !NativeRearMotion.isFlipping(view) && action?.actionIntent == bound?.actionIntent)
                runCatching { RearNotificationGestures.cancelTap(); action?.actionIntent?.send() }
                    .onFailure { YLog.warn("Rear stopwatch action unavailable", it) }
        }
        if (recorder || stopwatchBound) {
            listOf(R.id.rear_notification_actions, R.id.rear_notification_chronometer,
                R.id.rear_notification_progress, R.id.rear_notification_subtext).forEach {
                view.findViewById<View>(it)?.visibility = View.GONE
            }
        }
        // Ordinary notifications keep their privacy layout after verification.
        // Timers always use the dedicated scene selected above.
        val messageEntry = privacyLayout && !recorder && !stopwatchBound
        if (messageEntry) {
            nativeRoot.layoutParams = (nativeRoot.layoutParams as FrameLayout.LayoutParams).apply {
                height = ViewGroup.LayoutParams.MATCH_PARENT
            }
        }
        nativeRoot.setOnClickListener { view.performClick() }
        nativeRoot.findViewById<View>(hostId("notification_container")).setOnClickListener { view.performClick() }
        if (nativeHost.layoutParams is LinearLayout.LayoutParams) {
            nativeHost.layoutParams = (nativeHost.layoutParams as LinearLayout.LayoutParams).apply {
                height = 0; weight = 1f
            }
        }
        (nativeHost.parent as? LinearLayout)?.gravity = Gravity.TOP
        val footer = view.findViewById<LinearLayout>(R.id.rear_notification_footer)
        footer.visibility = if (!redacted && hasTaskControls && !recorder && !stopwatchBound || members.size > 1) View.VISIBLE else View.GONE
        if (messageEntry) {
            fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
            view.findViewById<View>(R.id.rear_notification_actions)?.layoutParams?.let { params ->
                params.height = dp(38)
                view.findViewById<View>(R.id.rear_notification_actions).layoutParams = params
            }
            listOf(R.id.rear_notification_action_one, R.id.rear_notification_action_two).forEach { id ->
                view.findViewById<TextView>(id)?.apply {
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(14).toFloat())
                    typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), 500, false)
                    includeFontPadding = false; paint.isFakeBoldText = false
                    setPadding(dp(12), 0, dp(12), 0)
                }
            }
            (footer.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.topMargin = 0; params.bottomMargin = dp(NativeGroupToggle.BOTTOM_MARGIN_DP)
                footer.layoutParams = params
            }
        }
        footer.findViewWithTag<View>("rear_notification_group_toggle")?.let { footer.removeView(it) }
        RearPopupReveal.removeQuickOpen(footer)
        if (members.size > 1) {
            footer.visibility = View.VISIBLE
            val toggle = NativeGroupToggle(context, members.size, group in expandedGroups)
            val hasButtons = view.findViewById<View>(R.id.rear_notification_actions)?.visibility == View.VISIBLE
            if (messageEntry || hasButtons) {
                // Keep the existing text entrance and touch target. Its label
                // starts at the top so the invisible half of a centered target
                // cannot create another large gap below the sender.
                repeat(toggle.childCount) { index ->
                    (toggle.getChildAt(index) as? TextView)?.apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        includeFontPadding = false
                    }
                }
            }
            toggle.setOnClickListener {
                if (NativeRearMotion.isFlipping(view)) return@setOnClickListener
                val expanded = if (expandedGroups.remove(group)) false else { expandedGroups.add(group); true }
                toggle.setExpanded(expanded)
                groupReconciles.remove(group)?.let(main::removeCallbacks)
                val job = Runnable {
                    groupReconciles.remove(group)
                    // Registry updates reapply content themselves. Rebuilding a
                    // retained root again here interrupts the label transition.
                    reconcile()
                    if (view.isAttachedToWindow)
                        view.findViewWithTag<NativeGroupToggle>("rear_notification_group_toggle")
                            ?.setExpanded(group in expandedGroups)
                }
                groupReconciles[group] = job
                main.postDelayed(job, if (android.animation.ValueAnimator.areAnimatorsEnabled()) 220L else 0L)
            }
            val density = context.resources.displayMetrics.density
            footer.addView(toggle, LinearLayout.LayoutParams(-1,
                ((if (messageEntry || hasButtons) 26 else NativeGroupToggle.HEIGHT_DP) * density).toInt()).apply {
                if (hasButtons) topMargin = (6f * density).toInt()
            })
        }
        if (messageEntry && members.size <= 1 && canQuickOpen(context, sbn)) {
            quickOpenHint(view, sbn)?.let {
                footer.visibility = View.VISIBLE
                footer.addView(it)
            }
        }

        if (messageEntry && nativeHost.parent is LinearLayout) {
            // The footer is an overlay. Revealing controls must not resize the
            // message host underneath the reference icon/text transition.
            val stack = nativeHost.parent as LinearLayout
            val parent = stack.parent as ViewGroup
            val index = parent.indexOfChild(stack)
            val params = stack.layoutParams
            stack.removeView(nativeHost); stack.removeView(footer)
            parent.removeView(stack)
            val overlay = FrameLayout(context)
            overlay.addView(nativeHost, FrameLayout.LayoutParams(-1, -1))
            overlay.addView(footer, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                bottomMargin = (NativeGroupToggle.BOTTOM_MARGIN_DP * context.resources.displayMetrics.density).toInt()
            })
            parent.addView(overlay, index, params)
        }
        // SmartAssistantPanel owns the stock background and wake/AOD transitions.
        // Keep mirror content transparent; do not add nested blur or blend layers.
        val attachedRoot = RearNotificationReveal.replace(nativeHost, nativeRoot, redacted, footer,
            sbn.key, "${sbn.postTime}:${title.hashCode()}:${body.hashCode()}:${members.size}:$hasTaskControls", messageEntry,
            retainMessageContent = messageEntry)
        attachedRoot.findViewById<ImageView>(hostId("notification_icon"))?.let {
            NativeRearAppLaunch.registerNotificationOrigin(view, it)
        }
        if (messageEntry) {
            RearPopupReveal.bind(attachedRoot as ViewGroup, sbn, redacted, app, entry = null,
                footer = footer)
        }
    }

    private fun PackageScope.installNativePanelBehavior() {
        "com.xiaomi.subscreencenter.SmartAssistantPanel".toClass().resolve().firstMethod {
            name = "E"; parameters(Boolean::class.javaPrimitiveType!!)
        }.hook {
            after {
                if (!enabled()) return@after
                val panel = instance as? View ?: return@after
                NativeRearCardHost.selectedMirror(panel)?.let(::refreshMountedPrivacy)
            }
        }
        "U1.m1".toClass().resolve().firstMethod { name = "e"; parameterCount = 1 }.hook {
            before {
                if (enabled()) args.firstOrNull()?.let { entry ->
                    runCatching { RearPopupReveal.capture(entry) }
                        .onFailure { YLog.warn("Rear popup source avatar unavailable", it) }
                }
            }
        }
        val popupClass = "com.xiaomi.subscreencenter.NotificationPanel".toClass()
        popupClass.resolve().firstMethod { name = "c"; parameterCount = 1 }.hook {
            before {
                if (!enabled()) return@before
                val panel = args.firstOrNull() as? ViewGroup ?: return@before
                val entry = panel.javaClass.getDeclaredField("q").apply { isAccessible = true }.get(panel) ?: return@before
                if (RearPopupReveal.owns(panel, entry)) {
                    // A native privacy/ranking rebind must not overwrite the
                    // child drawables while their reference morph is running.
                    runCatching { bindPopup(panel) }.onSuccess { result = null }
                }
            }
            after {
                if (!enabled()) return@after
                (args.firstOrNull() as? ViewGroup)?.let { panel ->
                    popup = java.lang.ref.WeakReference(panel)
                    runCatching { bindPopup(panel) }.onFailure { YLog.warn("Rear popup motion unavailable", it) }
                }
            }
        }
        popupClass.resolve().firstMethod { name = "h"; parameterCount = 0 }.hook {
            before {
                (instance as? ViewGroup)?.let {
                    RearMessageEntryMotion.finish(it)
                    RearNotificationReveal.forget(it)
                    RearPopupReveal.forget(it)
                    RearPopupLifetime.forget(it)
                    NativeRearCardTheme.forget(it)
                }
                popupBinding = null; popupSource = null
                popupRetry?.let(main::removeCallbacks); popupRetry = null
            }
        }
        ViewGroup::class.java.resolve().firstMethod { name = "dispatchTouchEvent"; parameterCount = 1 }.hook {
            before {
                val panel = instance as? ViewGroup ?: return@before
                if (panel.javaClass.name != "com.xiaomi.subscreencenter.NotificationPanel") return@before
                val event = args.firstOrNull() as? MotionEvent ?: return@before
                if (event.actionMasked == MotionEvent.ACTION_DOWN)
                    RearPopupLifetime.touch(panel)
            }
        }
        // Keep the native dismissal, visibility and background transition.
        // Its child container scale is replaced by the shared message entrance.
        "U1.h1".toClass().resolve().firstMethod { name = "run"; parameterCount = 0 }.hook {
            after {
                val task = instance ?: return@after
                if (task.javaClass.getDeclaredField("a").apply { isAccessible = true }.getInt(task) != 2) return@after
                val panel = popup.get() ?: return@after
                if (enabled() && popupSource != null && panel.isShown) {
                    val id = panel.resources.getIdentifier("notification_container", "id", "com.xiaomi.subscreencenter")
                    val content = panel.findViewById<View>(id) ?: return@after
                    runCatching {
                        panel.context.classLoader.loadClass("miuix.animation.Folme")
                            .getMethod("clean", View::class.java).invoke(null, content)
                        panel.javaClass.getDeclaredMethod("setBackgroundBlurProgress", Float::class.javaPrimitiveType)
                            .apply { isAccessible = true }.invoke(panel, 1f)
                    }
                    content.alpha = 1f; content.scaleX = 1f; content.scaleY = 1f; content.translationY = 0f
                    RearPopupLifetime.shown(panel)
                }
            }
        }
        val launcherClass = "com.xiaomi.subscreencenter.SubScreenLauncher".toClass()
        launcherClass.resolve().firstMethod { name = "dispatchTouchEvent"; parameterCount = 1 }.hook {
            before {
                val event = args.firstOrNull() as? MotionEvent ?: return@before
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    val activity = instance as? android.app.Activity ?: return@before
                    NativeRearReply.activity = java.lang.ref.WeakReference(activity)
                    val containerId = activity.resources.getIdentifier("assistant_container", "id", activity.packageName)
                    val container = activity.findViewById<View>(containerId)
                    panelOwnsGesture = enabled() && container?.isShown == true && container.getGlobalVisibleRect(Rect())
                }
                val activity = instance as? android.app.Activity ?: return@before
                if (enabled() && RearNotificationGestures.dispatch(activity, event)) result = true
            }
        }
        val bridge = runtime.manage(createDexKitCacheBridge(
            packageName = hookAppInfo.packageName,
            packageVersionCode = resolveHookPackageVersionCode(hookSystemContext, hookAppInfo.packageName, hookAppInfo.sourceDir),
            sourceDir = hookAppInfo.sourceDir,
            dataDir = hookAppInfo.dataDir,
        ))
        runCatching {
            val point = requireNotNull(resolveDexKitMethodInjectionPoint(bridge, "SSC_NOTIFICATION_NATIVE_DOUBLE_TAP") {
                findMethod { matcher { paramTypes("android.view.MotionEvent"); returnType = "void";
                    usingStrings("onGesturePerformed: double click subscreen go to sleep") } }.singleOrNull()
            })
            val type = point.className.toClass()
            val method = type.getDeclaredMethod(point.methodName, MotionEvent::class.java).apply { isAccessible = true }
            type.resolve().firstMethod { name = point.methodName; parameterCount = 1 }.hook {
                before {
                    val event = args.firstOrNull() as? MotionEvent ?: return@before
                    if (enabled() && RearNotificationGestures.suppressNativeDoubleTap && event.actionMasked != MotionEvent.ACTION_CANCEL) {
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                            try { method.invoke(instance, cancel) } finally { cancel.recycle() }
                        }
                        // Explicit footer controls behave as controls; rapid label
                        // reversals must not become the page's sleep gesture.
                        result = null
                    }
                }
            }
        }.onFailure { YLog.warn("Native notification control gesture unavailable: ${it.javaClass.simpleName}") }
        runCatching {
            val update = requireNotNull(resolveDexKitMethodInjectionPoint(bridge, "SSC_MIRROR_AOD_NATIVE_UPDATE") {
                findMethod { matcher { paramTypes("boolean"); returnType = "void";
                    usingStrings("updateViews: mInAod = ") } }.singleOrNull()
            })
            val widgetClass = update.className.toClass()
            val replace = widgetClass.getDeclaredMethod(update.methodName, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            val aodChanged = widgetClass.declaredMethods.single {
                it.name != update.methodName && it.returnType == Void.TYPE &&
                    it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType))
            }
            val payload = widgetClass.fields.single { it.type == android.os.Bundle::class.java }
            val createView = widgetClass.declaredMethods.single {
                it.parameterTypes.contentEquals(arrayOf(Context::class.java)) && it.returnType == View::class.java
            }
            widgetClass.resolve().firstMethod { name = createView.name; parameterCount = 1 }.hook {
                after {
                    val widget = instance ?: return@after
                    if ((payload.get(widget) as? android.os.Bundle)?.getString("business") != RearNotificationCards.BUSINESS) return@after
                    (result as? View)?.let { NativeRearCardHost.register(widget, it) }
                }
            }
            widgetClass.resolve().firstMethod { name = "c"; parameterCount = 0 }.hook {
                after {
                    val widget = instance ?: return@after
                    if (enabled() && (payload.get(widget) as? android.os.Bundle)?.getString("business") == RearNotificationCards.BUSINESS)
                        result = true
                }
            }
            val contentField = widgetClass.declaredFields.single { it.type == View::class.java }
                .apply { isAccessible = true }
            widgetClass.resolve().firstMethod { name = aodChanged.name; parameterCount = 1 }.hook {
                before {
                    if (!enabled()) return@before
                    val widget = instance ?: return@before
                    val bundle = payload.get(widget) as? android.os.Bundle ?: return@before
                    if (bundle.getString("business") != RearNotificationCards.BUSINESS) return@before
                    rearInAod = args[0] as Boolean
                    aodChangedAt = SystemClock.uptimeMillis()
                    // Retain the existing root, including a visible handoff face.
                    // The stock RemoteViews AOD path adds a duplicate text layer.
                    val root = contentField.get(widget) as? ViewGroup
                    if (root?.id == R.id.rear_notification_card) {
                        if (rearInAod) RearMessageEntryMotion.pause(root)
                        NativeRearCardTheme.aod(root, rearInAod)
                        root.getChildAt(2)?.let { NativeRearMotion.aod(it, rearInAod, true) }
                    } else replace.invoke(widget, false)
                    result = null
                }
            }
            YLog.info("[REAREye-NotificationSync] native AOD content transition installed")
        }.onFailure { YLog.warn("Native rear AOD update unavailable: ${it.javaClass.simpleName}") }
        runCatching {
            val point = requireNotNull(resolveDexKitMethodInjectionPoint(bridge, "SSC_CAMERA_GESTURE_PRECHECK") {
                findMethod { matcher { paramTypes("android.view.MotionEvent"); returnType = "boolean";
                    usingStrings("preCheck: app card opened, skip camera gesture") } }.singleOrNull()
            })
            point.className.toClass().resolve().firstMethod { name = point.methodName; parameterCount = 1 }.hook {
                before { if (enabled() && panelOwnsGesture) result = false }
            }
            YLog.info("[REAREye-NotificationSync] panel camera gesture guard installed")
        }.onFailure { YLog.warn("Rear camera gesture guard unavailable: ${it.javaClass.simpleName}") }
    }

    private fun showFrontContinuation(view: View) {
        val host = view.findViewById<ViewGroup>(R.id.rear_notification_native_host) ?: return
        val context = listener?.createDisplayContext(view.display) ?: return
        runCatching {
            host.layoutParams = when (val params = host.layoutParams) {
                is LinearLayout.LayoutParams -> params.apply { height = 0; weight = 1f }
                else -> params.apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
            }
            val prompt = NativeRearContinuation.create(context, "请翻转手机\n到主屏继续使用")
            prompt.setOnClickListener { view.performClick() }
            NativeRearContinuation.show(host, prompt, view.findViewById(R.id.rear_notification_footer), view)
        }.onFailure { YLog.warn("Native front continuation unavailable: ${it.javaClass.simpleName}") }
    }

    private fun focusText(notification: Notification, names: List<String>): String? = runCatching {
        val raw = notification.extras.getString("miui.rear.param")
            ?: notification.extras.getString("miui.focus.param") ?: return@runCatching null
        if (raw.length > 128_000) return@runCatching null
        var nodes = listOf(JSONObject(raw))
        repeat(5) {
            nodes.forEach { node ->
                names.forEach { name ->
                    val value = node.opt(name)
                    if (value is String && value.isNotBlank()) return@runCatching value.take(1200)
                }
            }
            nodes = nodes.flatMap { node ->
                node.keys().asSequence().mapNotNull { node.optJSONObject(it) }.take(24).toList()
            }.take(64)
        }
        null
    }.getOrNull()

    private fun revealWithFace(view: View, sbn: StatusBarNotification) {
        if (rearInAod || !view.isAttachedToWindow || !view.isShown || privacy(sbn) != true) return
        if (!NativeNotificationPrivacy.faceRequired(view.context)) return
        val authenticate = RearAppFaceAuth.authenticate ?: return
        if (active[sbn.key]?.postTime != sbn.postTime || !faceAttempted.add(sbn.key)) return
        val generation = RearFaceSession.generation
        authenticate(view, false) {
            // Shared wake authorization refreshes every card once after the
            // visual exit. Rebinding here too would cancel that crossfade.
            if (!RearFaceSession.isAuthorized() && generation == RearFaceSession.generation && active[sbn.key]?.postTime == sbn.postTime &&
                view.isAttachedToWindow && view.display?.state == Display.STATE_ON) {
                RearFaceSession.accept(generation)
            }
        }
    }

    private fun isPopup(view: View) = popup.get() === view

    private fun canQuickOpen(context: Context, sbn: StatusBarNotification): Boolean {
        val prefs = launchPrefs ?: return false
        return NativeNotificationPrivacy.isInstantMessageApp(sbn.packageName) &&
            NativeRearAppLaunch.canOpenNotification(sbn, prefs, context)
    }

    private fun quickOpenHint(view: View, sbn: StatusBarNotification, popup: Boolean = false): TextView? {
        val prefs = launchPrefs ?: return null
        val context = view.context
        val density = context.resources.displayMetrics.density
        return TextView(context).apply {
            tag = RearPopupReveal.QUICK_OPEN_TAG
            text = "在背屏快捷打开"
            setTextColor(-1)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 13f * density)
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            includeFontPadding = false; maxLines = 1
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(-1, (26f * density).toInt()).apply {
                if (popup) topMargin = (6f * density).toInt()
            }
            setOnClickListener {
                if (rearInAod || NativeRearMotion.isFlipping(view)) return@setOnClickListener
                if (popup && (!isPopup(view) || !view.isShown || alpha < .8f ||
                        view.display?.state != Display.STATE_ON)) return@setOnClickListener
                val current = if (popup) runCatching {
                    listener?.activeNotifications?.singleOrNull { it.key == sbn.key }
                }.getOrNull() else active[sbn.key]
                val latest = current?.takeIf { it.postTime == sbn.postTime } ?: return@setOnClickListener
                if (privacy(latest) != false) {
                    faceAttempted.remove(latest.key)
                    revealWithFace(view, latest)
                    return@setOnClickListener
                }
                if (popup) {
                    val iconId = resources.getIdentifier("notification_icon", "id", "com.xiaomi.subscreencenter")
                    view.findViewById<ImageView>(iconId)?.let { NativeRearAppLaunch.registerNotificationOrigin(view, it) }
                }
                RearNotificationGestures.cancelTap()
                runCatching { NativeRearAppLaunch.openNotification(view, latest, prefs) {
                    main.post { clearOpenedSource(latest.key, latest.postTime) }
                } }.onFailure { YLog.warn("Rear quick-open unavailable", it) }
            }
        }
    }

    private fun refreshPopup() {
        popup.get()?.takeIf { it.isAttachedToWindow && it.isShown }?.let { panel ->
            runCatching { bindPopup(panel) }.onFailure { YLog.warn("Rear popup refresh unavailable", it) }
        }
    }

    private fun bindPopup(panel: ViewGroup, attempt: Int = 0) {
        popupRetry?.let(main::removeCallbacks); popupRetry = null
        val entry = panel.javaClass.getDeclaredField("q").apply { isAccessible = true }.get(panel) ?: return
        fun field(name: String) = entry.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(entry)
        val pkg = field("f") as? String ?: return
        NativeRearCardTheme.bind(panel, pkg)
        val user = field("g") as Int
        val posted = field("h") as Long
        // Entry.a is the keyguard provider's _id (for example 7), not the
        // app's Notification.id (which can be a negative WeChat conversation
        // hash). Resolve by the exact package/user/post-time tuple instead.
        val live = runCatching { listener?.activeNotifications?.toList().orEmpty() }.getOrDefault(emptyList())
        val sources = (live + active.values).distinctBy { it.key to it.postTime }
            .filter { it.packageName == pkg && it.user.hashCode() == user && it.postTime == posted }
        val sbn = sources.singleOrNull() ?: run {
            popupSource = null
            popupBinding = null
            RearPopupReveal.removeQuickOpen(panel)
            if (attempt < 20) {
                val retry = Runnable {
                    val current = panel.javaClass.getDeclaredField("q").apply { isAccessible = true }.get(panel)
                    if (popup.get() === panel && current === entry && panel.isAttachedToWindow && panel.isShown)
                        runCatching { bindPopup(panel, attempt + 1) }
                            .onFailure { YLog.warn("Rear popup source retry unavailable", it) }
                }
                popupRetry = retry; main.postDelayed(retry, 80L)
            }
            return
        }
        // The OEM popup is reused for unrelated applications. Always bind its
        // theme, even when this entry uses the unmodified native content path.
        NativeRearCardTheme.bind(panel, sbn)
        val redacted = privacy(sbn) ?: run { panel.visibility = View.GONE; return }
        val custom = !usesTimerScene(sbn) &&
            ((privacyPolicy ?: NativeNotificationPrivacy.policy(panel.context)).hidesDetails || redacted ||
                RearPopupReveal.usesDownloadLayout(sbn))
        val showQuickOpen = canQuickOpen(panel.context, sbn)
        val binding = "${RearMessageEntryMotion.version(sbn)}:$redacted:$custom:$showQuickOpen:" +
            listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_BIG_TEXT)
                .joinToString { sbn.notification.extras.getCharSequence(it)?.toString()?.hashCode().toString() }
        if (popupBinding == binding) return
        popupBinding = binding
        val quickOpen = if (showQuickOpen) quickOpenHint(panel, sbn, popup = true) else null
        if (!custom) {
            faceObservers.remove(panel)?.let { (observer, callback) -> if (observer.isAlive) observer.removeOnPreDrawListener(callback) }
            RearMessageEntryMotion.finish(panel)
            RearPopupReveal.forget(panel)
            RearPopupLifetime.forget(panel)
            val containerId = panel.resources.getIdentifier("notification_container", "id", "com.xiaomi.subscreencenter")
            quickOpen?.let { panel.findViewById<LinearLayout>(containerId)?.addView(it) }
            popupSource = null
            return
        }
        active[sbn.key] = sbn
        popupSource = sbn.key
        YLog.debug("[RearPopup] bound package=$pkg user=$user redacted=$redacted")
        RearMessageEntryMotion.finish(panel)
        val app = runCatching { panel.context.packageManager.getApplicationLabel(
            panel.context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        RearPopupReveal.bind(panel, sbn, redacted, app, entry, quickOpen = quickOpen)
        RearPopupLifetime.bind(panel, RearMessageEntryMotion.version(sbn), redacted)
        RearMessageEntryMotion.bind(panel, panel, RearMessageEntryMotion.version(sbn), redacted, popup = true)
        faceObservers.remove(panel)?.let { (observer, callback) -> if (observer.isAlive) observer.removeOnPreDrawListener(callback) }
        if (!redacted) return
        val observer = panel.viewTreeObserver
        val callback = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                if (!panel.isAttachedToWindow) {
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                    faceObservers.remove(panel)
                    return true
                }
                if (!panel.isShown || !RearMessageEntryMotion.ready(panel) || rearInAod || panel.display?.state != Display.STATE_ON) return true
                if (observer.isAlive) observer.removeOnPreDrawListener(this)
                faceObservers.remove(panel)
                panel.post { if (popupSource == sbn.key) revealWithFace(panel, sbn) }
                return true
            }
        }
        observer.addOnPreDrawListener(callback)
        faceObservers[panel] = observer to callback
    }

    private val mountedCards = java.util.Collections.newSetFromMap(java.util.WeakHashMap<View, Boolean>())

    private fun mirrorSource(view: View): StatusBarNotification? {
        val token = view.findViewById<TextView>(R.id.rear_notification_token)?.text?.toString() ?: return null
        val key = tokens.entries.firstOrNull { it.value == token }?.key ?: return null
        return active[key]
    }

    private fun refreshMountedPrivacy(view: View) {
        if (!view.isAttachedToWindow) return
        val source = mirrorSource(view) ?: return
        val host = listener ?: return
        val expected = privacy(source)
        if (expected == null) {
            view.visibility = View.INVISIBLE
            return
        }
        val marker = view.findViewById<TextView>(R.id.rear_notification_redacted)
        if ((marker?.text?.toString() == "1") != expected) {
            runCatching { buildViews(host, source, tokens.getValue(source.key), expected).reapply(view.context, view) }
                .onFailure {
                    view.visibility = View.INVISIBLE
                    YLog.warn("Could not refresh rear notification privacy", it)
                }
        } else if (view.visibility == View.INVISIBLE) view.visibility = View.VISIBLE
    }

    private fun bindCard(view: View, animateContent: Boolean = false) {
        faceObservers.remove(view)?.let { (observer, callback) -> if (observer.isAlive) observer.removeOnPreDrawListener(callback) }
        // Camera spacing belongs to the stock notification layout. Changing this
        // outer padding during page translation/scaling causes text/icon jumps.
        view.setPadding(0, 0, 0, 0)
        val sbn = mirrorSource(view) ?: return
        val key = sbn.key
        val token = tokens[key] ?: return
        mountedCards.add(view)
        val redacted = view.findViewById<TextView>(R.id.rear_notification_redacted)?.text?.toString() == "1"
        if (privacy(sbn) != redacted) {
            refreshMountedPrivacy(view)
            return
        }
        if (view.visibility == View.INVISIBLE) view.visibility = View.VISIBLE
        if (redacted) view.setOnClickListener(null)
        runCatching { populateNativeContent(view, sbn, redacted, animateContent) }.onFailure {
            YLog.warn("Native rear notification layout unavailable: ${it.javaClass.simpleName}", it)
            main.post { hide(key) }
            return
        }
        NativeRearCardTheme.aod(view, rearInAod)
        if (redacted) {
            view.isClickable = true
            view.setOnClickListener { faceAttempted.remove(key); revealWithFace(view, sbn) }
            val observer = view.viewTreeObserver
            val beforeDraw = object : ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    if (!view.isAttachedToWindow) { if (observer.isAlive) observer.removeOnPreDrawListener(this); return true }
                    if (rearInAod || !(isPopup(view) || NativeRearCardHost.isSelected(view)) || !RearMessageEntryMotion.ready(view) || !view.isShown || view.alpha < .8f || view.display?.state != Display.STATE_ON) return true
                    val rect = Rect()
                    if (!view.getGlobalVisibleRect(rect) || rect.width() < view.width * .8f || rect.height() < view.height * .8f) return true
                    if (observer.isAlive) observer.removeOnPreDrawListener(this)
                    faceObservers.remove(view)
                    view.post { revealWithFace(view, sbn) }
                    return true
                }
            }
            observer.addOnPreDrawListener(beforeDraw)
            faceObservers[view] = observer to beforeDraw
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = Unit
                override fun onViewDetachedFromWindow(v: View) {
                    if (observer.isAlive) observer.removeOnPreDrawListener(beforeDraw)
                    faceObservers.remove(v)
                    v.removeOnAttachStateChangeListener(this)
                }
            })
        }
        if (!redacted) {
            view.isClickable = true
            view.setOnClickListener {
                RearNotificationGestures.singleTap(view) {
                    if (active[key]?.postTime != sbn.postTime || privacy(active[key] ?: return@singleTap) != false) return@singleTap
                    if (NativeRearMotion.isFlipping(view)) return@singleTap
                    if(!continueOnFront(sbn)) {
                        runCatching { NativeRearAppLaunch.openNotification(view,sbn,launchPrefs?:return@singleTap) {
                            main.post { clearOpenedSource(sbn.key, sbn.postTime) }
                        } }
                            .onFailure { YLog.warn("Native rear notification launch failed",it) }
                        return@singleTap
                    }
                    if (key in frontContinuations || view.findViewWithTag<View>(NativeRearContinuation.VIEW_TAG) != null) {
                        NativeRearMotion.flip(view, returning = true) {
                            frontContinuations.remove(key)
                            val current = active[key]
                            val host = listener
                            val currentRedacted = rendered[key]?.redacted
                            if (current != null && host != null && currentRedacted != null) {
                                buildViews(host, current, token, currentRedacted).reapply(view.context, view)
                            }
                        }
                    } else runCatching {
                        val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
                        sbn.notification.contentIntent?.send(view.context, 0, null, null, null, null, options)
                        frontContinuations.add(key)
                        showFrontContinuation(view)
                    }.onFailure { YLog.warn("Front notification open unavailable: ${it.javaClass.simpleName}") }
                }
            }
            if (key in frontContinuations) showFrontContinuation(view)
        }
        val content = (view as? ViewGroup)?.getChildAt(2)
        if (content != null) {
            val now = SystemClock.uptimeMillis()
            if (now - aodChangedAt < 1600L) {
                NativeRearMotion.aod(content, !rearInAod, false)
                NativeRearMotion.aod(content, rearInAod, true)
            } else NativeRearMotion.aod(content, rearInAod, false)
        }
        RearNotificationGestures.register(view, sbn.isClearable) { dismissSource(key, sbn.postTime) }
    }

    private fun clearOpenedSource(key: String, expectedPostTime: Long) {
        val host = listener ?: return
        // Cancel exactly the successfully opened version, never a newer message
        // which arrived during face authentication or another group member.
        val current = runCatching { host.activeNotifications?.singleOrNull { it.key == key } }.getOrNull() ?: return
        if (!current.isClearable || current.postTime != expectedPostTime || !sourceEligible(current)) return
        runCatching { host.cancelNotification(key) }.onFailure { YLog.warn("Opened notification cancellation failed", it) }
    }

    private fun dismissSource(key: String, expectedPostTime: Long): Boolean {
        val host = listener ?: return false
        if (!enabled()) return false
        // Resolve against NMS again: never cancel a non-clearable or already-replaced entry.
        val snapshot = runCatching { host.activeNotifications?.toList() }.getOrNull() ?: return false
        val current = snapshot.singleOrNull { it.key == key } ?: return false
        if (current.postTime != expectedPostTime || !current.isClearable || !eligible(current)) return false
        val group = groupId(current)
        val targets = if (group !in expandedGroups && groupedSources[group].orEmpty().size > 1)
            snapshot.filter { groupId(it) == group && it.isClearable &&
                (sourceEligible(it) || (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) }
        else listOf(current)
        val targetKeys = targets.map { it.key }
        dismissing.addAll(targetKeys)
        val accepted = runCatching { host.cancelNotifications(targetKeys.toTypedArray()); true }.onFailure {
            YLog.warn("Rear notification dismissal rejected: ${it.javaClass.simpleName}")
        }.getOrDefault(false)
        // NMS removal is authoritative. If cancellation was refused, retain the rear card.
        main.postDelayed({
            dismissing.removeAll(targetKeys.toSet())
            targetKeys.forEach { rendered.remove(it) }
            reconcile()
        }, 600L)
        return accepted
    }

    private fun hide(key: String) {
        frontContinuations.remove(key)
        rendered.remove(key)
        if (shown.remove(key)) RearNotificationCards.remove?.invoke(key)
    }

    private fun remove(key: String) { hide(key); active.remove(key); tokens.remove(key); faceAttempted.remove(key) }
    private fun clear() {
        popupRetry?.let(main::removeCallbacks); popupRetry = null
        popupBinding = null; popupSource = null
        RearNotificationReveal.clear()
        RearPopupReveal.clear()
        popup.get()?.let(RearPopupReveal::removeQuickOpen)
        NativeRearCardTheme.clear()
        RearPopupLifetime.clear()
        RearMessageEntryMotion.finishAll()
        faceObservers.values.forEach { (observer, callback) -> if (observer.isAlive) observer.removeOnPreDrawListener(callback) }
        faceObservers.clear()
        faceAttempted.clear(); privacyPolicy = null; privacyPass = null
        shown.toList().forEach(::hide); active.clear(); tokens.clear()
        groupedSources = emptyMap(); expandedGroups.clear(); dismissing.clear()
        groupReconciles.values.forEach(main::removeCallbacks); groupReconciles.clear()
        RearNotificationGestures.cancelTap()
    }

    override fun onHotReloadQuiesce() {
        val done = CountDownLatch(1)
        val cleanup = Runnable {
            try {
                preferencesSubscription?.close();preferencesSubscription=null
                main.removeCallbacksAndMessages(null)
                clear()
                RearFaceSession.onChanged = null
                popup.clear(); popupSource = null
                receiverContext?.unregisterReceiver(privacyReceiver)
                settingsObserver?.let { receiverContext?.contentResolver?.unregisterContentObserver(it) }
                settingsObserver = null
                receiverContext = null
                listener = null
                RearNotificationCards.onReady = null
                RearNotificationCards.setVisibleSourceKeys = null
                visibleSourceKeys = null
                sourceGroups = emptyMap()
                continueOnFront = { false }
                panelOwnsGesture = false
            } finally { done.countDown() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
        else { main.post(cleanup); check(done.await(3, TimeUnit.SECONDS)) { "Notification cleanup timed out" } }
    }
}
