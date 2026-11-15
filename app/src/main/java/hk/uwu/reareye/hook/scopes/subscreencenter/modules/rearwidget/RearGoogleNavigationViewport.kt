package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.view.SurfaceControl
import android.view.SurfaceView
import android.view.ViewGroup
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.reareye.hook.hostbridge.HookHostBridgeBootstrapRegistry
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookAppInfo
import hk.uwu.reareye.hook.support.hookSystemContext
import hk.uwu.reareye.hook.utils.createDexKitCacheBridge
import hk.uwu.reareye.hook.utils.resolveDexKitMethodInjectionPoint
import hk.uwu.reareye.hook.utils.resolveHookPackageVersionCode
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import org.luckypray.dexkit.annotations.DexKitExperimentalApi
import java.lang.ref.WeakReference
import java.lang.reflect.Modifier
import java.util.concurrent.atomic.AtomicReference

/** Supplies only Google's map surface and changes its camera anchor on the owned display. */
@OptIn(DexKitExperimentalApi::class)
internal class RearGoogleNavigationViewport : RoxyHooker() {
    companion object {
        const val ACTION = "hk.uwu.reareye.GOOGLE_MAP_SURFACE"
        const val DISPLAY_NAME = "Seine Google navigation"
    }

    override fun PackageScope.onHook() {
        loadApp("com.google.android.apps.maps") {
            if (processName != packageName) return@loadApp
            val activity = AtomicReference(WeakReference<Activity>(null))
            val camera = AtomicReference(android.graphics.PointF(.5f, .5f))
            val cameraReported = java.util.concurrent.atomic.AtomicBoolean(false)
            var layerReported = false
            val endpoint = Messenger(Handler(Looper.getMainLooper()) { message ->
                if (message.sendingUid != Process.SYSTEM_UID || message.what != 11) return@Handler true
                runCatching {
                    val owner = activity.get().get() ?: error("Google navigation window is unavailable")
                    val frameId = owner.resources.getIdentifier("map_frame", "id", owner.packageName)
                    val frame = owner.findViewById<ViewGroup>(frameId) ?: error("Google map frame is unavailable")
                    val surface = (0 until frame.childCount).map { frame.getChildAt(it) }
                        .filterIsInstance<SurfaceView>().firstOrNull() ?: error("Google map surface is unavailable")
                    val control = SurfaceView::class.java.getMethod("getSurfaceControl").invoke(surface) as SurfaceControl
                    check(control.isValid && surface.width > 0 && surface.height > 0)
                    if (!layerReported) {
                        layerReported = true
                        YLog.info("[RearNavigation] Google map layer display=${owner.display?.displayId} name=${owner.display?.name}")
                    }
                    message.replyTo?.send(Message.obtain(null, 10000120).apply {
                        data = Bundle().apply {
                            putParcelable("surface", control)
                            putInt("width", surface.width)
                            putInt("height", surface.height)
                            val anchor = camera.get()
                            putFloat("anchorX", anchor.x)
                            putFloat("anchorY", anchor.y)
                            putString("generation", "${System.identityHashCode(surface)}:${System.identityHashCode(control)}:${surface.width}:${surface.height}")
                        }
                    })
                }.onFailure { YLog.warn("Google original map layer unavailable", it) }
                true
            })
            val registry = HookHostBridgeBootstrapRegistry(ACTION, { endpoint.binder })
            runtime.onClose { registry.unregister() }
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    val thread = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null)
                    val records = hk.uwu.reareye.hook.activityhost.ActivityHostReflection
                        .field(thread.javaClass, "mActivities").get(thread) as Map<*, *>
                    val owner = records.values.filterNotNull().firstNotNullOfOrNull { record ->
                        (hk.uwu.reareye.hook.activityhost.ActivityHostReflection.field(record.javaClass, "activity")
                            .get(record) as? Activity)?.takeIf { it.javaClass.name == "com.google.android.maps.MapsActivity" }
                    }
                    if (owner != null) {
                        activity.set(WeakReference(owner))
                        registry.register(owner.applicationContext, "android.permission.MANAGE_ACTIVITY_TASKS")
                    }
                }.onFailure { YLog.warn("Google navigation window lookup unavailable", it) }
            }
            Activity::class.java.resolve().firstMethod { name = "onResume"; parameterCount = 0 }.hook {
                after {
                    val owner = instance as? Activity ?: return@after
                    if (owner.javaClass.name != "com.google.android.maps.MapsActivity") return@after
                    activity.set(WeakReference(owner))
                    registry.register(owner.applicationContext, "android.permission.MANAGE_ACTIVITY_TASKS")
                }
            }
            runCatching {
                val dex = runtime.manage(createDexKitCacheBridge(
                    packageName = hookAppInfo.packageName,
                    packageVersionCode = resolveHookPackageVersionCode(hookSystemContext, hookAppInfo.packageName, hookAppInfo.sourceDir),
                    sourceDir = hookAppInfo.sourceDir, dataDir = hookAppInfo.dataDir,
                ))
                val nativeCamera = "com.google.android.libraries.geo.navcore.ui.camera.jni.CameraControllerJni".toClass()
                val positionType = nativeCamera.declaredMethods.single { method ->
                    Modifier.isStatic(method.modifiers) &&
                        method.parameterTypes.contentEquals(arrayOf(ByteArray::class.java)) &&
                        method.returnType.declaredFields.count { it.type == Double::class.javaPrimitiveType } >= 6
                }.returnType
                val conversion = requireNotNull(resolveDexKitMethodInjectionPoint(dex, "GOOGLE_REAR_NATIVE_CAMERA_POSITION") {
                    findMethod { matcher {
                        paramTypes(positionType.name, "boolean")
                        returnType = "j$.util.Optional"
                    } }.singleOrNull()
                })
                // The native position protocol uses signed screen coordinates, with zero at the centre.
                val anchorX = positionType.getDeclaredField("j").apply { isAccessible = true }
                val anchorY = positionType.getDeclaredField("k").apply { isAccessible = true }
                require(anchorX.type == Double::class.javaPrimitiveType && anchorY.type == Double::class.javaPrimitiveType)
                conversion.className.toClass().resolve().firstMethod {
                    name = conversion.methodName
                    parameterCount = 2
                }.hook {
                    before {
                        val original = args[0] ?: return@before
                        if (activity.get().get()?.display?.name != DISPLAY_NAME) {
                            camera.set(android.graphics.PointF(((anchorX.getDouble(original)+1)*.5).toFloat(),
                                ((anchorY.getDouble(original)+1)*.5).toFloat()))
                            return@before
                        }
                        val reflect = hk.uwu.reareye.hook.activityhost.ActivityHostReflection
                        val builder = requireNotNull(reflect.invoke(original, "toBuilder"))
                        reflect.invoke(builder, "copyOnWrite")
                        val copy = reflect.field(builder.javaClass, "instance").get(builder)
                        anchorX.setDouble(copy, 0.0)
                        anchorY.setDouble(copy, 0.0)
                        args(0).value = reflect.invoke(builder, "build")
                        camera.set(android.graphics.PointF(.5f, .5f))
                        if (cameraReported.compareAndSet(false, true))
                            YLog.info("[RearNavigation] Google native camera anchor centred")
                    }
                }
                YLog.info("[RearNavigation] Google map layer and camera anchor installed")
            }.onFailure { YLog.warn("Google rear camera anchor unavailable", it) }
        }
    }
}
