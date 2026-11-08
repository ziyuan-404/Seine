package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.animation.TimeInterpolator
import android.content.Context
import android.view.animation.PathInterpolator

/** Curves for our visual layers; native gestures keep their own physics. */
internal object RearMotionCurves {
    val enter = PathInterpolator(.22f, 0f, .18f, 1f)
    val content = PathInterpolator(.25f, .1f, .25f, 1f)
    val exit = PathInterpolator(.3f, 0f, .7f, 1f)

    fun spring(context: Context, damping: Float, response: Float, duration: Long): TimeInterpolator = runCatching {
        val type = context.classLoader.loadClass("miuix.animation.utils.SpringInterpolator")
        val curve = type.getConstructor(Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .newInstance(damping, response)
        type.getMethod("setFakeDuration", Long::class.javaPrimitiveType).invoke(curve, duration)
        curve as TimeInterpolator
    }.getOrDefault(enter)
}
