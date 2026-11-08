package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.View
import android.widget.Toast
import android.widget.FrameLayout
import com.highcapable.kavaref.KavaRef.Companion.resolve
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
import java.util.concurrent.atomic.AtomicBoolean

/** Preserve the OEM unlocked-device exemption; never substitute face detection. */
internal object RearAppFaceAuth {
    var authenticate: ((View, Boolean, () -> Unit) -> Unit)? = null

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
            var notificationAnchor: java.lang.ref.WeakReference<View>? = null
            val generations = java.util.WeakHashMap<Any, Long>()
            lifecycle { onAttach(replay = true) {
                val ctx = hookAppContext ?: this.context
                main.post { RearFaceSession.attach(ctx) }
            } }
            fun releaseGate() {
                if (ownsGate) gate?.set(false)
                ownsGate = false
            }
            runCatching {
                val overlay = "com.xiaomi.trustservice.face.FaceAuthOverlayView".toClass().resolve()
                listOf("onFinishInflate", "onAttachedToWindow").forEach { method ->
                    overlay.firstMethod { name = method; parameterCount = 0 }.hook {
                        after {
                            if (!applicationSession) (instance as? FrameLayout)?.let {
                                RearFaceFeedback.clearNotificationBlur(it, passTouches = true)
                            }
                        }
                    }
                }
                listOf("c", "d", "e", "f").forEach { method ->
                    overlay.firstMethod { name = method; parameterCount = if (method == "d") 1 else 0 }.hook {
                        after {
                            if (!applicationSession) (instance as? FrameLayout)?.let {
                                RearFaceFeedback.clearNotificationBlur(it, passTouches = method == "c" || method == "f")
                            }
                            // The ROM also initiates face sessions itself. The
                            // visual preference applies to those native sessions too.
                            val styleKey = if (applicationSession) ConfigKeys.REAR_APP_FACE_AUTH_STYLE
                            else ConfigKeys.REAR_NOTIFICATION_FACE_AUTH_STYLE
                            val defaultStyle = if (applicationSession) RearAppDefaults.FACE_ANIMATION
                            else RearNotificationDefaults.FACE_ANIMATION
                            val savedStyle = hookPrefs.getInt(
                                styleKey, hookPrefs.getInt(ConfigKeys.REAR_FACE_AUTH_STYLE, defaultStyle),
                            )
                            if (savedStyle == 1) (instance as? FrameLayout)?.let {
                                RearFaceFeedback.update(it, scanning = method == "c", success = method == "f",
                                    application = applicationSession, notificationAnchor = notificationAnchor)
                            }
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
                        val deferred = runCatching { RearFaceFeedback.deferRemoval(target) {
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
                nativeGate.resolve().firstMethod { name = "c"; parameters(String::class.java) }.hook {
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    after { if (active) RearFaceSession.reset() }
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
                starter.declaringClass.resolve().firstMethod { name = "run"; parameterCount = 0 }.hook {
                    before {
                        if (!RearFaceSession.isAuthorized()) return@before
                        val task = instance ?: return@before
                        fun field(name: String) = task.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(task)
                        if (field("a") != 14 || !helperClass.isInstance(field("c"))) return@before
                        val succeeded = field("b") as? Runnable ?: return@before
                        val finished = field("d") as? Runnable ?: return@before
                        result = null
                        try { succeeded.run() } finally { finished.run(); gate?.set(false) }
                    }
                }
                terminate = helperClass.getMethod("g", String::class.java, Boolean::class.javaPrimitiveType)
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
                                else main.post {
                                    // g() removes the OEM window and registers its visual tail.
                                    // Keep the privacy label mounted until that tail completes.
                                    if (active) RearFaceFeedback.afterVisualExit {
                                        if (active) RearFaceSession.accept(generation)
                                    }
                                }
                            }
                        }
                    }
                }
                helperClass.resolve().firstMethod { name = "f"; parameterCount = 0 }.hook {
                    before { instance?.let { generations.putIfAbsent(it, RearFaceSession.generation) } }
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
                    if (RearFaceSession.isAuthorized()) { launch(); return@authenticate }
                    if (!active || helper != null) return@authenticate
                    if (view.display?.displayId != 1 || !view.isAttachedToWindow) return@authenticate
                    if (gate?.compareAndSet(false, true) != true) {
                        Toast.makeText(view.context, "人脸验证正在进行，请稍后重试", Toast.LENGTH_SHORT).show()
                        return@authenticate
                    }
                    ownsGate = true
                    applicationSession = application
                    notificationAnchor = if (application) null else java.lang.ref.WeakReference(view)
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
                            notificationAnchor = null
                            callbacks.clear()
                            releaseGate()
                            // The native helper has removed the overlay and released the camera.
                            // A screen-off, Back, Home, timeout, or hot reload cannot launch later.
                            val reveal = {
                                if (active && generation == RearFaceSession.generation && view.isAttachedToWindow && view.display?.state == Display.STATE_ON) launch()
                            }
                            if (authorized) {
                                if (application) reveal() else RearFaceFeedback.afterVisualExit(reveal)
                            }
                        }
                        (starter.newInstance(current, succeeded, finished, 14) as Runnable).run()
                    }.onFailure {
                        helper?.let { current -> runCatching { terminate?.invoke(current, "module_error", true) } }
                        helper = null; callbacks.clear()
                        applicationSession = false
                        notificationAnchor = null
                        releaseGate()
                        YLog.warn("Native rear face authentication failed", it)
                        Toast.makeText(view.context, "人脸验证启动失败，未打开应用", Toast.LENGTH_LONG).show()
                    }
                }
                RearAppFaceAuth.authenticate = authenticate
                val close: () -> Unit = {
                    active = false
                    if (RearFaceSession.onNativeAuthorizationChanged === synchronizeNative)
                        RearFaceSession.onNativeAuthorizationChanged = null
                    nativeViews.clear()
                    if (RearAppFaceAuth.authenticate === authenticate) RearAppFaceAuth.authenticate = null
                    // Detach module-owned callbacks before Roxy removes their hooks.
                    // Native teardown may finish asynchronously after this generation closes.
                    val current = helper
                    current?.let { value -> nativeCallbacks.forEach { it.set(value, null) } }
                    main.post {
                        RearFaceFeedback.finishPendingImmediately()
                        RearFaceSession.close()
                        current?.let { runCatching { terminate?.invoke(it, "module_closed", true) } }
                        helper = null; callbacks.clear()
                        releaseGate()
                    }
                }
                closeSession = close
                runtime.onClose { close(); closeSession = null }
            }.onFailure { YLog.warn("Native rear face authentication unavailable; protected launches stay blocked", it) }
        }
    }
}
