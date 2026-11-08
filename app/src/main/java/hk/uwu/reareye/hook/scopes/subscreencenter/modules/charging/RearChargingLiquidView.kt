/* SPDX-License-Identifier: GPL-3.0-only */
package hk.uwu.reareye.hook.scopes.subscreencenter.modules.charging

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.SystemClock
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.RearMotionCurves

/** Reference clip, 35–39 s: blur → centered lightning → ring → liquid → charge value. */
internal class RearChargingLiquidView(context: Context) : RearChargingView(context) {
    private val typography = ReferenceRearChargeText(context)
    private val system = runCatching { context.createPackageContext("com.android.systemui", 0) }.getOrNull()
    private fun art(name: String) = runCatching {
        val source = requireNotNull(system)
        source.getDrawable(source.resources.getIdentifier(name, "drawable", source.packageName))?.mutate()
    }.getOrNull()
    private val strongBolt = art("charge_animation_strong_super_rapid_flip_charge_icon")
        ?: art("charge_animation_super_rapid_flip_charge_icon")
        ?: art("charge_animation_super_rapid_charge_icon")
    private val doubleBolt = art("charge_animation_super_rapid_flip_charge_icon") ?: strongBolt
    private val singleBolt = art("charge_animation_rapid_flip_charge_icon")
        ?: art("charge_animation_rapid_charge_icon")
    private val car = art("charge_animation_car_flip_mode_icon")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    private val motion = RearMotionCurves.enter
    private val grow = RearMotionCurves.spring(context, .84f, .58f, 650L)
    private val colours = ArgbEvaluator()
    private val green = 0xff00c995.toInt()
    private val yellow = 0xffffe500.toInt()
    private val liquidShader = RuntimeShader("""
        uniform float2 size;
        uniform float surface;
        uniform float phase;
        uniform float reveal;
        half4 main(float2 p) {
            float line = surface + sin(p.x / size.x * 6.283185 + phase) * size.y * 0.028
                + sin(p.x / size.x * 3.141593 - phase * 0.5) * size.y * 0.009;
            float depth = p.y - line;
            float water = smoothstep(-0.8, 0.8, depth);
            float haze = 0.72 * exp(min(depth, 0.0) / (size.y * 0.15));
            float alpha = mix(haze, 1.0, water) * reveal;
            half3 colour = mix(half3(0.035, 0.40, 0.21), half3(0.0, 0.98, 0.73), half(water));
            return half4(colour * half(alpha), half(alpha));
        }
    """.trimIndent())
    private val haloShader = RuntimeShader("""
        uniform float2 center;
        uniform float radius;
        uniform float3 tint;
        half4 main(float2 p) {
            float d = length(p - center) / radius;
            float noise = fract(sin(dot(p,float2(12.9898,78.233))) * 43758.5453) - 0.5;
            float a = clamp(exp(-d*d*2.5) * 0.54 + noise / 255.0, 0.0, 1.0);
            return half4(half3(tint) * half(a), half(a));
        }
    """.trimIndent())
    private val rippleShader = RuntimeShader("""
        uniform float2 center;
        uniform float radius;
        uniform float feather;
        half4 main(float2 p) {
            float d = (length(p - center) - radius) / feather;
            float a = exp(-d*d*0.5);
            return half4(half3(0.88, 1.0, 0.95) * half(a), half(a));
        }
    """.trimIndent())
    private var percentage = 0
    private var stateLabel = ""
    private var decimal: Int? = null
    private var entryAt = 0L
    private var running = false
    // Every 30 fps reference frame at 36.333–36.867 s, normalized to the
    // actual lit display height (the video crop includes a border below it).
    private val ringRadius = floatArrayOf(.10f,.11f,.13f,.16f,.19f,.22f,.28f,.35f,.44f,.56f,.69f,.81f,.94f,1.06f,1.15f,1.22f,1.25f)
    private val ringOpacity = floatArrayOf(0f,.025f,.05f,.08f,.13f,.20f,.28f,.34f,.34f,.29f,.24f,.19f,.13f,.08f,.04f,.01f,0f)
    private val glowExtent = floatArrayOf(.12f,.13f,.15f,.18f,.22f,.28f,.35f,.42f,.47f,.50f,.52f,.53f,.54f,.54f,.54f,.54f,.54f)
    private fun sample(values: FloatArray, progress: Float): Float {
        val x = progress.coerceIn(0f, 1f) * values.lastIndex
        val i = x.toInt(); val t = x - i
        fun at(n: Int) = values[n.coerceIn(0, values.lastIndex)]
        val a=at(i-1); val b=at(i); val c=at(i+1); val d=at(i+2)
        return .5f * (2f*b + (-a+c)*t + (2f*a-5f*b+4f*c-d)*t*t + (-a+3f*b-3f*c+d)*t*t*t)
    }
    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate(); postOnAnimation(this)
        }
    }

    override fun updateBattery(percent: Int, statusLabel: String, animateEntry: Boolean, socDecimal: Int?) {
        percentage = percent.coerceIn(0, 100)
        stateLabel = statusLabel
        decimal = socDecimal.takeIf { percentage < 100 && nativeSpeed > 0 }
        if (animateEntry) entryAt = 0L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (entryAt == 0L) entryAt = SystemClock.uptimeMillis()
        val age = if (ValueAnimator.areAnimatorsEnabled()) (SystemClock.uptimeMillis() - entryAt).toFloat() else 4000f
        fun phase(start: Float, duration: Float) = ((age - start) / duration).coerceIn(0f, 1f)
        fun ease(start: Float, duration: Float) = motion.getInterpolation(phase(start, duration))

        // The launcher stays visible behind the first lightning and ripple.
        // Only then does its background darken as the liquid enters from below.
        val dark = ease(1180f, 520f)
        canvas.drawColor(Color.argb((backdropProgress * (.10f + .83f * dark) * 255).toInt(), 0, 0, 0))
        val rise = ease(1690f, 480f)
        if (rise > 0f) {
            val fraction = percentage / 100f
            val surface = h * (1.12f - (fraction + .12f) * rise)
            liquidShader.setFloatUniform("size", w, h)
            liquidShader.setFloatUniform("surface", surface)
            liquidShader.setFloatUniform("phase", age / 1150f)
            liquidShader.setFloatUniform("reveal", ease(1690f, 180f))
            paint.shader = liquidShader; paint.alpha = 255
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null
        }
        val intro = ease(180f, 240f)
        val growth = grow.getInterpolation(phase(180f, 650f))
        val slide = ease(2050f, 440f)
        val bolt = when {
            nativeSpeed >= 3 && !doubleChargeClosed -> strongBolt
            nativeSpeed >= 2 -> doubleBolt
            else -> singleBolt ?: doubleBolt
        }
        val drawable = if (nativeCarMode) car ?: bolt else bolt
        val finalIconWidth = h * .28f * (drawable?.intrinsicWidth ?: 1) /
            (drawable?.intrinsicHeight ?: 1).coerceAtLeast(1)
        val value = decimalLevel(percentage, decimal) ?: percentage.toString()
        val cameraEdge = (rootWindowInsets?.displayCutout?.safeInsetLeft?.takeIf { it > 0 }
            ?: display?.cutout?.safeInsetLeft ?: 0).coerceAtLeast(0).toFloat()
        val textLayout = typography.layout(value, w, h, cameraEdge, finalIconWidth)
        val x = w * .59f + (textLayout.iconCenterX - w * .59f) * slide
        val y = h * .50f
        val iconH = h * (.35f - .07f * slide) * (.12f + .88f * growth)
        val colour = colours.evaluate(slide, green, yellow) as Int
        haloShader.setFloatUniform("center", x, y)
        // The soft glow continues expanding after the bolt has settled. Its
        // extent is independent of bolt size, including the later left shift.
        val glowRadius = h * sample(glowExtent, phase(300f, 900f)) * 1.4f * (1f - .16f * slide)
        haloShader.setFloatUniform("radius", glowRadius.coerceAtLeast(1f))
        haloShader.setFloatUniform("tint", Color.red(colour) / 255f, Color.green(colour) / 255f, Color.blue(colour) / 255f)
        // In the clip the halo peaks before the liquid arrives, then leaves a
        // faint halo around the bolt; a constant peak glow obscures the wave.
        val haloTail = 1f - .77f * ease(1120f, 650f)
        paint.shader = haloShader; paint.alpha = (255 * intro * haloTail).toInt()
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null; paint.alpha = 255

        // Separate radius and brightness curves preserve the visible expanding
        // band; a huge final radius made the old pulse leave the screen early.
        val ripple = phase(430f, 533.333f)
        if (ripple > 0f && ripple < 1f) {
            rippleShader.setFloatUniform("center", w * .59f, h * .50f)
            rippleShader.setFloatUniform("radius", h * sample(ringRadius, ripple))
            rippleShader.setFloatUniform("feather", h * (.045f + .08f * ripple))
            paint.shader = rippleShader
            paint.alpha = (sample(ringOpacity, ripple).coerceIn(0f, 1f) * 255).toInt()
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null; paint.alpha = 255
        }
        if (drawable != null && intro > 0f) {
            val iconW = iconH * drawable.intrinsicWidth / drawable.intrinsicHeight.coerceAtLeast(1)
            drawable.setTint(colour); drawable.alpha = (255 * intro).toInt()
            drawable.setBounds((x - iconW / 2).toInt(), (y - iconH / 2).toInt(), (x + iconW / 2).toInt(), (y + iconH / 2).toInt())
            drawable.draw(canvas)
        }
        // Like the OEM counter, switch to two decimal places only after a
        // real SOC sample arrives; the ordinary battery update is an integer.
        typography.draw(canvas, value, w, h, ease(2160f, 380f), ease(2550f, 360f),
            nativeSpeed, nativeWatts, doubleChargeClosed, stateLabel, textLayout)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateRunning() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) updateRunning()
    }
    private fun updateRunning() {
        val visible = isAttachedToWindow && windowVisibility == VISIBLE && isShown
        if (visible == running) return
        running = visible; removeCallbacks(frame)
        if (running) postOnAnimation(frame)
    }
    override fun onDetachedFromWindow() {
        running = false; removeCallbacks(frame)
        super.onDetachedFromWindow()
    }
}
