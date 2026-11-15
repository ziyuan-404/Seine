package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.content.Context
import android.app.Notification
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import hk.uwu.reareye.BuildConfig
import hk.uwu.reareye.R
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import java.util.WeakHashMap

/** Adapts navigation at the native widget boundary, preserving its lifecycle and notification route. */
class RearNavigationHook : RoxyHooker() {
    private val cards = WeakHashMap<Any, RearNavigationCard>()
    private val fallbacks = WeakHashMap<Any, RearNavigationCard>()
    private val navigation = linkedMapOf<String, RearNavigationData>()
    private val published = mutableSetOf<String>()
    private var listener: NotificationListenerService? = null
    private var notificationExtras: ((Context, StatusBarNotification) -> Bundle)? = null
    private val main = Handler(Looper.getMainLooper())
    private val refresh = Runnable { reconcileNavigation() }

    override fun onHotReloadPreflight(): Boolean = (cards.values + fallbacks.values).none { it.isAttachedToWindow }

    override fun PackageScope.onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            runtime.onClose {
                val cleanup = Runnable {
                    main.removeCallbacksAndMessages(null)
                    (cards.values + fallbacks.values).distinct().forEach { it.dispose(retainSession = false) }
                    RearNavigationMapService.closeIdle()
                    RearNavigationFrames.close()
                    cards.clear()
                    fallbacks.clear()
                    navigation.clear()
                    published.clear()
                    notificationExtras = null
                    listener = null
                }
                if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run() else main.post(cleanup)
            }
            runCatching {
                listOf("q2.k", "q2.o").forEach { className ->
                    className.toClass().resolve().firstMethod { name = "r"; parameterCount = 1 }.hook {
                        after {
                            val owner = instance ?: return@after
                            val original = result as? View ?: return@after
                            val context = args[0] as? Context ?: return@after
                            if (original.findViewById<View>(R.id.rear_navigation_card) != null) {
                                registerFallback(owner, original)
                                return@after
                            }
                            val extras = extras(owner) ?: return@after
                            val navigation = RearNavigationData.read(context, extras) ?: return@after
                            val card = RearNavigationCard(context, navigation)
                            card.sourceKey = extras.getString("notification_key")
                            card.aod(inAod(owner))
                            original.alpha = 0f
                            original.visibility = View.GONE
                            card.addView(original, 0, ViewGroup.LayoutParams(-1, -1))
                            cards.put(owner, card)?.dispose()
                            NativeRearCardHost.register(owner, card, backdrop = false)
                            result = card
                            YLog.info("[RearNavigation] native card adapted package=${navigation.packageName}")
                        }
                    }
                }
                "q2.v".toClass().resolve().firstMethod { name = "y"; parameterCount = 1 }.hook {
                    before { (cards[instance] ?: fallbacks[instance])?.syncFrame() }
                    after {
                        val owner = instance ?: return@after
                        (cards[owner] ?: fallbacks[owner])?.aod(inAod(owner))
                    }
                }
                "q2.o".toClass().resolve().firstMethod { name = "I"; parameterCount = 1 }.hook {
                    after {
                        val owner = instance ?: return@after
                        val field = owner.javaClass.declaredFields.firstOrNull { it.type == FrameLayout::class.java }
                            ?: return@after
                        field.isAccessible = true
                        (field.get(owner) as? View)?.let { registerFallback(owner, it) }
                    }
                }
                listOf("q2.n", "q2.o").forEach { className ->
                    className.toClass().resolve().firstMethod { name = "c"; parameterCount = 0 }.hook {
                        after { if (cards.containsKey(instance) || fallbacks.containsKey(instance)) result = true }
                    }
                }
                listOf("q2.n", "q2.o").forEach { className ->
                    className.toClass().resolve().firstMethod { name = "u"; parameterCount = 1 }.hook {
                        after {
                            val card = cards[instance] ?: return@after
                            val extras = args[0] as? Bundle ?: return@after
                            RearNavigationData.read(card.context, extras)?.let(card::update)
                        }
                    }
                    className.toClass().resolve().firstMethod { name = "s"; parameterCount = 0 }.hook {
                        before { cards.remove(instance)?.dispose(); fallbacks.remove(instance)?.dispose() }
                    }
                }
                // LiveUpdate-only changes are omitted by some ROM revisions' native dirty check.
                // Deliver the source notification to mounted cards as well as the ordinary route.
                val converter = "m2.b".toClass().declaredMethods.first {
                    it.name == "b" && it.parameterTypes.contentEquals(arrayOf(Context::class.java, StatusBarNotification::class.java))
                }.apply { isAccessible = true }
                notificationExtras = { context, sbn ->
                    Bundle(sbn.notification.extras).apply {
                        putString("package_name", sbn.packageName)
                        if (sbn.notification.category == "navigation") {
                            val converted = converter.invoke(null, context, sbn) as? Bundle
                            if (converted != null) putAll(converted)
                        }
                    }
                }
                RemoteViews::class.java.resolve().firstMethod { name = "apply"; parameterCount = 2 }.hook {
                    after {
                        val root = result as? FrameLayout ?: return@after
                        if (root.id != R.id.rear_navigation_card) return@after
                        val key = root.findViewById<TextView>(R.id.rear_navigation_key)?.text?.toString() ?: return@after
                        val data = navigation[key] ?: return@after
                        val context = args[0] as? Context ?: return@after
                        root.addView(RearNavigationCard(context, data).apply {
                            tag = "rear_navigation_map"
                            sourceKey = key
                        }, ViewGroup.LayoutParams(-1, -1))
                    }
                }
                val nls = "com.xiaomi.subscreencenter.smartassistant.SmartAssistantNotificationListenerService".toClass().resolve()
                nls.firstMethod { name = "onListenerConnected"; parameterCount = 0 }.hook {
                    after {
                        listener = instance as? NotificationListenerService
                        main.removeCallbacks(refresh)
                        main.postDelayed(refresh, 300L)
                    }
                }
                nls.firstMethod { name = "onNotificationRemoved"; parameterCount = 1 }.hook {
                    after {
                        if ((args[0] as? StatusBarNotification)?.packageName in RearNavigationData.packages) {
                            main.removeCallbacks(refresh)
                            main.postDelayed(refresh, 150L)
                        }
                    }
                }
                val previousReady = RearNotificationCards.onReady
                val ready: () -> Unit = {
                    previousReady?.invoke()
                    main.removeCallbacks(refresh)
                    main.post(refresh)
                }
                RearNotificationCards.onReady = ready
                runtime.onClose {
                    if (RearNotificationCards.onReady === ready) RearNotificationCards.onReady = previousReady
                }
                "com.xiaomi.subscreencenter.smartassistant.SmartAssistantNotificationListenerService".toClass()
                    .resolve().firstMethod { name = "onNotificationPosted"; parameterCount = 1 }.hook {
                        after {
                            val sbn = args[0] as? StatusBarNotification ?: return@after
                            val listener = instance as? NotificationListenerService ?: return@after
                            if (sbn.packageName !in RearNavigationData.packages) return@after
                            this@RearNavigationHook.listener = listener
                            main.post {
                                runCatching {
                                    val extras = notificationExtras?.invoke(listener, sbn) ?: return@runCatching
                                    val data = RearNavigationData.read(listener, extras) ?: return@runCatching
                                    cards.values.filter { it.packageName == sbn.packageName &&
                                        (it.sourceKey.isNullOrBlank() || it.sourceKey == sbn.key) }.forEach { it.update(data) }
                                }.onFailure { YLog.warn("Rear navigation update unavailable", it) }
                            }
                            main.removeCallbacks(refresh)
                            main.postDelayed(refresh, 150L)
                        }
                    }
                YLog.info("[RearNavigation] native navigation adaptation installed")
            }.onFailure { YLog.warn("Rear navigation adaptation unavailable", it) }
        }
    }

    private fun registerFallback(owner: Any, root: View) {
        val card = root.findViewWithTag<RearNavigationCard>("rear_navigation_map") ?: return
        if (fallbacks[owner] === card) return
        fallbacks.put(owner, card)?.dispose()
        card.aod(inAod(owner))
        NativeRearCardHost.register(owner, root, backdrop = false)
    }

    private fun inAod(owner: Any): Boolean = generateSequence(owner.javaClass as Class<*>?) { it.superclass }
        .mapNotNull { type -> type.declaredFields.firstOrNull { it.name == "k" && it.type == Boolean::class.javaPrimitiveType } }
        .firstOrNull()?.let { field ->
            runCatching { field.isAccessible = true; field.getBoolean(owner) }.getOrDefault(false)
        } ?: false

    private fun reconcileNavigation() {
        val host = listener ?: return
        val convert = notificationExtras ?: return
        runCatching {
            val candidates = host.activeNotifications.orEmpty().filter {
                it.packageName in RearNavigationData.packages &&
                    it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
            }.mapNotNull { sbn -> RearNavigationData.read(host, convert(host, sbn))?.let { sbn to it } }
            val selected = candidates.groupBy { it.first.user to it.first.packageName }.values.map { sources ->
                sources.maxBy { (sbn, _) ->
                    if (sbn.notification.extras.containsKey("miui.focus.param")) 2
                    else if (sbn.notification.category == "navigation") 1 else 0
                }
            }
            RearNavigationMapService.retainSources(candidates.mapTo(mutableSetOf()) { it.first.key })
            navigation.clear()
            selected.forEach { (sbn, data) -> navigation[sbn.key] = data }
            val needed = selected.filter { (_, data) -> cards.values.none { it.packageName == data.packageName } }
                .mapTo(mutableSetOf()) { it.first.key }
            RearNotificationCards.reconcileNavigation?.invoke(needed)
            (published - needed).forEach { RearNotificationCards.removeNavigation?.invoke(it) }
            published.retainAll(needed)
            fallbacks.values.forEach { card -> navigation[card.sourceKey]?.let(card::update) }
            needed.filterNot { it in published }.forEach { key ->
                val views = RemoteViews(BuildConfig.APPLICATION_ID, R.layout.rear_navigation_card).apply {
                    setTextViewText(R.id.rear_navigation_key, key)
                }
                if (RearNotificationCards.postNavigation?.invoke(key, views) == true) {
                    published.add(key)
                    YLog.info("[RearNavigation] dedicated navigation card posted package=${navigation[key]?.packageName}")
                }
            }
        }.onFailure { YLog.warn("Rear navigation card reconciliation unavailable", it) }
    }

    private fun extras(owner: Any): Bundle? {
        var type: Class<*>? = owner.javaClass
        while (type != null) {
            type.declaredFields.filter { it.type == Bundle::class.java }.forEach { field ->
                val bundle = runCatching { field.isAccessible = true; field.get(owner) as? Bundle }.getOrNull()
                if (bundle != null && (bundle.containsKey("package_name") || bundle.containsKey("miui.liveupdate.param") ||
                    bundle.containsKey("miui.focus.param"))) return bundle
            }
            type = type.superclass
        }
        return null
    }

}
