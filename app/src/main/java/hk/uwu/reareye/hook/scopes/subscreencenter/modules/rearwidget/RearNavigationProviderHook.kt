package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.os.Messenger
import android.os.Message
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.support.hookAppContext
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker

/** Compatibility for the provider's OEM map service, scoped to the rear-screen client. */
class RearNavigationProviderHook : RoxyHooker() {
    override fun PackageScope.onHook() {
        loadSystem {
            runCatching {
                val google = RearGoogleNavigationHost(hookSystemContext)
                runtime.onClose { google.close() }
                google.register()
                val tasks = "com.android.server.wm.ActivityTaskManagerService".toClass().resolve()
                tasks.firstMethod { name = "startActivityAsUser"; parameterCount = 12 }.hook {
                    after {
                        val intent = args.filterIsInstance<Intent>().firstOrNull() ?: return@after
                        val target = intent.component?.packageName ?: intent.`package`
                            ?: intent.takeIf { it.data?.scheme in setOf("geo", "google.navigation") }
                                ?.resolveActivity(hookSystemContext.packageManager)?.packageName
                        if (target == "com.google.android.apps.maps" &&
                            (intent.action == Intent.ACTION_MAIN || intent.action == Intent.ACTION_VIEW))
                            google.frontRequested()
                    }
                }
                tasks.firstMethod { name = "startActivityFromRecents"; parameterCount = 2 }.hook {
                    after { (args[0] as? Int)?.let { google.frontRequested(it) } }
                }
            }.onFailure { YLog.warn("Google rear navigation bridge unavailable", it) }
            runCatching {
                "com.android.server.permission.access.permission.PermissionService".toClass().resolve()
                    .firstMethod { name = "checkUidPermission"; returnType = Int::class.javaPrimitiveType }.hook {
                        before {
                            if (args[1] != LIVE_ALERT_PERMISSION && args[1] != "android.permission.READ_FRAME_BUFFER") return@before
                            val uid = args[0] as? Int ?: return@before
                            if (hookSystemContext.packageManager.getPackagesForUid(uid)
                                    ?.contains("com.xiaomi.subscreencenter") == true)
                                result = PackageManager.PERMISSION_GRANTED
                        }
                    }
                YLog.info("[RearNavigation] rear map service permission bridge installed")
            }.onFailure { YLog.warn("Rear provider map permission bridge unavailable", it) }
        }
        loadApp("com.autonavi.minimap") {
            if (processName == packageName) runCatching {
                hookRearDisplay("com.autonavi.minimap.immersenavi.AMapImmerseNaviService\$b")
                hookAmapTheme()
            }.onFailure { YLog.warn("AMap rear display binding unavailable", it) }
        }
        loadApp("com.baidu.BaiduMap") {
            if (processName != packageName) return@loadApp
            runCatching {
                hookRearDisplay("com.baidu.baidumaps.oem.mapshare.MapShareService\$c")
            }.onFailure { YLog.warn("Baidu rear display binding unavailable", it) }
            runCatching {
                val controller = "com.baidu.bikenavi.ui.smallmap.LockScreenMapController".toClass()
                val navigator = "com.baidu.walknavi.WNavigator".toClass()
                val navigation = { navigator.getDeclaredMethod("getInstance").invoke(null) }
                var initializingRearMap = false
                var rearBound = false
                var receiver: Messenger? = null
                fun publishRemaining() {
                    val target = receiver ?: return
                    runCatching {
                        val owner = navigation()
                        if (navigator.getDeclaredMethod("isNaviOn").invoke(owner) != true) return@runCatching
                        val remaining = navigator.getDeclaredMethod("getRemainInfoBundle").invoke(owner) as? Bundle
                            ?: return@runCatching
                        val route = "com.baidu.walknavi.ui.model.SimpleGuideModel".toClass()
                            .getDeclaredMethod("getDataFromRoutePlanResult").invoke(null) as? Bundle
                        val data = Bundle().apply {
                            putInt("remainingSeconds", remaining.getInt("time", -1))
                            putInt("remainingMeters", remaining.getInt("distance", -1))
                            putInt("totalMeters", route?.getInt("totaldist", -1) ?: -1)
                        }
                        target.send(Message.obtain(null, 10000101).apply { this.data = data })
                    }.onFailure { receiver = null }
                }
                runtime.onClose { receiver = null }
                navigator.resolve().firstMethod { name = "setRemainInfoBundle"; parameterCount = 1 }.hook {
                    after { publishRemaining() }
                }
                "com.baidu.baidumaps.oem.mapshare.MapShareService\$c".toClass().resolve()
                    .firstMethod { name = "handleMessage"; parameterCount = 1 }.hook {
                        after {
                            val message = args[0] as? Message ?: return@after
                            if (rearBound && message.what == 11) {
                                receiver = message.replyTo
                                publishRemaining()
                            }
                        }
                    }
                controller.resolve().firstMethod { name = "h"; parameterCount = 0 }.hook {
                    before { if (initializingRearMap) result = true }
                }
                val service = "com.baidu.baidumaps.oem.mapshare.MapShareService".toClass().resolve()
                service.firstMethod { name = "onBind"; parameterCount = 1 }.hook {
                    before {
                        if ((args[0] as? Intent)?.action != "hk.uwu.reareye.REAR_NAVIGATION_MAP") return@before
                        val provider = instance ?: return@before
                        rearBound = true
                        // The OEM service expects the navigation engine to have created
                        // its lock-screen renderer before the first SurfacePackage request.
                        runCatching {
                            val owner = controller.declaredFields.first { it.type == controller }.apply {
                                isAccessible = true
                            }.get(null)
                            initializingRearMap = true
                            try {
                                controller.getDeclaredMethod("g").invoke(owner)
                            } finally {
                                initializingRearMap = false
                            }
                        }.onFailure { YLog.warn("Baidu rear map renderer initialization failed", it) }
                        val messenger = provider.javaClass.declaredFields
                            .filter { it.type == Messenger::class.java && java.lang.reflect.Modifier.isFinal(it.modifiers) }
                            .firstNotNullOfOrNull { field ->
                                field.isAccessible = true
                                field.get(provider) as? Messenger
                            } ?: return@before
                        result = messenger.binder
                    }
                }
                service.firstMethod { name = "onUnbind"; parameterCount = 1 }.hook {
                    before {
                        if ((args[0] as? Intent)?.action == "hk.uwu.reareye.REAR_NAVIGATION_MAP") {
                            receiver = null
                            rearBound = false
                        }
                    }
                }
                "com.baidu.walknavi.jni.JNIGuidanceControl".toClass().resolve()
                    .firstMethod { name = "enableLockScreenMode"; parameterCount = 3 }.hook {
                        after { if (initializingRearMap) YLog.info("[RearNavigation] Baidu native route renderer enabled=$result") }
                    }
                YLog.info("[RearNavigation] Baidu native map service compatibility installed")
            }.onFailure { YLog.warn("Baidu rear navigation provider unavailable", it) }
        }
    }

    private fun PackageScope.hookRearDisplay(handlerClass: String) {
        val requestedDisplay = ThreadLocal<Int>()
        handlerClass.toClass().resolve().firstMethod { name = "handleMessage"; parameterCount = 1 }.hook {
            before {
                val message = args[0] as? Message ?: return@before
                if (message.what != 11 || !message.data.containsKey("rearMapDisplayId")) return@before
                if (hookAppContext?.packageManager?.getPackagesForUid(message.sendingUid)
                        ?.contains("com.xiaomi.subscreencenter") != true) return@before
                requestedDisplay.set(message.data.getInt("rearMapDisplayId"))
            }
            after { requestedDisplay.remove() }
        }
        DisplayManager::class.java.resolve().firstMethod { name = "getDisplay"; parameterCount = 1 }.hook {
            after {
                val id = requestedDisplay.get() ?: return@after
                if (args[0] != 0 || id == 0) return@after
                val rear = (instance as? DisplayManager)?.getDisplay(id) ?: return@after
                result = rear
                YLog.info("[RearNavigation] provider renderer bound to rear display=$id")
            }
        }
    }

    private fun PackageScope.hookAmapTheme() {
        val serviceType = "com.autonavi.minimap.immersenavi.AMapImmerseNaviService".toClass()
        val handlerType = "com.autonavi.minimap.immersenavi.AMapImmerseNaviService\$b".toClass()
        val ownerField = handlerType.declaredFields.first { it.type == serviceType }.apply { isAccessible = true }
        val viewType = "com.autonavi.minimap.immersenavi.view.ImmerseNaviView".toClass()
        val viewField = serviceType.declaredFields.first { it.type == viewType }.apply { isAccessible = true }
        val engineField = viewType.getDeclaredField("engineId").apply { isAccessible = true }
        val controllerType = "com.autonavi.ae.gmap.AMapController".toClass()
        val controller = { controllerType.getMethod("getInstance").invoke(null) }
        val integer = Int::class.javaPrimitiveType!!
        val getMode = controllerType.getMethod("getMapIntMode", integer, Boolean::class.javaPrimitiveType)
        val getTime = controllerType.getMethod("getMapIntTime", integer, Boolean::class.javaPrimitiveType)
        val getState = controllerType.getMethod("getMapIntModeState", integer, Boolean::class.javaPrimitiveType)
        val setStyle = controllerType.getMethod("setMapModeAndStyle", integer, integer, integer, integer)
        val deviceField = viewType.getDeclaredField("deviceId").apply { isAccessible = true }
        val nativeType = "com.autonavi.jni.ae.gmap.GLMapEngine".toClass()
        val nativeOwner = { controllerType.getMethod("getGLMapEngine").invoke(controller()) }
        val pause = nativeType.getMethod("renderPauseIn", integer)
        val resume = nativeType.getMethod("renderResumeIn", integer)
        val getFps = nativeType.getMethod("getRenderFps", integer)
        val setFps = nativeType.getMethod("setRenderFps", integer, integer)
        val original = mutableMapOf<Int, IntArray>()
        var rearEngine = -1
        var rearDevice = -1
        var originalFps = -1
        var rearVisible = true
        var frameLeaseUntil = 0L
        var rendering: Boolean? = null
        var policyEnabled = true
        var rearHandler: Handler? = null
        var provider: Any? = null
        fun ownsRenderer(): Boolean {
            val view = provider?.let { viewField.get(it) } ?: return false
            return engineField.getInt(view) == rearEngine && deviceField.getInt(view) == rearDevice
        }
        fun updateRendering() {
            if (rearDevice < 0 || !ownsRenderer()) return
            val wanted = rearVisible || SystemClock.uptimeMillis() < frameLeaseUntil
            if (rendering == wanted) return
            (if (wanted) resume else pause).invoke(nativeOwner(), rearDevice)
            rendering = wanted
            YLog.info("[RearNavigation] AMap rear renderer active=$wanted device=$rearDevice")
        }
        val expireFrame = Runnable {
            runCatching { updateRendering() }.onFailure { YLog.warn("AMap rear renderer pause failed", it) }
        }
        fun requestFrame() {
            frameLeaseUntil = SystemClock.uptimeMillis() + 4_000L
            rendering = null
            updateRendering()
            rearHandler?.removeCallbacks(expireFrame)
            rearHandler?.postDelayed(expireFrame, 4_000L)
        }
        listOf("nativeSetRenderFps", "nativeSetRenderFpsWithTimer").forEach { method ->
            nativeType.resolve().firstMethod { name = method }.hook {
                before {
                    if (policyEnabled && rearDevice >= 0 && args[1] == rearDevice &&
                        (args[2] as? Int ?: 0) > 30) args[2] = 30
                }
            }
        }
        nativeType.resolve().firstMethod { name = "nativeRenderResume"; parameterCount = 2 }.hook {
            before {
                if (policyEnabled && rearDevice >= 0 && args[1] == rearDevice &&
                    !rearVisible && SystemClock.uptimeMillis() >= frameLeaseUntil) result = null
            }
        }
        runtime.onClose {
            policyEnabled = false
            rearHandler?.removeCallbacks(expireFrame)
            runCatching {
                val owner = controller()
                original.forEach { (engine, style) -> setStyle.invoke(owner, engine, style[0], style[1], style[2]) }
                original.clear()
                if (ownsRenderer() && originalFps >= 0) {
                    setFps.invoke(nativeOwner(), rearDevice, originalFps)
                    resume.invoke(nativeOwner(), rearDevice)
                }
            }
        }
        handlerType.resolve().firstMethod { name = "handleMessage"; parameterCount = 1 }.hook {
            before {
                val message = args[0] as? Message ?: return@before
                if (message.what == 11 || message.what == 14) {
                    rearHandler?.removeCallbacks(expireFrame)
                    rearEngine = -1
                    rearDevice = -1
                    rendering = null
                    return@before
                }
                if (message.what !in setOf(13, 10000103, 10000107, 10000108) || hookAppContext?.packageManager
                        ?.getPackagesForUid(message.sendingUid)?.contains("com.xiaomi.subscreencenter") != true) return@before
                runCatching {
                    val view = viewField.get(ownerField.get(instance)) ?: return@runCatching
                    val engine = engineField.getInt(view)
                    if (engine < 0 || engine != rearEngine) return@runCatching
                    when (message.what) {
                        13 -> {
                            rearVisible = message.data.getInt("state") == 1
                            updateRendering()
                            return@runCatching
                        }
                        10000107 -> {
                            frameLeaseUntil = 0L
                            rearHandler?.removeCallbacks(expireFrame)
                            updateRendering()
                            return@runCatching
                        }
                        10000108 -> { requestFrame(); return@runCatching }
                    }
                    requestFrame()
                    val owner = controller()
                    if (message.data.getBoolean("ambient")) {
                        val style = original.getOrPut(engine) { intArrayOf(
                            getMode.invoke(owner, engine, false) as Int,
                            getTime.invoke(owner, engine, false) as Int,
                            getState.invoke(owner, engine, false) as Int) }
                        setStyle.invoke(owner, engine, style[0], 1, style[2])
                    } else original.remove(engine)?.let { style ->
                        setStyle.invoke(owner, engine, style[0], style[1], style[2])
                    }
                    message.replyTo?.send(Message.obtain(null, 10000104).apply {
                        data = Bundle().apply {
                            putInt("themeSequence", message.data.getInt("themeSequence"))
                            putBoolean("ambient", message.data.getBoolean("ambient"))
                            putInt("mapTime", getTime.invoke(owner, engine, false) as Int)
                        }
                    })
                    YLog.info("[RearNavigation] AMap native night style ambient=${message.data.getBoolean("ambient")} engine=$engine")
                }.onFailure { YLog.warn("AMap rear native theme change failed", it) }
                if (message.what != 13) result = null
            }
            after {
                val message = args[0] as? Message ?: return@after
                if (message.what != 11 || !message.data.containsKey("rearMapDisplayId") ||
                    hookAppContext?.packageManager?.getPackagesForUid(message.sendingUid)
                        ?.contains("com.xiaomi.subscreencenter") != true) return@after
                val view = viewField.get(ownerField.get(instance)) ?: return@after
                rearEngine = engineField.getInt(view)
                rearDevice = deviceField.getInt(view)
                provider = ownerField.get(instance)
                rearHandler = instance as? Handler
                rearVisible = true
                rendering = null
                if (rearEngine >= 0 && rearDevice >= 0) runCatching {
                    originalFps = getFps.invoke(nativeOwner(), rearDevice) as Int
                    setFps.invoke(nativeOwner(), rearDevice, originalFps.takeIf { it in 1..30 } ?: 30)
                    requestFrame()
                    YLog.info("[RearNavigation] AMap rear renderer fps=$originalFps capped=30 device=$rearDevice")
                }.onFailure { YLog.warn("AMap rear renderer frame policy failed", it) }
                original.keys.retainAll(setOf(rearEngine))
            }
        }
    }

    private companion object {
        const val LIVE_ALERT_PERMISSION = "com.oplus.systemui.permission.LIVE_ALERT_SERVICE"
    }
}
