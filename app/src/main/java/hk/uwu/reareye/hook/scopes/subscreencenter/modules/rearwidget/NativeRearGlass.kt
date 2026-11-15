package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import dalvik.system.PathClassLoader
import hk.uwu.reareye.hook.support.YLog

/** Add the installed OS4 edge stroke without replacing the factory backdrop. */
internal object NativeRearGlass {
    private var tokenClass: Class<*>? = null
    private var glassClass: Class<*>? = null
    private var navigationFailureReported = false

    fun navigation(view: View, dark: Boolean, radius: Float): Boolean = runCatching {
        val type = glassClass ?: PathClassLoader(
            view.context.packageManager.getApplicationInfo("miui.systemui.plugin", 0).sourceDir,
            View::class.java.classLoader).loadClass("miuix.theme.token.GlassToken").also { glassClass = it }
        val tokenName = if (dark) "Glass_Common_Medium_Regular_Dark" else "Glass_Common_Medium_Regular_High_Light"
        val token = type.getField(tokenName).get(null)
        val blur = type.getField("blurRadius").get(token)
        View::class.java.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType).invoke(view, 1)
        View::class.java.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType).invoke(view, 1)
        View::class.java.getMethod("setMiBackgroundBlurRadius", Int::class.javaPrimitiveType)
            .invoke(view, blur.javaClass.getField("smallBlurRadius").getInt(blur))
        View::class.java.getMethod("setMiViewMaterialType", Int::class.javaPrimitiveType).invoke(view, 1)
        View::class.java.getMethod("setMiGlassBlurRadius", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(view, blur.javaClass.getField("smallBlurRadius").getInt(blur),
                blur.javaClass.getField("bigBlurRadius").getInt(blur))
        val parameters = (type.getMethod("toShaderParams").invoke(token) as FloatArray).clone()
        // The system token targets a larger container. Fit its edge and optical
        // thickness to these short panels while retaining the native glass shader.
        val density = view.resources.displayMetrics.density
        parameters[19] = minOf(parameters[19], density * .5f)
        parameters[21] = minOf(parameters[21], density * .75f)
        parameters[24] *= .25f
        parameters[28] *= .35f
        parameters[29] *= .35f
        View::class.java.getMethod("setMiGlass", FloatArray::class.java).invoke(view, parameters)
        View::class.java.getMethod("setMiBloomStroke", FloatArray::class.java).invoke(view, FloatArray(0))
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(target: View, outline: Outline) {
                outline.setRoundRect(0, 0, target.width, target.height, radius)
            }
        }
        view.clipToOutline = true
        true
    }.getOrElse {
        if (!navigationFailureReported) {
            navigationFailureReported = true
            YLog.warn("OS4 navigation glass unavailable", it)
        }
        false
    }

    fun clearNavigation(view: View) {
        runCatching {
            View::class.java.getMethod("setMiGlass", FloatArray::class.java).invoke(view, FloatArray(0))
            View::class.java.getMethod("setMiBloomStroke", FloatArray::class.java).invoke(view, FloatArray(0))
            View::class.java.getMethod("setMiViewMaterialType", Int::class.javaPrimitiveType).invoke(view, 0)
            View::class.java.getMethod("setMiBackgroundBlurMode", Int::class.javaPrimitiveType).invoke(view, 0)
            View::class.java.getMethod("setMiViewBlurMode", Int::class.javaPrimitiveType).invoke(view, 0)
        }
    }
    fun begin(page: View): () -> Unit {
        val previousOutline = page.outlineProvider
        val previousClip = page.clipToOutline
        val previousElevation = page.elevation
        return runCatching {
            val context = page.context
            val type = tokenClass ?: PathClassLoader(
                context.packageManager.getApplicationInfo("miui.systemui.plugin", 0).sourceDir,
                View::class.java.classLoader).loadClass("miuix.theme.token.BloomStrokeToken").also { tokenClass = it }
            val parameters = (type.getField("Glass_Stroke_Big_Dark").get(null) as FloatArray).clone()
            require(parameters.size == 21)
            // Match the host's G3.d.y density conversion. The token affects only
            // the rim; blur, color blending and transparency remain untouched.
            val density = page.resources.displayMetrics.density
            parameters[0] = parameters[0] * density + .5f
            parameters[6] = parameters[6] * density + .5f
            val stroke = View::class.java.getMethod("setMiBloomStroke", FloatArray::class.java)
            val radius = context.classLoader.loadClass("o2.j").getField("t").getInt(null).toFloat()
            stroke.invoke(page, parameters)
            page.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            page.clipToOutline = true
            val restore: () -> Unit = {
                runCatching {
                    stroke.invoke(page, FloatArray(0))
                }
                page.outlineProvider = previousOutline; page.clipToOutline = previousClip
                page.elevation = previousElevation
            }
            restore
        }.getOrElse {
            YLog.warn("Native OS4 edge unavailable: ${it.javaClass.simpleName}: ${it.message}");
            { page.outlineProvider = previousOutline; page.clipToOutline = previousClip }
        }
    }
}
