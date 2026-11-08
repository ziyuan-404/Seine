package hk.uwu.reareye.hook.scopes.subscreencenter.modules.charging

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** Typography measured from the reference clip at 38–39 s, in display coordinates. */
internal class ReferenceRearChargeText(context: android.content.Context) {
    private val system = runCatching { context.createPackageContext("com.android.systemui", 0) }.getOrNull()
    private fun art(name: String) = runCatching {
        val source = requireNotNull(system)
        source.getDrawable(source.resources.getIdentifier(name, "drawable", source.packageName))?.mutate()
    }.getOrNull()
    private val chargeLogo = art("charge_animation_filp_charge_icon")
    private val turboLogo = art("charge_animation_filp_turbo_icon")
    private val rapidLabel = runCatching {
        val source = requireNotNull(system)
        source.getString(source.resources.getIdentifier("rapid_charge_mode_tip", "string", source.packageName))
    }.getOrDefault("快速充电")
    private val family = runCatching { Typeface.Builder("/system/fonts/MiSansVF.ttf")
        .setFontVariationSettings("'wght' 450").setWeight(600).build() }
        .getOrDefault(Typeface.create("sans-serif", Typeface.NORMAL))
    private val number = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = Color.WHITE
        typeface = family
    }
    private val power = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = 0xffffe500.toInt()
        typeface = runCatching {
            val source = context.createPackageContext("com.android.systemui", 0)
            Typeface.createFromAsset(source.assets, "fonts/Flynnsans-charge.otf")
        }.getOrDefault(Typeface.create("sans-serif-medium", Typeface.NORMAL))
    }

    private val bounds = android.graphics.Rect()
    data class Layout(val textSize: Float, val cell: Float, val drawLeft: Float,
        val inkLeft: Float, val inkWidth: Float, val iconCenterX: Float)
    private data class Run(val cell: Float, val inkLeft: Float, val inkWidth: Float)

    private fun advance(character: Char, cell: Float) =
        if (character in '0'..'9') cell else number.measureText(character.toString())

    private fun measureRun(label: String): Run {
        val cell = ('0'..'9').maxOf { number.measureText(it.toString()) }
        var cursor = 0f
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        label.forEach { character ->
            val glyph = character.toString()
            val slot = advance(character, cell)
            number.getTextBounds(glyph, 0, glyph.length, bounds)
            val origin = cursor + (slot - number.measureText(glyph)) / 2f
            left = minOf(left, origin + bounds.left)
            right = maxOf(right, origin + bounds.right)
            cursor += slot
        }
        return Run(cell, left, (right - left).coerceAtLeast(1f))
    }

    fun layout(value: String, width: Float, height: Float, cameraEdge: Float, iconWidth: Float): Layout {
        val start = cameraEdge.coerceIn(0f, (width - iconWidth - 1f).coerceAtLeast(0f))
        val available = width - start
        number.textSize = height * .20f
        var run = measureRun("$value%")
        val limit = minOf(width * .422f, available - iconWidth - height * .06f).coerceAtLeast(1f)
        if (run.inkWidth > limit) {
            number.textSize *= limit / run.inkWidth
            run = measureRun("$value%")
        }
        // Allocate all three gaps from the actual visible glyph bounds. This
        // works for 38%, 38.55% and 100% without a fixed right-aligned anchor.
        val gap = ((available - iconWidth - run.inkWidth) / 3f).coerceAtLeast(0f)
        val inkLeft = start + gap * 2f + iconWidth
        return Layout(number.textSize, run.cell, inkLeft - run.inkLeft,
            inkLeft, run.inkWidth, start + gap + iconWidth / 2f)
    }

    fun draw(canvas: Canvas, value: String, width: Float, height: Float,
        numberAlpha: Float, powerAlpha: Float, speed: Int, watts: Int, closed: Boolean,
        stateLabel: String, layout: Layout) {
        // All characters, including fractional digits and %, share one face,
        // weight, size and baseline in the video.
        val label = "$value%"
        number.textSize = layout.textSize
        val cell = layout.cell
        val left = layout.inkLeft
        number.alpha = (numberAlpha * 255).toInt().coerceIn(0, 255)
        // Fixed digit slots and font bounds: 0→1 and 9→0 cannot resize or
        // recenter the entire value, shift its baseline, or move the power label.
        number.getTextBounds("0123456789%", 0, 11, bounds)
        val baseline = height * .50f - (bounds.top + bounds.bottom) / 2f
        var cursor = layout.drawLeft
        label.forEach { character ->
            val glyph = character.toString()
            val slot = advance(character, cell)
            canvas.drawText(glyph, cursor + (slot-number.measureText(glyph))/2f, baseline, number)
            cursor += slot
        }
        // Keep ordinary/unknown-wattage states legible too. Native speed and
        // real watts select the logos; the battery status supplies the fallback.
        if (speed == 2 && chargeLogo != null && turboLogo != null) {
            val naturalHeight = height * .066f
            val aspect = listOf(chargeLogo, turboLogo).sumOf {
                it.intrinsicWidth.toDouble() / it.intrinsicHeight.coerceAtLeast(1)
            }.toFloat()
            val naturalWidth = naturalHeight * aspect + height * .006f
            val fit = (layout.inkWidth / naturalWidth).coerceAtMost(1f)
            val logoHeight = naturalHeight * fit
            var x = left
            listOf(chargeLogo, turboLogo).forEach { logo ->
                val logoWidth = logoHeight * logo.intrinsicWidth / logo.intrinsicHeight.coerceAtLeast(1)
                logo.alpha = (powerAlpha * 255).toInt().coerceIn(0, 255)
                logo.setBounds(x.toInt(), (height * .70f - logoHeight).toInt(), (x + logoWidth).toInt(), (height * .70f).toInt())
                logo.draw(canvas)
                x += logoWidth + height * .006f * fit
            }
        } else {
            power.textSize = height * .066f
            power.alpha = (powerAlpha * 255).toInt().coerceIn(0, 255)
            val text = when {
                speed >= 3 && watts > 0 -> "${watts}W" + if (closed) "" else " MAX"
                speed == 1 -> if (stateLabel.startsWith("无线")) stateLabel else rapidLabel
                else -> stateLabel.ifBlank { "正在充电" }
            }
            canvas.drawText(text, left, height * .70f, power)
        }
    }
}
