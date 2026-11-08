package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget.NativeRearCardHost
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget.NativeRearMotion
import java.lang.ref.WeakReference

/** Visual feedback only. The OEM helper owns the camera and authentication result. */
internal class RearFaceFeedback(context: android.content.Context) : View(context) {
    companion object {
        private const val TAG = "rear_face_camera_feedback"
        private const val CYCLE_MS = 1350L
        private data class Layout(val visibility: Int, val background: Drawable?, val flags: Int?, val format: Int?, val blur: Int?)
        private val layouts = java.util.WeakHashMap<View, Layout>()
        private val pending = java.util.IdentityHashMap<RearFaceFeedback, () -> Unit>()
        private val afterExit = mutableListOf<() -> Unit>()
        private var closing = false

        fun clearNotificationBlur(overlay: FrameLayout, passTouches: Boolean? = null) {
            overlay.background = null
            overlay.setRenderEffect(null)
            listOf("setMiBackgroundBlurMode", "setMiViewBlurMode", "setMiBackgroundBlurRadius").forEach { name ->
                runCatching { View::class.java.getMethod(name, Int::class.javaPrimitiveType).invoke(overlay, 0) }
            }
            runCatching { View::class.java.getMethod("setPassWindowBlurEnabled", Boolean::class.javaPrimitiveType).invoke(overlay, false) }
            (overlay.layoutParams as? WindowManager.LayoutParams)?.let { window ->
                window.flags = window.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv() and
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                if (passTouches != null) {
                    // Keep the OEM retry panel interactive after failure. During
                    // a scan the trusted overlay only draws over the card pager.
                    window.flags = if (passTouches) window.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    else window.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                }
                window.setBlurBehindRadius(0); window.dimAmount = 0f
                if (overlay.isAttachedToWindow) runCatching {
                    overlay.context.getSystemService(WindowManager::class.java).updateViewLayout(overlay, window)
                }
            }
        }

        fun update(overlay: FrameLayout, scanning: Boolean, success: Boolean, application: Boolean,
                   notificationAnchor: WeakReference<View>?) {
            if (scanning) closing = false
            val id = overlay.resources.getIdentifier("face_auth_card", "id", "com.xiaomi.subscreencenter")
            val panel = overlay.findViewById<View>(id) ?: return
            val feedback = overlay.findViewWithTag<RearFaceFeedback>(TAG)
            if (scanning || success) {
                if (!layouts.containsKey(panel)) {
                    val window = overlay.layoutParams as? WindowManager.LayoutParams
                    layouts[panel] = Layout(panel.visibility,
                        overlay.background?.constantState?.newDrawable()?.mutate() ?: overlay.background,
                        window?.flags, window?.format, window?.blurBehindRadius)
                    overlay.addOnAttachStateChangeListener(object : OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) = Unit
                        override fun onViewDetachedFromWindow(v: View) { layouts.remove(panel); v.removeOnAttachStateChangeListener(this) }
                    })
                }
                panel.visibility = View.INVISIBLE
                overlay.setBackgroundColor(if (application) 0x33000000 else Color.TRANSPARENT)
                overlay.clipChildren = false
                // Clear both Android and Xiaomi blur; clearing only the window flag
                // leaves the ROM's view background blur active on a notification.
                run {
                    listOf("setMiBackgroundBlurMode", "setMiViewBlurMode", "setMiBackgroundBlurRadius").forEach { name ->
                        runCatching { View::class.java.getMethod(name, Int::class.javaPrimitiveType).invoke(overlay, 0) }
                    }
                }
                (overlay.layoutParams as? WindowManager.LayoutParams)?.let { window ->
                    window.flags = (window.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN).let {
                        if (application) it or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                        else it and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv() and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                    }
                    window.format = android.graphics.PixelFormat.TRANSLUCENT
                    window.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    window.setFitInsetsTypes(0)
                    window.setBlurBehindRadius(if (application) (24 * overlay.resources.displayMetrics.density).toInt() else 0)
                    window.dimAmount = if (application) window.dimAmount else 0f
                    if (overlay.isAttachedToWindow) overlay.context.getSystemService(WindowManager::class.java).updateViewLayout(overlay, window)
                }
                val current = feedback ?: RearFaceFeedback(overlay.context).also {
                    it.tag = TAG; overlay.addView(it, FrameLayout.LayoutParams(-1, -1))
                }
                current.setRenderEffect(null)
                listOf("setMiViewBlurMode", "setMiBackgroundBlurMode", "setMiBackgroundBlurRadius").forEach { name ->
                    runCatching { View::class.java.getMethod(name, Int::class.javaPrimitiveType).invoke(current, 0) }
                }
                current.inline = !application
                current.trackAnchor(notificationAnchor)
                if (scanning) current.scan() else current.showSuccess()
            } else if (!application) {
                // Failure/retry stays beside the placeholder too. Restoring the
                // native failure panel used to re-enable its full-window blur.
                panel.visibility = View.INVISIBLE
                feedback?.showFailure()
                clearNotificationBlur(overlay)
                overlay.setOnClickListener {
                    val retryId = overlay.resources.getIdentifier("authen_retry", "id", "com.xiaomi.subscreencenter")
                    overlay.findViewById<View>(retryId)?.takeIf { it.visibility == View.VISIBLE }?.performClick()
                }
            } else {
                // Native retry/no-enrollment text stays intact. Fade the camera
                // indicator out instead of abruptly dropping its final frame.
                val state = layouts.remove(panel)
                val restore = {
                    state?.let {
                        panel.visibility = it.visibility; overlay.background = it.background
                        (overlay.layoutParams as? WindowManager.LayoutParams)?.let { window ->
                            it.flags?.let { flags -> window.flags = flags }; it.format?.let { format -> window.format = format }
                            it.blur?.let(window::setBlurBehindRadius)
                            if (overlay.isAttachedToWindow) overlay.context.getSystemService(WindowManager::class.java).updateViewLayout(overlay, window)
                        }
                    }
                }
                if (feedback != null) feedback.fadeOut { overlay.removeView(it); restore() } else restore()
            }
        }

        fun deferRemoval(overlay: FrameLayout, remove: () -> Unit): Boolean {
            if (closing || Looper.myLooper() != Looper.getMainLooper() ||
                overlay.display?.displayId != 1 || overlay.display?.state != android.view.Display.STATE_ON || !overlay.isAttachedToWindow) return false
            val feedback = overlay.findViewWithTag<RearFaceFeedback>(TAG) ?: return false
            if (feedback.removalAllowed || !ValueAnimator.areAnimatorsEnabled()) return false
            if (feedback.retiring) return true
            val window = overlay.layoutParams as? WindowManager.LayoutParams ?: return false
            // Native termination continues immediately. This tail cannot hold
            // keyboard focus, receive touches, or delay credential entry.
            window.flags = (window.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) and
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            window.windowAnimations = 0
            overlay.context.getSystemService(WindowManager::class.java).updateViewLayout(overlay, window)
            feedback.retiring = true
            val done = {
                if (!feedback.removalAllowed) {
                    feedback.removalAllowed = true
                    pending.remove(feedback)
                    feedback.cancelMotion()
                    remove()
                    if (pending.isEmpty() && !closing) {
                        val callbacks = afterExit.toList(); afterExit.clear()
                        android.os.Handler(Looper.getMainLooper()).post { callbacks.forEach { it() } }
                    }
                }
            }
            pending[feedback] = done
            // Safety bound even if an animator is interrupted by platform state.
            overlay.postDelayed({ pending[feedback]?.invoke() }, 1600L)
            feedback.fadeOut { done() }
            return true
        }

        fun finishPendingImmediately() {
            closing = true
            afterExit.clear()
            pending.values.toList().forEach { it() }
            pending.clear()
        }

        fun afterVisualExit(callback: () -> Unit) {
            if (pending.isEmpty()) callback() else afterExit.add(callback)
        }
    }

    private val system = runCatching { context.createPackageContext("com.android.systemui", 0) }.getOrNull()
    private fun art(name: String) = runCatching {
        val source = requireNotNull(system)
        source.getDrawable(source.resources.getIdentifier(name, "drawable", source.packageName))?.mutate()
    }.getOrNull()
    private val face = art("face_unlock_face")
    private val ring = art("face_unlock_ring")
    private val successIcon = art("face_unlock_success_ani")
    private var inline = false
    private var anchor: WeakReference<View>? = null
    private var anchorObserver: ViewTreeObserver? = null
    private val anchorPreDraw = ViewTreeObserver.OnPreDrawListener {
        // The notification and face overlay have separate window roots.
        invalidate()
        true
    }
    private val cardVisible = Rect()
    private val cardOffset = Point()
    private val screenLocation = IntArray(2)
    private val overlayLocation = IntArray(2)
    private var success = false
    private var retiring = false
    private var removalAllowed = false
    private val circle = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isDither = true }
    // A broad luminous ribbon sits above a continuous, faint feather. Neither
    // is part of the window background blur; dither avoids alpha banding.
    private val smoothGlow = android.graphics.RuntimeShader("""
        uniform float2 center;
        uniform float radius;
        uniform float feather;
        uniform float4 arcs;
        float arc(float degrees, float tail, float head) {
            float extent = clamp(head - tail, 0.0, 360.0);
            if (extent < 0.1) return 0.0;
            if (extent > 359.5) return 1.0;
            float p = mod(degrees - tail + 1080.0, 360.0);
            float soft = min(24.0, extent * 0.28);
            return smoothstep(0.0, max(0.1, soft), p)
                * (1.0 - smoothstep(max(0.0, extent-soft), extent, p));
        }
        half4 main(float2 p) {
            float2 delta = p - center;
            float distance = length(delta) - radius;
            float inner = exp(-0.5 * pow((distance - feather * 0.22) / (feather * 0.43), 2.0));
            float outer = exp(-0.5 * pow((distance - feather * 0.70) / feather, 2.0));
            float degrees = atan(delta.y, delta.x) * 57.2957795;
            float core = inner * arc(degrees, arcs.x, arcs.y) * 0.83;
            float halo = outer * arc(degrees, arcs.z, arcs.w) * 0.48;
            float noise = fract(sin(dot(p, float2(12.9898,78.233))) * 43758.5453) - 0.5;
            float a = clamp(core + halo + noise / 255.0, 0.0, 1.0);
            half3 tint = mix(half3(0.78, 1.0, 0.91), half3(1.0), half(core / max(0.001, core+halo)));
            return half4(tint * half(a), half(a));
        }
    """.trimIndent())
    private var glowRadius = 0f
    private var angle = 0f
    private var length = 72f
    private var outerHead = 0f
    private var outerTail = 0f
    private var glowAlpha = 1f
    private var iconAlpha = 1f
    private var iconScale = 1f
    private var startedAt = 0L
    private var finishAnimator: ValueAnimator? = null
    // Every original 30 fps frame, 4.967–6.000 s. The tail advances rapidly
    // after the first stretch, brakes, then the head catches it one lap later.
    private val innerHeads = floatArrayOf(-45f,-17f,9f,22f,42f,57f,70f,90f,105f,120f,140f,153f,179f,210f,233f,259f,286f,311f,336f,358f,370f,390f,404f,422f,441f,456f,462f,480f,488f,504f,519f,530f)
    private val innerTails = floatArrayOf(-76f,-60f,-59f,-53f,-43f,-36f,-23f,-9f,9f,23f,49f,74f,93f,112f,137f,141f,150f,160f,164f,170f,170f,170f,170f,170f,170f,170f,170f,170f,170f,170f,170f,170f)
    private val outerHeads = floatArrayOf(-60f,-30f,1f,24f,44f,64f,79f,104f,117f,139f,157f,183f,201f,221f,245f,269f,286f,308f,332f,351f,371f,393f,411f,435f,448f,468f,479f,500f,510f,510f,510f,510f)
    private val outerTails = floatArrayOf(-90f,-72f,-56f,-53f,-44f,-35f,-23f,-11f,11f,25f,46f,65f,81f,99f,112f,121f,130f,135f,142f,147f,150f,150f,150f,150f,150f,150f,150f,150f,150f,150f,150f,150f)
    private fun sample(values: FloatArray, progress: Float): Float {
        val x=progress.coerceIn(0f,1f)*(values.size-1)
        val i=x.toInt().coerceAtMost(values.lastIndex-1)
        val t=x-i
        val start = values[i]
        val end = values[i + 1]
        val step = end - start
        fun tangent(before: Float, after: Float): Float =
            if (before * after <= 0f) 0f else 2f * before * after / (before + after)
        val startSlope = if (i == 0) step else tangent(start - values[i - 1], step)
        val endSlope = if (i + 1 == values.lastIndex) step else tangent(step, values[i + 2] - end)
        // Shape-preserving cubic. Braking to a plateau cannot overshoot it.
        val t2 = t * t
        return start + t2 * (3f - 2f * t) * step +
            t * (1f - t) * ((1f - t) * startSlope - t * endSlope)
    }
    private fun scanFrame(elapsed: Long) {
        val t=(elapsed/1033.333f).coerceIn(0f,1f)
        angle=sample(innerHeads,t)
        length=(angle-sample(innerTails,t)).coerceIn(0f,360f)
        outerHead=sample(outerHeads,t);outerTail=sample(outerTails,t)
        // The reference holds a completed ring while recognition finishes;
        // rotating a complete ring adds no information and changes the exit.
    }
    private val orbit = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = CYCLE_MS; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener {
            val elapsed = SystemClock.uptimeMillis() - startedAt
            scanFrame(elapsed)
            invalidate()
        }
    }

    private fun scan() {
        success = false
        finishAnimator?.removeAllListeners(); finishAnimator?.cancel(); finishAnimator = null
        animate().cancel()
        if (isAttachedToWindow && !retiring) {
            alpha = 0f
            glowAlpha = 1f
            iconAlpha = 1f
            iconScale = 1f
            startedAt = SystemClock.uptimeMillis()
            scanFrame(0L)
            orbit.start()
            animate().alpha(1f).setDuration(320L).setInterpolator(RearMotionCurves.content).start()
        }
    }

    private fun showFailure() {
        orbit.cancel()
        finishAnimator?.removeAllListeners(); finishAnimator?.cancel(); finishAnimator = null
        success = false
        animateExit({ })
    }

    private fun showSuccess() {
        success = true
        successIcon?.callback = this
        (successIcon as? android.graphics.drawable.Animatable)?.start()
        // Finish the current complete revolution when native removal arrives.
        invalidate()
    }

    private fun animateExit(done: () -> Unit, entireOverlay: Boolean = false) {
        orbit.cancel()
        animate().cancel()
        val overlay = (parent as? FrameLayout)?.takeIf { entireOverlay && retiring }
        val window = overlay?.layoutParams as? WindowManager.LayoutParams
        val initialBlur = window?.blurBehindRadius ?: 0
        overlay?.animate()?.cancel()
        val fromLength = length
        val outerLength=outerHead-outerTail
        // A full circle has no visible seam. Align that hidden seam to the
        // reference exit before the first gap opens.
        val from = if (fromLength >= 359.5f) 630f else angle
        val outerFrom = if (outerLength >= 359.5f) 630f else outerHead
        val alphaFrom=glowAlpha
        // 6.267–6.767 s: tail accelerates past the lower-left arc while the
        // leading end brakes near the upper-right. It is not a uniform spin.
        val travelInner=floatArrayOf(0f,11f,21f,30f,39f,45f,48f,51f,54f,57f,59f,60f,60f,60f,60f,60f)
        val travelOuter=floatArrayOf(0f,16f,24f,29f,36f,41f,47f,51f,57f,58f,59f,59f,59f,59f,59f,59f)
        val remainingInner=floatArrayOf(1f,.933f,.872f,.783f,.689f,.592f,.481f,.419f,.364f,.339f,.286f,.264f,.214f,.183f,.05f,0f)
        val remainingOuter=floatArrayOf(1f,.956f,.875f,.775f,.70f,.622f,.561f,.489f,.45f,.411f,.35f,.292f,.231f,.106f,.047f,0f)
        finishAnimator?.removeAllListeners(); finishAnimator?.cancel()
        finishAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 533L
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                // Reverse the growth: the trailing end catches the moving head,
                // leaving a finite dissolving strip instead of fading a fixed arc.
                length = fromLength * sample(remainingInner,t).coerceIn(0f,1f)
                angle = from + sample(travelInner,t)
                outerHead=outerFrom+sample(travelOuter,t)
                outerTail=outerHead-outerLength*sample(remainingOuter,t).coerceIn(0f,1f)
                glowAlpha=alphaFrom*(1f-RearMotionCurves.exit.getInterpolation(((t-.55f)/.45f).coerceIn(0f,1f)))
                if (entireOverlay) {
                    iconAlpha = 1f - RearMotionCurves.content.getInterpolation(t)
                    if (inline) iconScale = .86f + .14f * iconAlpha
                }
                invalidate()
                if (overlay != null && window != null && overlay.isAttachedToWindow) {
                    val opacity=1f-RearMotionCurves.content.getInterpolation(t)
                    if(!inline) overlay.setBackgroundColor(Color.argb((51*opacity).toInt(),0,0,0))
                    window.setBlurBehindRadius((initialBlur * opacity).toInt())
                    runCatching { overlay.context.getSystemService(WindowManager::class.java).updateViewLayout(overlay, window) }
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) { done() }
            })
            start()
        }
    }

    private fun fadeOut(done: (RearFaceFeedback) -> Unit) = animateExit({ done(this) }, entireOverlay = true)

    private fun cancelMotion() {
        orbit.cancel()
        finishAnimator?.removeAllListeners(); finishAnimator?.cancel(); finishAnimator = null
        animate().cancel()
        if (retiring) (parent as? View)?.animate()?.cancel()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val cutout = rootWindowInsets?.displayCutout?.safeInsetLeft?.takeIf { it > 0 }?.toFloat() ?: w * .303f
        val x = cutout / 2f; val y = h * .25f; val radius = cutout * .46f
        circle.set(x - radius, y - radius, x + radius, y + radius)
        val feather = 104f * h / 596f
        glowRadius = radius + feather
    }

    private fun trackAnchor(next: WeakReference<View>?) {
        if (anchor === next) return
        anchorObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(anchorPreDraw)
        anchor = next
        anchorObserver = next?.get()?.viewTreeObserver?.takeIf { it.isAlive }
        anchorObserver?.addOnPreDrawListener(anchorPreDraw)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val scale = height / 596f
        canvas.save()
        smoothGlow.setFloatUniform("center", circle.centerX(), circle.centerY())
        smoothGlow.setFloatUniform("radius", circle.width() / 2f)
        smoothGlow.setFloatUniform("feather", 19f * scale)
        smoothGlow.setFloatUniform("arcs", angle-length,angle,outerTail,outerHead)
        paint.shader = smoothGlow
        paint.alpha = (glowAlpha * 255).toInt().coerceIn(0, 255)
        canvas.drawRect(circle.left - glowRadius, circle.top - glowRadius,
            circle.right + glowRadius, circle.bottom + glowRadius, paint)
        canvas.restore(); paint.shader = null; paint.alpha = 255
        val size = if (inline) 52f * scale else 112f * scale
        val x: Float
        val y: Float
        canvas.save()
        if (inline) {
            val card = anchor?.get()
            val message = card?.findViewWithTag<android.widget.TextView>("rear_message_privacy_label")
                ?: card?.findViewById<android.widget.TextView>(card.resources.getIdentifier("notification_content", "id", "com.xiaomi.subscreencenter"))
            val line = message?.layout
            if (card == null || message == null || line == null || line.lineCount == 0 ||
                !card.isAttachedToWindow || !message.isShown || NativeRearMotion.isFlipping(card) ||
                (NativeRearCardHost.container(card) != null && !NativeRearCardHost.isSelected(card)) ||
                !card.getGlobalVisibleRect(cardVisible, cardOffset)) {
                canvas.restore()
                return
            }
            getLocationOnScreen(overlayLocation)
            card.getLocationOnScreen(screenLocation)
            cardVisible.offset(screenLocation[0] - cardOffset.x - overlayLocation[0],
                screenLocation[1] - cardOffset.y - overlayLocation[1])
            canvas.clipRect(cardVisible)
            message.getLocationOnScreen(screenLocation)
            x = screenLocation[0] - overlayLocation[0] + message.compoundPaddingLeft +
                line.getLineRight(0) + 12f * scale + size / 2f
            // baseline includes TextView's vertical-gravity offset. Padding
            // alone leaves the face above a centered, reserved text slot.
            y = screenLocation[1] - overlayLocation[1] + message.baseline - line.getLineBaseline(0) +
                (line.getLineTop(0) + line.getLineBottom(0)) / 2f
        } else {
            val cutout = circle.centerX() * 2f
            x = cutout + (width - cutout) / 2f
            y = height * .50f
        }
        fun Drawable.drawIcon() {
            alpha=(iconAlpha*255).toInt().coerceIn(0,255)
            val half = size * iconScale / 2f
            setBounds((x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt())
            draw(canvas)
        }
        if (success && successIcon != null) successIcon.drawIcon() else { ring?.drawIcon(); face?.drawIcon() }
        canvas.restore()
    }

    override fun verifyDrawable(who: Drawable) = who === successIcon || super.verifyDrawable(who)
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (!retiring && ValueAnimator.areAnimatorsEnabled()) scan() }
    override fun onDetachedFromWindow() {
        trackAnchor(null)
        cancelMotion()
        (successIcon as? android.graphics.drawable.Animatable)?.stop(); successIcon?.callback = null
        super.onDetachedFromWindow()
    }
}
