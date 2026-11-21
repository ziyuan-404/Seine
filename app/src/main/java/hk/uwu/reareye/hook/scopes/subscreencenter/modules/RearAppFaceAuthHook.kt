package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.widget.Toast
import android.widget.FrameLayout
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.charging.RearChargingHook
import hk.uwu.reareye.hook.support.HookPrefs
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.hook.support.hookAppContext
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.RearAppDefaults
import hk.uwu.reareye.ui.config.RearNotificationDefaults
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.roxyhook.android.lifecycle.lifecycle
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Preserve the OEM unlocked-device exemption; never substitute face detection. */
internal object RearAppFaceAuth {
    var authenticate: ((View, Boolean, () -> Unit) -> Unit)? = null
    var requestNotification: ((View, String, Boolean) -> Boolean)? = null
    var cancelForCharging: (() -> Boolean)? = null

    fun authorize(view: View, prefs: HookPrefs, launch: () -> Unit) {
        if (!prefs.isRemoteReady()) {
            Toast.makeText(view.context, "设置尚未连接，请稍后重试", Toast.LENGTH_SHORT).show()
            return
        }
        if (!prefs.getBoolean(ConfigKeys.REAR_APP_FACE_AUTH, RearAppDefaults.FACE_AUTH)) { launch(); return }
        if (RearFaceSession.isAuthorized()) { launch(); return }
        val keyguard = view.context.getSystemService(android.app.KeyguardManager::class.java)
        if (keyguard != null && (!keyguard.isDeviceSecure || !keyguard.isDeviceLocked)) {
            launch(); return
        }
        val native = authenticate
        if (native == null) {
            Toast.makeText(view.context, "原厂人脸验证不可用，未打开应用", Toast.LENGTH_LONG).show()
            return
        }
        native(view, true, launch)
    }
}

/** Reuse the installed ROM's complete helper, including enrollment check and TrustService. */
class RearAppFaceAuthHook : RoxyHooker() {
    @Volatile private var closeSession: (() -> Unit)? = null
    override fun onHotReloadQuiesce() { closeSession?.invoke() }

    override fun PackageScope.onHook() {
        loadApp("com.xiaomi.subscreencenter") {
            val main = Handler(Looper.getMainLooper())
            val callbacks = java.util.IdentityHashMap<Any, () -> Unit>()
            var helper: Any? = null
            var terminate: java.lang.reflect.Method? = null
            var success = false
            var acceptSuccess = false
            var active = true
            var ownsGate = false
            var gate: AtomicBoolean? = null
            var applicationSession = false
            var notificationAnchor: WeakReference<View>? = null
            var notificationOverlay: WeakReference<FrameLayout>? = null
            var startingHelper: Any? = null
            var notificationWake = -1L
            val attemptedSources = mutableSetOf<String>()
            val overlays = WeakHashMap<FrameLayout, Boolean>()
            val generations = WeakHashMap<Any, Long>()
            val closed = AtomicBoolean()
            lifecycle { onAttach(replay = true) {
                val ctx = hookAppContext ?: this.context
                main.post { if (active) RearFaceSession.attach(ctx) }
            } }
            fun releaseGate() {
                if (ownsGate) gate?.set(false)
                ownsGate = false
            }
            runCatching {
                val overlay = "com.xiaomi.trustservice.face.FaceAuthOverlayView".toClass().resolve()
                overlay.firstMethod { name = "onFinishInflate"; parameterCount = 0 }.hook {
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    after {
                        val view = instance as? FrameLayout ?: return@after
                        val current = startingHelper ?: return@after
                        val application = current === helper && applicationSession
                        overlays[view] = application
                        if (!application) notificationOverlay = WeakReference(view)
                    }
                }
                overlay.firstMethod { name = "onAttachedToWindow"; parameterCount = 0 }.hook {
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    after {
                        val view = instance as? FrameLayout ?: return@after
                        if (overlays[view] == false) RearFaceFeedback.clearNotificationBlur(view)
                    }
                }
                listOf("c", "d", "e", "f").forEach { method ->
                    overlay.firstMethod { name = method; parameterCount = if (method == "d") 1 else 0 }.hook {
                        hotReloadPolicy = HotReloadPolicy.REINSTALL
                        after {
                            val view = instance as? FrameLayout ?: return@after
                            val application = overlays[view] ?: return@after
                            val anchor = notificationAnchor?.get()?.takeIf { it.isAttachedToWindow && it.isShown }
                            val styleKey = if (application) ConfigKeys.REAR_APP_FACE_AUTH_STYLE
                            else ConfigKeys.REAR_NOTIFICATION_FACE_AUTH_STYLE
                            val defaultStyle = if (application) RearAppDefaults.FACE_ANIMATION
                            else RearNotificationDefaults.FACE_ANIMATION
                            val savedStyle = hookPrefs.getInt(
                                styleKey, hookPrefs.getInt(ConfigKeys.REAR_FACE_AUTH_STYLE, defaultStyle),
                            )
                            val custom = savedStyle == 1 && (application || anchor != null)
                            if (!application) RearFaceFeedback.clearNotificationBlur(
                                view, passTouches = custom || method == "c" || method == "f",
                            )
                            if (custom) RearFaceFeedback.update(view, scanning = method == "c", success = method == "f",
                                application = application, notificationAnchor = anchor)
                        }
                    }
                }
                // The ROM releases the face service before removing this window.
                // Keep only its non-interactive visual tail; never retain camera
                // ownership or focus while finishing the reference's full sweep.
                "android.view.WindowManagerGlobal".toClass().resolve().firstMethod {
                    name = "removeView"; parameterCount = 2
                }.hook {
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    before {
                        val target = args(0).value as? FrameLayout ?: return@before
                        if (target.javaClass.name != "com.xiaomi.trustservice.face.FaceAuthOverlayView") return@before
                        val manager = instance ?: return@before
                        val deferred = runCatching { RearFaceFeedback.deferRemoval(target, RearChargingHook.blocksFaceAuth) {
                            runCatching { manager.javaClass.getMethod("removeView", View::class.java, Boolean::class.javaPrimitiveType)
                                .invoke(manager, target, true) }.onFailure { YLog.warn("Face visual tail removal failed", it) }
                        } }.onFailure { YLog.warn("Face visual tail unavailable", it) }.getOrDefault(false)
                        if (deferred) result = null
                    }
                }
                // Names and constructor signatures checked against the installed ROM's DEX.
                val helperClass = "r2.i".toClass()
                val nativeGate = "U1.f".toClass()
                gate = nativeGate.getDeclaredField("b").apply { isAccessible = true }.get(null) as AtomicBoolean
                val nativePending = nativeGate.getDeclaredField("c").apply { isAccessible = true }.get(null) as AtomicBoolean
                val maml = "q2.j".toClass()
                val nativeViews = java.util.WeakHashMap<View, Long>()
                val putNumber = maml.getMethod("putVariableNumber", String::class.java, Double::class.javaPrimitiveType)
                val sendCommand = maml.getMethod("sendCommand", String::class.java)
                fun revealNative(view: View) {
                    if (!RearFaceSession.isAuthorized() || nativeViews[view] == RearFaceSession.generation) return
                    putNumber.invoke(view, "face_auth_pending", 0.0)
                    sendCommand.invoke(view, "face_auth_success")
                    nativeViews[view] = RearFaceSession.generation
                }
                val synchronizeNative: (Boolean) -> Unit = { authorized ->
                    if (authorized) nativeGate.getMethod("a", String::class.java).invoke(null, "auth-success")
                    else nativePending.set(true)
                    if (authorized) {
                        val generation = RearFaceSession.generation
                        main.post {
                            if (active && generation == RearFaceSession.generation && RearFaceSession.isAuthorized())
                                nativeViews.keys.toList().forEach { runCatching { revealNative(it) } }
                        }
                    }
                }
                RearFaceSession.onNativeAuthorizationChanged = synchronizeNative
                // j() is the factory's common resume/AOD privacy-variable update.
                // Observe all native cards and deliver the same success command
                // to a newly mounted page in this wake, once per page/generation.
                maml.resolve().firstMethod { name = "j"; parameterCount = 0 }.hook {
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    after {
                        val view = instance as? View ?: return@after
                        if (!nativeViews.containsKey(view)) nativeViews[view] = -1L
                        if (RearFaceSession.isAuthorized()) main.post { if (active) runCatching { revealNative(view) } }
                    }
                }
                "com.xiaomi.subscreencenter.SubScreenCenterApp".toClass().resolve().firstMethod {
                    name = "c"; parameters(Boolean::class.javaPrimitiveType!!)
                }.hook {
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    after { if (active) RearFaceSession.setAod(args(0).value == true) }
                }
                val callbackClass = "q2.g".toClass()
                val nativeCallbacks = helperClass.declaredFields.filter { it.type == callbackClass }
                    .onEach { it.isAccessible = true }
                check(nativeCallbacks.size == 2) { "Unsupported native face callback layout" }
                val callbackConstructor = callbackClass.getConstructor(
                    "q2.k".toClass(), AtomicBoolean::class.java, Int::class.javaPrimitiveType,
                )
                val starter = "U1.a0".toClass().getConstructor(
                    Any::class.java, Any::class.java, Any::class.java, Int::class.javaPrimitiveType,
                )
                val starterKind = starter.declaringClass.getDeclaredField("a").apply { isAccessible = true }
                val starterHelper = starter.declaringClass.getDeclaredField("c").apply { isAccessible = true }
                val starterSuccess = starter.declaringClass.getDeclaredField("b").apply { isAccessible = true }
                val starterFinished = starter.declaringClass.getDeclaredField("d").apply { isAccessible = true }
                starter.declaringClass.resolve().firstMethod { name = "run"; parameterCount = 0 }.hook {
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    before {
                        val task = instance ?: return@before
                        if (!active || starterKind.getInt(task) != 14) return@before
                        val current = starterHelper.get(task)
                        if (!helperClass.isInstance(current)) return@before
                        if (RearChargingHook.blocksFaceAuth) {
                            result = null
                            // The factory acquired its gate before posting this runnable.
                            (starterFinished.get(task) as? Runnable)?.run()
                            return@before
                        }
                        if (!RearFaceSession.isAuthorized()) {
                            startingHelper = current
                            return@before
                        }
                        val succeeded = starterSuccess.get(task) as? Runnable ?: return@before
                        val finished = starterFinished.get(task) as? Runnable ?: return@before
                        result = null
                        try { succeeded.run() } finally { finished.run() }
                    }
                    after {
                        val task = instance ?: return@after
                        if (starterKind.getInt(task) == 14 && starterHelper.get(task) === startingHelper) startingHelper = null
                    }
                }
                terminate = helperClass.getMethod("g", String::class.java, Boolean::class.javaPrimitiveType)
                val pendingNativeAccept = WeakHashMap<Any, Long>()
                helperClass.resolve().firstMethod {
                    name = "g"; parameters(String::class.java, Boolean::class.javaPrimitiveType!!)
                }.hook {
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    before {
                        if (instance === helper) acceptSuccess = args(0).value == "success"
                        instance?.let { current ->
                            val generation = generations.remove(current)
                            if (args(0).value == "success" && generation != null) {
                                if (current === helper && applicationSession) RearFaceSession.accept(generation)
                                else if (current !== helper) pendingNativeAccept[current] = generation
                            }
                        }
                    }
                    after {
                        val current = instance ?: return@after
                        val generation = pendingNativeAccept.remove(current) ?: return@after
                        if (active) RearFaceFeedback.afterVisualExit {
                            if (active && !RearChargingHook.blocksFaceAuth) RearFaceSession.accept(generation)
                        }
                    }
                }
                helperClass.resolve().firstMethod { name = "f"; parameterCount = 0 }.hook {
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    before {
                        val current = instance ?: return@before
                        if (RearChargingHook.blocksFaceAuth) {
                            result = null
                            terminate?.invoke(current, "rear_charging", true)
                        } else generations.putIfAbsent(current, RearFaceSession.generation)
                    }
                }
                callbackClass.resolve().firstMethod { name = "run"; parameterCount = 0 }.hook {
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    before {
                        val callback = callbacks.remove(instance) ?: return@before
                        result = null
                        callback()
                    }
                }
                val authenticate: (View, Boolean, () -> Unit) -> Unit = authenticate@ { view, application, launch ->
                    if (RearChargingHook.blocksFaceAuth) return@authenticate
                    if (RearFaceSession.isAuthorized()) { launch(); return@authenticate }
                    if (!active || helper != null || !RearFaceSession.isAwake()) return@authenticate
                    if (view.display?.displayId != 1 || !view.isAttachedToWindow) return@authenticate
                    if (!gate.compareAndSet(false, true)) {
                        if (application) Toast.makeText(view.context, "人脸验证正在进行，请稍后重试", Toast.LENGTH_SHORT).show()
                        return@authenticate
                    }
                    ownsGate = true
                    applicationSession = application
                    if (!application) notificationAnchor = WeakReference(view)
                    success = false; acceptSuccess = false
                    val generation = RearFaceSession.generation
                    runCatching {
                        val current = helperClass.getConstructor().newInstance()
                        helper = current
                        generations[current] = generation
                        val succeeded = callbackConstructor.newInstance(null, AtomicBoolean(false), 0)
                        val finished = callbackConstructor.newInstance(null, AtomicBoolean(false), 1)
                        callbacks[succeeded] = { success = true }
                        callbacks[finished] = {
                            val authorized = success && acceptSuccess && generation == RearFaceSession.generation
                            helper = null
                            applicationSession = false
                            callbacks.clear()
                            releaseGate()
                            // The native helper has removed the overlay and released the camera.
                            // A screen-off, Back, Home, timeout, or hot reload cannot launch later.
                            val reveal = {
                                if (active && generation == RearFaceSession.generation && RearFaceSession.isAuthorized() && view.isAttachedToWindow) launch()
                            }
                            if (authorized) {
                                if (application) reveal() else RearFaceFeedback.afterVisualExit {
                                    // g() has already removed the OEM window and registered any
                                    // visual tail. Accept the session before running reveal().
                                    if (active && !RearChargingHook.blocksFaceAuth) RearFaceSession.accept(generation)
                                    reveal()
                                }
                            }
                        }
                        (starter.newInstance(current, succeeded, finished, 14) as Runnable).run()
                    }.onFailure {
                        if (startingHelper === helper) startingHelper = null
                        helper?.let { current -> runCatching { terminate?.invoke(current, "module_error", true) } }
                        helper = null; callbacks.clear()
                        applicationSession = false
                        notificationAnchor = null
                        releaseGate()
                        YLog.warn("Native rear face authentication failed", it)
                        if (application) Toast.makeText(view.context, "人脸验证启动失败，未打开应用", Toast.LENGTH_LONG).show()
                    }
                }
                RearAppFaceAuth.authenticate = authenticate
                val requestNotification: (View, String, Boolean) -> Boolean = request@ { view, source, manual ->
                    if (!active || RearChargingHook.blocksFaceAuth || !RearFaceSession.isAwake() ||
                        RearFaceSession.isAuthorized()) return@request false
                    if (notificationWake != RearFaceSession.generation) {
                        notificationWake = RearFaceSession.generation
                        attemptedSources.clear()
                    }
                    if (notificationAnchor?.get() !== view) notificationAnchor = WeakReference(view)
                    val visibleOverlay = notificationOverlay?.get()?.takeIf { it.isAttachedToWindow }
                    if (visibleOverlay != null) {
                        if (RearFaceFeedback.isRetiring(visibleOverlay)) {
                            if (!manual) view.postInvalidateDelayed(200L)
                            return@request false
                        }
                        val isNewSource = attemptedSources.add(source)
                        RearFaceFeedback.moveNotificationAnchor(visibleOverlay, view)
                        if (manual || isNewSource) RearFaceFeedback.retryNotification(visibleOverlay)
                        return@request true
                    }
                    if (!manual && source in attemptedSources) return@request true
                    if (helper != null || gate.get()) return@request false
                    // Reserve before starting so a rebind during startup cannot
                    // reopen the same card on failure. A new selected card can
                    // request one scan if the previous overlay has timed out.
                    attemptedSources.add(source)
                    authenticate(view, false) {}
                    true
                }
                RearAppFaceAuth.requestNotification = requestNotification
                val cancelForCharging: () -> Boolean = {
                    val hasExitingRing = RearFaceFeedback.prepareChargingExit()
                    val running = generations.keys.toList() + listOfNotNull(helper)
                    running.distinct().forEach { current ->
                        runCatching { terminate?.invoke(current, "rear_charging", true) }
                            .onFailure { YLog.warn("Could not close rear face authentication for charging", it) }
                    }
                    running.isNotEmpty() || hasExitingRing
                }
                RearAppFaceAuth.cancelForCharging = cancelForCharging
                val close: () -> Unit = {
                    if (closed.compareAndSet(false, true)) {
                        val done = CountDownLatch(1)
                        val cleanup = Runnable {
                            try {
                                active = false
                                if (RearAppFaceAuth.authenticate === authenticate) RearAppFaceAuth.authenticate = null
                                if (RearAppFaceAuth.requestNotification === requestNotification)
                                    RearAppFaceAuth.requestNotification = null
                                if (RearAppFaceAuth.cancelForCharging === cancelForCharging)
                                    RearAppFaceAuth.cancelForCharging = null
                                val current = helper
                                current?.let { value -> nativeCallbacks.forEach { it.set(value, null) } }
                                RearFaceFeedback.finishPendingImmediately()
                                val running = generations.keys.toList() + listOfNotNull(current)
                                running.distinct().forEach { runCatching { terminate?.invoke(it, "module_closed", true) } }
                                RearFaceSession.close()
                            } finally {
                                helper = null
                                callbacks.clear()
                                nativeViews.clear()
                                overlays.clear()
                                generations.clear()
                                pendingNativeAccept.clear()
                                releaseGate()
                                done.countDown()
                            }
                        }
                        if (Looper.myLooper() == Looper.getMainLooper()) cleanup.run()
                        else {
                            main.post(cleanup)
                            check(done.await(3, TimeUnit.SECONDS)) { "Rear face cleanup timed out" }
                        }
                    }
                }
                closeSession = close
                runtime.onClose { close(); closeSession = null }
            }.onFailure { YLog.warn("Native rear face authentication unavailable; protected launches stay blocked", it) }
        }
    }
}
