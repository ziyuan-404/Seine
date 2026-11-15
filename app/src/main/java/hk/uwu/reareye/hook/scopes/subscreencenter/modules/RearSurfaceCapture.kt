package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.graphics.Bitmap
import android.graphics.Rect
import android.view.SurfaceControl
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/** Captures only the supplied layer and its children, including hosted map surfaces. */
internal object RearSurfaceCapture {
    private class CaptureApi {
        private val owner = sequenceOf("android.window.ScreenCaptureInternal", "android.window.ScreenCapture",
            "android.view.SurfaceControl").mapNotNull { runCatching { Class.forName(it) }.getOrNull() }
            .first { type -> type.methods.any { it.name == "captureLayers" } }
        private val argsClass = Class.forName(owner.name + "\$LayerCaptureArgs")
        private val builderClass = Class.forName(argsClass.name + "\$Builder")
        val builder: Constructor<*> = builderClass.getConstructor(SurfaceControl::class.java)
        val scale: Method = builderClass.getMethod("setFrameScale", Float::class.javaPrimitiveType)
        val childrenOnly: Method = builderClass.getMethod("setChildrenOnly", Boolean::class.javaPrimitiveType)
        val crop: Method = builderClass.getMethod("setSourceCrop", Rect::class.java)
        val build: Method = builderClass.getMethod("build")
        private val direct = owner.methods.firstOrNull {
            it.name == "captureLayers" && it.parameterTypes.contentEquals(arrayOf(argsClass))
        }
        private val listenerFactory = if (direct == null) owner.getMethod("createSyncCaptureListener") else null
        private val listenerType = listenerFactory?.returnType
        private val capture = direct ?: owner.getMethod("captureLayers", argsClass, listenerType)
        private val getBuffer = listenerType?.getMethod("getBuffer")
        private val bufferType = getBuffer?.returnType ?: capture.returnType
        private val asBitmap = bufferType.getMethod("asBitmap")
        private val hardwareBuffer = runCatching { bufferType.getMethod("getHardwareBuffer") }.getOrNull()

        fun capture(args: Any): Bitmap? {
            val buffer = if (listenerFactory == null) capture.invoke(null, args) else {
                val listener = listenerFactory.invoke(null)
                capture.invoke(null, args, listener)
                getBuffer!!.invoke(listener)
            } ?: return null
            return try {
                asBitmap.invoke(buffer) as? Bitmap
            } finally {
                // asBitmap retains its own hardware-buffer reference.
                (hardwareBuffer?.invoke(buffer) as? AutoCloseable)?.close()
            }
        }
    }

    private val api by lazy { runCatching { CaptureApi() }.getOrNull() }

    fun bitmap(layer: SurfaceControl, scale: Float, crop: Rect? = null, readable: Boolean = true): Bitmap? {
        if (!layer.isValid) return null
        val api = api ?: return null
        val builder = api.builder.newInstance(layer)
        api.scale.invoke(builder, scale)
        api.childrenOnly.invoke(builder, true)
        if (crop != null) api.crop.invoke(builder, crop)
        val args = api.build.invoke(builder) ?: return null
        val bitmap = api.capture(args) ?: return null
        if (!readable || bitmap.config != Bitmap.Config.HARDWARE) return bitmap
        return try { bitmap.copy(Bitmap.Config.ARGB_8888, false) } finally { bitmap.recycle() }
    }
}
