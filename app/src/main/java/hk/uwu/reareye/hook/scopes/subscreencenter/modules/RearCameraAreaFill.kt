package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import hk.uwu.reareye.hook.support.HookPrefs
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.activityhost.ActivityHostTaskConfigurationAdapter as Server
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.RearAppDefaults

/** Samples the organized task itself, then paints only the unused area to its left. */
internal class RearCameraAreaFill(context: Context, private val prefs: HookPrefs) {
    companion object {
        @Volatile private var current: RearCameraAreaFill? = null
        fun onClosingTransition() { current?.suspendForClosing() }
    }
    private data class Task(val id: Int, val leash: SurfaceControl, val bounds: Rect)

    private class FillView(context: Context) : View(context) {
        var appBounds = Rect()
        var appRadius = 0f
        var fillColor = Color.BLACK
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: Canvas) {
            val shape = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
                if (appRadius > 0f) addRoundRect(RectF(appBounds), appRadius, appRadius, Path.Direction.CW)
                else addRect(RectF(appBounds), Path.Direction.CW)
            }
            paint.color = fillColor
            canvas.drawPath(shape, paint)
        }
    }

    private val display = context.getSystemService(DisplayManager::class.java).getDisplay(1)
    private val displayContext = display?.let(context::createDisplayContext)
    private val windowManager = displayContext?.getSystemService(WindowManager::class.java)
    private val activityManager = context.getSystemService(ActivityManager::class.java)
    @Suppress("DEPRECATION")
    private val size = Point().also { display?.getRealSize(it) }
    private val handler = Handler(Looper.getMainLooper())
    private val colorView = displayContext?.let(::FillView)
    private var params: WindowManager.LayoutParams? = null
    private var task: Task? = null
    private var color: Int? = null
    private var stopped = false
    @Volatile private var suppressedUntil = 0L
    private var reported = false
    private val tick = object : Runnable {
        override fun run() {
            if (stopped) return
            runCatching { refresh() }.onFailure {
                if (!reported) { YLog.warn("Rear camera-area color fill unavailable", it); reported = true }
                hide()
            }
            handler.postDelayed(this, 700L)
        }
    }

    init { current = this; if (display != null) handler.post(tick) }

    private fun suspendForClosing() {
        suppressedUntil = SystemClock.elapsedRealtime() + 2000L
        if (Looper.myLooper() == Looper.getMainLooper()) hide() else handler.post { hide() }
    }

    fun onTaskUpdated(info: ActivityManager.RunningTaskInfo, leash: SurfaceControl?, bounds: Rect?) {
        if (Server.field(info, "displayId") != 1 || Server.call(info, "getWindowingMode") != 5 || leash == null || bounds == null) return
        if (info.isVisible && leash.isValid && bounds.left > 0) task = Task(info.taskId, leash, Rect(bounds))
        else if (task?.id == info.taskId) { task = null; hide() }
    }

    private fun refresh() {
        if (!prefs.getBoolean(ConfigKeys.REAR_APP_ENABLED, RearAppDefaults.ENABLED) ||
            !prefs.getBoolean(ConfigKeys.REAR_APP_CAMERA_FILL, false) ||
            SystemClock.elapsedRealtime() < suppressedUntil) { hide(); return }
        val current = task ?: run { hide(); return }
        @Suppress("DEPRECATION")
        val frontTask = activityManager.getRunningTasks(50)
            .firstOrNull { Server.field(it, "displayId") == 1 && it.isVisible }
        if (!current.leash.isValid || current.bounds.left !in 1 until size.x ||
            frontTask?.taskId != current.id) {
            hide(); return
        }
        val sampled = captureEdgeColor(current.leash) ?: run { hide(); return }
        val view = colorView ?: return
        val wm = windowManager ?: return
        val radius = if (!prefs.getBoolean(ConfigKeys.REAR_APP_ROUNDED, true)) 0f else
            prefs.getFloat(ConfigKeys.REAR_APP_CORNER_RADIUS, RearAppDefaults.CORNER_RADIUS)
                .takeIf { it > 0f } ?: (display?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_RIGHT)?.radius?.toFloat() ?: 0f)
        val width = (current.bounds.left + radius.toInt()).coerceAtMost(size.x)
        val shapeChanged = view.appBounds != current.bounds || view.appRadius != radius
        view.appBounds = Rect(current.bounds)
        view.appRadius = radius
        view.fillColor = sampled
        if (params == null) {
            val layout = WindowManager.LayoutParams(width, size.y,
                WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.LEFT
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                title = "Rear app camera area"
            }
            wm.addView(view, layout)
            params = layout
            color = sampled
        } else {
            if (params!!.width != width) {
                params!!.width = width
                wm.updateViewLayout(view, params)
            }
            if (color != sampled || shapeChanged) { view.invalidate(); color = sampled }
        }
    }

    private fun captureEdgeColor(leash: SurfaceControl): Int? {
        val owner = sequenceOf("android.window.ScreenCaptureInternal", "android.window.ScreenCapture",
            "android.view.SurfaceControl").mapNotNull { runCatching { Class.forName(it) }.getOrNull() }
            .firstOrNull { type -> type.methods.any { it.name == "captureLayers" } } ?: return null
        val argsClass = Class.forName(owner.name + "\$LayerCaptureArgs")
        val builderClass = Class.forName(argsClass.name + "\$Builder")
        val builder = builderClass.getConstructor(SurfaceControl::class.java).newInstance(leash)
        builderClass.getMethod("setFrameScale", Float::class.javaPrimitiveType).invoke(builder, .12f)
        val args = builderClass.getMethod("build").invoke(builder)
        val direct = owner.methods.firstOrNull { it.name == "captureLayers" && it.parameterCount == 1 }
        val buffer = if (direct != null) direct.invoke(null, args) else {
            val listener = owner.getMethod("createSyncCaptureListener").invoke(null)
            owner.getMethod("captureLayers", argsClass, listener.javaClass).invoke(null, args, listener)
            listener.javaClass.getMethod("getBuffer").invoke(listener)
        } ?: return null
        val bitmap = buffer.javaClass.getMethod("asBitmap").invoke(buffer) as? Bitmap ?: return null
        val readable = if (bitmap.config == Bitmap.Config.HARDWARE)
            bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: run { bitmap.recycle(); return null }
        else bitmap
        return try {
            if (readable.width < 2 || readable.height < 2) return null
            val frequencies = HashMap<Int, IntArray>()
            for (yIndex in 2..18) {
                val y = (readable.height * yIndex / 20).coerceIn(0, readable.height - 1)
                for (x in 1..minOf(4, readable.width - 1)) {
                    val pixel = readable.getPixel(x, y)
                    if (Color.alpha(pixel) < 240) continue
                    val bucket = Color.rgb(Color.red(pixel) and 0xF8,
                        Color.green(pixel) and 0xF8, Color.blue(pixel) and 0xF8)
                    val samples = frequencies.getOrPut(bucket) { IntArray(4) }
                    samples[0]++
                    samples[1] += Color.red(pixel)
                    samples[2] += Color.green(pixel)
                    samples[3] += Color.blue(pixel)
                }
            }
            frequencies.values.maxByOrNull { it[0] }?.let { samples ->
                Color.rgb(samples[1] / samples[0], samples[2] / samples[0], samples[3] / samples[0])
            }
        } finally {
            if (readable !== bitmap) readable.recycle()
            bitmap.recycle()
        }
    }

    private fun hide() {
        val view = colorView ?: return
        if (params != null) runCatching { windowManager?.removeViewImmediate(view) }
        params = null
        color = null
    }

    fun close() {
        stopped = true
        if (current === this) current = null
        handler.removeCallbacks(tick)
        hide()
    }
}
