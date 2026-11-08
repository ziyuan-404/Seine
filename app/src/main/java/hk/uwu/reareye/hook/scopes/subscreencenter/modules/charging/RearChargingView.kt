package hk.uwu.reareye.hook.scopes.subscreencenter.modules.charging

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.RearMotionCurves

internal abstract class RearChargingView(context: Context) : View(context) {
    protected var nativeCarMode = false
    protected var nativeSpeed = 0
    protected var nativeWatts = -1
    protected var doubleChargeClosed = false
    private var decimalRate = 0
    private var decimalSampleAt = 0L
    protected var backdropProgress = 0f
    private var backdropAnimator: ValueAnimator? = null
    private var exitAnimator: ValueAnimator? = null
    private val curve = RearMotionCurves.enter
    private val blurMode = runCatching { View::class.java.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType) }.getOrNull()
    private val blurRadius = runCatching { View::class.java.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType) }.getOrNull()
    private val viewBlurMode = runCatching { View::class.java.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType) }.getOrNull()

    abstract fun updateBattery(percent: Int, statusLabel: String, animateEntry: Boolean = false, socDecimal: Int? = null)

    fun updateNativeCharge(speed: Int, watts: Int, closed: Boolean, rate: Int, sampledAt: Long, carMode: Boolean) {
        nativeCarMode = carMode
        nativeSpeed = speed; nativeWatts = watts; doubleChargeClosed = closed
        decimalRate = rate.coerceAtLeast(0); decimalSampleAt = sampledAt
        invalidate()
    }

    protected fun decimalLevel(level: Int, decimal: Int?): String? {
        if (nativeSpeed == 0 || decimal == null || level >= 100) return null
        // Same measured start/rate and bounded 10-second linear interval as
        // MiuiChargeController.ACTION_SOC_DECIMAL / MiuiChargePercentCountView.
        val progress = ((SystemClock.elapsedRealtime() - decimalSampleAt) / 10000f).coerceIn(0f, 1f)
        val value = (level + decimal / 100f + decimalRate / 100f * progress).coerceIn(0f, 99.99f)
        return String.format(java.util.Locale.ROOT, "%.2f", value)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        backdropAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 380L; interpolator = curve
            addUpdateListener {
                backdropProgress = it.animatedValue as Float
                runCatching {
                    blurMode?.invoke(this@RearChargingView, 1)
                    viewBlurMode?.invoke(this@RearChargingView, 1)
                    blurRadius?.invoke(this@RearChargingView, (backdropProgress * 24f * height / 596f).toInt())
                }
                invalidate()
            }
            start()
        }
    }

    fun exit(finished: () -> Unit) {
        if (exitAnimator != null) return
        isClickable = false; keepScreenOn = false
        exitAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 560L; interpolator = RearMotionCurves.exit
            addUpdateListener {
                val progress = it.animatedValue as Float
                alpha = 1f - progress
                runCatching { blurRadius?.invoke(this@RearChargingView, ((1f - progress) * 24f * height / 596f).toInt()) }
                val radius = progress * 12f * resources.displayMetrics.density
                setRenderEffect(if (radius > .1f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { finished() }
            })
            start()
        }
    }

    fun stopTransitions() {
        exitAnimator?.removeAllListeners(); exitAnimator?.cancel(); exitAnimator = null
        backdropAnimator?.cancel(); backdropAnimator = null
        setRenderEffect(null)
    }

    override fun onDetachedFromWindow() {
        stopTransitions()
        runCatching { blurMode?.invoke(this, 0); viewBlurMode?.invoke(this, 0) }
        super.onDetachedFromWindow()
    }
}
