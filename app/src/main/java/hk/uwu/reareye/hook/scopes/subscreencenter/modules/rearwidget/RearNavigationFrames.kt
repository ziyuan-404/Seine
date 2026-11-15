package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.RearSurfaceCapture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Bounded last-frame cache and off-main-thread capture for navigation cards. */
internal object RearNavigationFrames {
    data class Appearance(val bitmap: Bitmap, val mapLuma: Float, val barLuma: Float)
    data class Poster(val key: String, val bitmap: Bitmap, val time: Long,
        val dark: Boolean, val ambient: Boolean)

    private const val POSTER_LIFETIME_MS = 30_000L
    private val main = Handler(Looper.getMainLooper())
    private var poster: Poster? = null
    private var worker: ExecutorService? = null
    private val expire = object : Runnable {
        override fun run() {
            val cached = poster ?: return
            val remaining = POSTER_LIFETIME_MS - (SystemClock.uptimeMillis() - cached.time)
            if (remaining <= 0L) poster = null else main.postDelayed(this, remaining)
        }
    }

    fun find(key: String?): Poster? = poster?.takeIf {
        key != null && it.key == key && SystemClock.uptimeMillis() - it.time < POSTER_LIFETIME_MS
    }

    fun store(value: Poster) {
        poster = value
        if (!main.hasCallbacks(expire)) main.postDelayed(expire, POSTER_LIFETIME_MS)
    }

    fun retainSources(keys: Set<String>) {
        if (poster?.key !in keys) {
            poster = null
            main.removeCallbacks(expire)
        }
    }

    fun execute(task: () -> Unit) {
        val executor = worker ?: Executors.newSingleThreadExecutor { job ->
            Thread(job, "RearNavigationAppearance").apply { isDaemon = true }
        }.also { worker = it }
        executor.execute(task)
    }

    fun close() {
        main.removeCallbacks(expire)
        poster = null
        worker?.shutdownNow()
        worker = null
    }

    fun capture(source: RearNavigationMapService.CaptureSource, inspect: Boolean,
        safeLeft: Int, cardWidth: Int): Appearance? {
        val bitmap = RearSurfaceCapture.bitmap(source.control, source.scale, source.crop, readable = false)
            ?: return null
        if (!inspect) return Appearance(bitmap, -1f, -1f)
        try {
            val pixels = if (bitmap.config == Bitmap.Config.HARDWARE)
                bitmap.copy(Bitmap.Config.ARGB_8888, false) else bitmap
            try {
                val viewportWidth = source.crop.width() * source.scale
                val mapLuma = median(pixels, safeLeft, cardWidth, viewportWidth, bottom = false)
                if (mapLuma <= .001f) {
                    bitmap.recycle()
                    return null
                }
                return Appearance(bitmap, mapLuma,
                    median(pixels, safeLeft, cardWidth, viewportWidth, bottom = true))
            } finally {
                if (pixels !== bitmap) pixels.recycle()
            }
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    private fun median(pixels: Bitmap, safeLeft: Int, cardWidth: Int, viewportWidth: Float,
        bottom: Boolean): Float {
        val values = FloatArray(81)
        var count = 0
        for (row in 0 until 9) for (column in 0 until 9) {
            val x = ((safeLeft + (cardWidth - safeLeft) * (column + 1) / 10f) * pixels.width / viewportWidth)
                .toInt().coerceIn(0, pixels.width - 1)
            val y = if (bottom) pixels.height - 1 - row.coerceAtMost(pixels.height - 1)
                else pixels.height * (row + 1) / 10
            val color = pixels.getPixel(x, y.coerceIn(0, pixels.height - 1))
            if (Color.alpha(color) > 240) values[count++] = Color.luminance(color)
        }
        if (count == 0) return -1f
        java.util.Arrays.sort(values, 0, count)
        return values[count / 2]
    }
}
