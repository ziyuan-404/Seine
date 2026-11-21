package hk.uwu.reareye.hook.scopes.subscreencenter.modules.charging

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import hk.uwu.reareye.BuildConfig
import hk.uwu.reareye.hook.support.YLog
import kotlin.math.roundToInt

/** Xiaomi 17 Ultra Leica theme charge artwork, arranged for the landscape rear display. */
internal class RearLeicaChargingView(context: Context, green: Boolean = false) : RearChargingView(context) {
    // Remap the red artwork to the teal-green charge palette, retaining neutral pixels and alpha.
    private val glowTint = if (green) ColorMatrixColorFilter(floatArrayOf(
        0f, .75f, .25f, 0f, 0f,
        1f, 0f, 0f, 0f, 0f,
        .58f, 0f, .42f, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )) else null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val destination = RectF()
    private var percentage = 0
    private var animation: AnimatedImageDrawable? = null
    private var glyphs = emptyList<Bitmap>()
    private var icons = emptyList<Bitmap>()
    private var decodeGeneration = 0
    private var requestedWidth = 0
    private var requestedHeight = 0
    private var visibleToUser = false

    override fun updateBattery(percent: Int, statusLabel: String, animateEntry: Boolean, socDecimal: Int?) {
        percentage = percent.coerceIn(0, 100)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        loadArtwork(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (isAttachedToWindow) loadArtwork(w, h)
    }

    private fun loadArtwork(targetWidth: Int, targetHeight: Int) {
        if (targetWidth <= 0 || targetHeight <= 0) return
        if (requestedWidth == targetWidth && requestedHeight == targetHeight) return
        requestedWidth = targetWidth
        requestedHeight = targetHeight
        val generation = ++decodeGeneration
        // Decode at rear-display resolution off the UI thread. AnimatedImageDrawable
        // streams the sequence without retaining 154 full-size decoded bitmaps.
        Thread({
            runCatching {
                val assets = context.createPackageContext(BuildConfig.APPLICATION_ID, 0).assets
                val source = ImageDecoder.createSource(assets, "charging/leica/glow.webp")
                val glow = ImageDecoder.decodeDrawable(source) { decoder, _, _ ->
                    decoder.setTargetSize(targetWidth, targetHeight)
                } as AnimatedImageDrawable
                glow.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                fun bitmap(name: String): Bitmap = assets.open("charging/leica/$name.png").use {
                    requireNotNull(BitmapFactory.decodeStream(it))
                }
                val numbers = "0123456789b".map { bitmap("num_$it") }
                val endpoints = listOf(bitmap("icon_0"), bitmap("icon_1"))
                post {
                    if (!isAttachedToWindow || generation != decodeGeneration) return@post
                    animation?.stop()
                    animation?.callback = null
                    animation = glow
                    glyphs = numbers
                    icons = endpoints
                    glow.callback = this
                    glow.colorFilter = glowTint
                    glow.setBounds(0, 0, targetWidth, targetHeight)
                    updatePlayback()
                    invalidate()
                    YLog.info("[REAREye-Charging] Leica artwork ready ${targetWidth}x$targetHeight")
                }
            }.onFailure {
                post {
                    if (generation == decodeGeneration) {
                        requestedWidth = 0
                        requestedHeight = 0
                    }
                }
                YLog.warn("Leica rear charging artwork unavailable", it)
            }
        }, "Seine-LeicaCharge").start()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibleToUser = isVisible
        updatePlayback()
    }

    private fun updatePlayback() {
        val glow = animation ?: return
        if (isAttachedToWindow && visibleToUser && ValueAnimator.areAnimatorsEnabled()) {
            glow.start()
        } else {
            glow.stop()
        }
    }

    override fun verifyDrawable(who: Drawable): Boolean = who === animation || super.verifyDrawable(who)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawColor(Color.argb((255 * backdropProgress).toInt(), 0, 0, 0))
        animation?.let {
            // The glow fills the physical display, including the area around
            // the camera. Only foreground information observes the safe insets.
            it.alpha = (255 * backdropProgress).toInt()
            it.draw(canvas)
        }
        if (glyphs.isEmpty()) return
        val cutout = rootWindowInsets?.displayCutout ?: display?.cutout
        val left = (cutout?.safeInsetLeft ?: 0).toFloat()
        val right = w - (cutout?.safeInsetRight ?: 0)
        val top = (cutout?.safeInsetTop ?: 0).toFloat()
        val bottom = h - (cutout?.safeInsetBottom ?: 0)
        val availableWidth = (right - left).coerceAtLeast(1f)
        val availableHeight = (bottom - top).coerceAtLeast(1f)
        val centerX = (left + right) / 2f
        val centerY = top + availableHeight * .43f
        val digits = percentage.toString().map { glyphs[it - '0'] } + glyphs[10]
        val naturalWidth = digits.sumOf { it.width }
        val scale = minOf(availableHeight * .34f / 379f, availableWidth * .82f / naturalWidth)
        var x = centerX - naturalWidth * scale / 2f
        paint.alpha = (255 * backdropProgress).toInt()
        for (digit in digits) {
            val digitHeight = digit.height * scale
            destination.set(x, centerY - digitHeight / 2f, x + digit.width * scale, centerY + digitHeight / 2f)
            canvas.drawBitmap(digit, null, destination, paint)
            x += digit.width * scale
        }
        drawGauge(canvas, centerX, top + availableHeight * .75f, availableWidth, availableHeight)
    }

    private fun drawGauge(canvas: Canvas, centerX: Float, centerY: Float, availableWidth: Float, availableHeight: Float) {
        // Preserve the theme's 23 marks, long sixth marks, endpoint icons and
        // integer battery level. The source numbers and percentage are sprites.
        val scale = minOf(availableWidth * .68f / 450f, availableHeight / 596f)
        val firstX = centerX - 198f * scale
        val filled = (percentage / 100f * 23).roundToInt()
        paint.color = Color.WHITE
        for (index in 0 until 23) {
            val active = filled >= index
            val tickWidth = (if (active) 5f else 4f) * scale
            val tickHeight = (if ((index + 1) % 6 == 0) 34f else 16f) * scale
            val tickX = firstX + index * 18f * scale
            paint.alpha = (255 * backdropProgress * if (active) 1f else .24f).toInt()
            canvas.drawRoundRect(tickX - tickWidth / 2f, centerY - tickHeight / 2f,
                tickX + tickWidth / 2f, centerY + tickHeight / 2f, 3f * scale, 3f * scale, paint)
        }
        for (index in icons.indices) {
            val icon = icons[index]
            val iconX = firstX + (if (index == 0) -20f else 416f) * scale
            val iconWidth = icon.width * scale
            val iconHeight = icon.height * scale
            destination.set(iconX - iconWidth / 2f, centerY - iconHeight / 2f,
                iconX + iconWidth / 2f, centerY + iconHeight / 2f)
            paint.alpha = (255 * backdropProgress * if (index == 0 || filled >= 22) 1f else .24f).toInt()
            canvas.drawBitmap(icon, null, destination, paint)
        }
    }

    override fun onDetachedFromWindow() {
        ++decodeGeneration
        requestedWidth = 0
        requestedHeight = 0
        visibleToUser = false
        animation?.stop()
        animation?.callback = null
        animation = null
        glyphs = emptyList()
        icons = emptyList()
        super.onDetachedFromWindow()
    }
}
