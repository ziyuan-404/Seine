package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.graphics.Rect
import android.graphics.Matrix
import android.os.SystemClock
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.LinearInterpolator
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.RearMotionCurves
import kotlin.math.roundToInt

/** Reference clip: icon at 1.6 s, text at 2.7 s, alignment at 4 s, face at 4.9 s.
 * Own child transforms only; the OEM pager, clock and privacy decision stay native. */
internal object RearMessageEntryMotion {
    private const val START_DELAY_MS = 220L
    private const val DURATION_MS = 3000L
    const val POPUP_ENTRANCE_MS = START_DELAY_MS + DURATION_MS
    private data class Stamp(val version: String, val redacted: Boolean, var start: Long = 0L,
        var owner: java.lang.ref.WeakReference<View>? = null)
    private val presented = object : LinkedHashMap<String, Stamp>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Stamp>?) = size > 256
    }
    private val active = mutableMapOf<View, Entry>()
    private val ease = RearMotionCurves.content

    fun version(sbn: android.service.notification.StatusBarNotification) =
        "${sbn.key}:${sbn.postTime}"
    fun ready(root: View) = root !in active

    fun bind(root: ViewGroup, native: ViewGroup, version: String, redacted: Boolean, popup: Boolean = false) {
        active.remove(root)?.finish()
        // The entrance belongs only to a heads-up notification. Persistent
        // cards are already part of the native pager and stay in their slots.
        if (!popup) return
        if (!ValueAnimator.areAnimatorsEnabled()) return
        val previous = presented[version]
        // Revealing details is a content replacement. A popup and a pager page
        // consume the same message entrance, even when they have different roots.
        if (previous != null && (previous.redacted != redacted || previous.owner?.get() !== root ||
                SystemClock.uptimeMillis() - previous.start > DURATION_MS)) return
        Entry(root, native, previous ?: Stamp(version, redacted), popup).also {
            active[root] = it; it.waitForVisible()
        }
    }

    fun finish(root: View) { active.remove(root)?.finish() }
    fun pause(root: View) {
        val entry = active.remove(root) ?: return
        entry.finish()
        if (entry.stamp.start == 0L) bind(entry.root, entry.native, entry.stamp.version, entry.stamp.redacted, entry.popup)
    }
    fun finishAll() { active.values.toList().forEach { it.finish() }; active.clear(); presented.clear() }

    /** Same child arrangement for the stock heads-up panel and persistent card. */
    fun prepare(native: ViewGroup, redacted: Boolean, persistent: Boolean = false) {
        fun id(name: String) = native.resources.getIdentifier(name, "id", "com.xiaomi.subscreencenter")
        val container = native.findViewById<android.widget.LinearLayout>(id("notification_container")) ?: return
        val icon = native.findViewById<android.widget.ImageView>(id("notification_icon")) ?: return
        val frame = icon.parent as View
        val heading = native.findViewById<TextView>(id("notification_title"))
        val message = native.findViewById<TextView>(id("notification_content"))
        val badge = native.findViewById<android.widget.ImageView>(id("notification_small_icon"))
        fun dp(value: Int) = (value * native.resources.displayMetrics.density).toInt()
        container.removeAllViews()
        container.clipChildren = false
        (frame as? ViewGroup)?.clipChildren = false
        icon.layoutParams = android.widget.FrameLayout.LayoutParams(dp(48), dp(48), android.view.Gravity.START)
        badge.layoutParams = android.widget.FrameLayout.LayoutParams(dp(16), dp(16), android.view.Gravity.START).apply {
            // The old 34 + 16 dp extended outside the 48 dp icon frame and
            // lost the bottom/right edge in the stock clipped container.
            topMargin = dp(32); marginStart = dp(32)
        }
        heading.apply {
            gravity = android.view.Gravity.START; maxLines = 1; setTextColor(0x99ffffff.toInt())
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.create("sans-serif", 0), 400, false)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(14).toFloat()); includeFontPadding = false
        }
        message.apply {
            tag = "rear_message_privacy_label"
            gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            maxLines = if (persistent) 2 else if (redacted) 1 else 2
            minLines = 1
            setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.create("sans-serif", 0), 500, false)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, dp(20).toFloat())
            includeFontPadding = !persistent; isFallbackLineSpacing = true
            setPadding(0, 0, if (redacted) dp(28) else 0, if (persistent) 0 else dp(2))
            setLineHeight(dp(if (persistent) 24 else 26))
        }
        if (persistent) {
            heading.setLineSpacing(0f, 1f)
            heading.setLineHeight(dp(18))
            container.addView(frame, android.widget.LinearLayout.LayoutParams(-1, dp(48)))
            val labels = android.widget.LinearLayout(native.context).apply {
                tag = "rear_message_labels"
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            labels.addView(message, android.widget.LinearLayout.LayoutParams(-1, -2))
            labels.addView(heading, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
            // A compact title/sender pair stays together. Its slot supports
            // two-line messages without remeasuring or moving the icon.
            container.addView(labels, android.widget.LinearLayout.LayoutParams(-1, dp(70)).apply { topMargin = dp(4) })
        } else {
            container.addView(frame, android.widget.LinearLayout.LayoutParams(-1, dp(52)))
            container.addView(message, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            container.addView(heading, android.widget.LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        container.layoutParams = (container.layoutParams as android.widget.RelativeLayout.LayoutParams).apply {
            removeRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
            addRule(android.widget.RelativeLayout.CENTER_VERTICAL); topMargin = 0
        }
    }

    /** Center the complete content stack inside the OEM clock/navigation bounds. */
    fun anchorFooter(card: ViewGroup, footer: View) {
        card.doOnPreDraw {
            if (footer.visibility != View.VISIBLE) return@doOnPreDraw
            val toggle = footer.findViewWithTag<android.widget.TextSwitcher>("rear_notification_group_toggle")
            val label = toggle?.currentView as? TextView ?: footer.findViewWithTag<TextView>("rear_notification_quick_open")
                ?: return@doOnPreDraw
            val transform = Matrix()
            label.transformMatrixToGlobal(transform); card.transformMatrixToLocal(transform)
            val point = floatArrayOf(0f, 0f); transform.mapPoints(point)
            val metrics = label.paint.fontMetrics
            val current = point[1] + label.baseline + (metrics.ascent + metrics.descent) / 2f
            val screen = android.graphics.Point().also { card.display?.getRealSize(it) }
            val target = NativeGroupToggle.hintCenter(screen.y.toFloat(), card.resources.displayMetrics.density)
            footer.translationY += target - current
        }
    }

    fun centerCard(card: ViewGroup, native: ViewGroup, footer: View) {
        card.doOnPreDraw {
            val stack = native.parent?.parent as? View ?: return@doOnPreDraw
            fun id(name: String) = native.resources.getIdentifier(name, "id", "com.xiaomi.subscreencenter")
            val icon = native.findViewById<View>(id("notification_icon")) ?: return@doOnPreDraw
            val group = footer.findViewWithTag<android.widget.TextSwitcher>("rear_notification_group_toggle")
            val labels = native.findViewWithTag<View>("rear_message_labels") ?: return@doOnPreDraw
            if (icon.height <= 0 || labels.height <= 0) return@doOnPreDraw
            fun localY(view: View): Float {
                val transform = Matrix()
                view.transformMatrixToGlobal(transform)
                card.transformMatrixToLocal(transform)
                val point = floatArrayOf(0f, 0f)
                transform.mapPoints(point)
                return point[1]
            }
            val top = localY(icon)
            val bottom = localY(labels) + labels.height
            val screen = android.graphics.Point()
            card.display?.getRealSize(screen)
            // The native widget omits the bottom-bar area from its height.
            // Centering in that smaller height leaves the complete stack high.
            val visualHeight = screen.y.takeIf { it > 0 }?.toFloat() ?: card.height.toFloat()
            // Reserve the lower part for the group entry, rather than letting
            // its measurement move the icon when privacy content is revealed.
            var offset = visualHeight * .46f - (top + bottom) / 2f
            val clock = card.rootView.findViewById<View>(id("time_parent"))
            // Clock and neighbor cards belong to different pager transforms.
            // Its layout top is stable even while a neighboring card is offscreen.
            val clockTop = if (clock != null && clock.height > 0) clock.top.toFloat() else card.height * .094f
            val gap = 4f * native.resources.displayMetrics.density
            offset = maxOf(offset, clockTop + gap - top)
            stack.translationY = (stack.translationY + offset).roundToInt().toFloat()
            val groupLabel = group?.currentView as? TextView
                ?: footer.findViewWithTag<TextView>("rear_notification_quick_open")
            if (footer.visibility == View.VISIBLE && groupLabel != null) {
                val metrics = groupLabel.paint.fontMetrics
                // Reuse the ordinary card's existing footer slot. Reading the
                // bar through an offscreen page's transform caches a different
                // position for each preloaded page in the native pager.
                val targetCenter = NativeGroupToggle.hintCenter(visualHeight, native.resources.displayMetrics.density)
                val currentCenter = localY(groupLabel) + groupLabel.baseline + (metrics.ascent + metrics.descent) / 2f
                // Move the actual footer, including its hit region, while
                // preserving the existing TextSwitcher and expansion animation.
                footer.translationY = (footer.translationY + targetCenter - currentCenter).roundToInt().toFloat()
                (stack as? ViewGroup)?.clipChildren = false
            } else footer.translationY = 0f
        }
    }

    private class Entry(val root: ViewGroup, val native: ViewGroup, var stamp: Stamp, val popup: Boolean) : ViewTreeObserver.OnPreDrawListener,
        View.OnAttachStateChangeListener {
        private var observer: ViewTreeObserver? = null
        private var animator: ValueAnimator? = null
        private var frame: View? = null
        private var icon: View? = null
        private var labels = emptyList<TextView>()
        private var offsets = emptyList<Float>()
        private var dx = 0f
        private var dy = 0f
        private var finished = false
        private var spring: TimeInterpolator = RearMotionCurves.enter
        private var growSpring: TimeInterpolator = RearMotionCurves.enter

        fun waitForVisible() {
            root.addOnAttachStateChangeListener(this)
            observer = root.viewTreeObserver.also { it.addOnPreDrawListener(this) }
        }

        override fun onPreDraw(): Boolean {
            if (finished || !root.isAttachedToWindow) return true
            if (!root.isShown || root.alpha < .9f || root.display?.state != Display.STATE_ON) return true
            if (!popup && !NativeRearCardHost.isSelected(root)) return true
            val visible = Rect()
            if (!root.getGlobalVisibleRect(visible) || visible.height() < root.height * .9f || visible.width() < root.width * .9f) return true
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
            fun find(name: String) = native.findViewById<View>(native.resources.getIdentifier(name, "id", "com.xiaomi.subscreencenter"))
            icon = find("notification_icon")
            frame = icon?.parent as? View
            val quickOpen = native.findViewWithTag<TextView>(RearPopupReveal.QUICK_OPEN_TAG)
            labels = listOfNotNull(find("notification_content") as? TextView, find("notification_title") as? TextView, quickOpen)
            val image = icon ?: run { finish(); return true }
            val imageFrame = frame ?: run { finish(); return true }
            val camera = find("camera_place_holder")?.width ?: 0
            val centerX = (camera + root.width) / 2f
            val centerY = native.height / 2f
            fun bounds(v: View) = Rect(0, 0, v.width, v.height).also { root.offsetDescendantRectToMyCoords(v, it) }
            val imageBounds = bounds(image)
            dx = centerX - imageBounds.exactCenterX()
            dy = centerY - imageBounds.exactCenterY()
            imageFrame.pivotX = image.left + image.width / 2f
            imageFrame.pivotY = image.top + image.height / 2f
            offsets = labels.map { label ->
                if (label === quickOpen) return@map 0f
                val textWidth = label.layout?.let { layout ->
                    (0 until minOf(layout.lineCount, label.maxLines)).maxOfOrNull { layout.getLineWidth(it) }
                } ?: label.width.toFloat()
                centerX - (bounds(label).left + label.compoundPaddingLeft + textWidth / 2f)
            }
            // Measured reference glyphs peak at 40 px before settling at 37 px;
            // the upward move overshoots its resting baseline by about 4 px.
            growSpring = RearMotionCurves.spring(native.context, .76f, .65f, 900L)
            spring = RearMotionCurves.spring(native.context, .84f, .84f, 1000L)
            if (stamp.start == 0L) {
                if (presented.containsKey(stamp.version)) { finish(); return true }
                stamp.start = SystemClock.uptimeMillis() + START_DELAY_MS
                stamp.owner = java.lang.ref.WeakReference(root)
                presented[stamp.version] = stamp
            }
            YLog.debug("[RearMessageMotion] start popup=$popup elapsed=${SystemClock.uptimeMillis() - stamp.start}")
            apply(SystemClock.uptimeMillis() - stamp.start)
            animator = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = (DURATION_MS - (SystemClock.uptimeMillis() - stamp.start)).coerceAtLeast(1L)
                interpolator = LinearInterpolator()
                addUpdateListener { apply(SystemClock.uptimeMillis() - stamp.start) }
                addListener(object : AnimatorListenerAdapter() { override fun onAnimationEnd(animation: Animator) { finish() } })
                start()
            }
            return true
        }

        private fun phase(ms: Long, start: Float, duration: Float) = ((ms - start) / duration).coerceIn(0f, 1f)
        private fun apply(elapsed: Long) {
            val imageFrame = frame ?: return
            val ms = elapsed * 3300L / DURATION_MS
            val grow = growSpring.getInterpolation(phase(ms, 0f, 900f))
            val rise = spring.getInterpolation(phase(ms, 1040f, 1000f))
            val align = ease.getInterpolation(phase(ms, 2380f, 520f))
            imageFrame.alpha = ease.getInterpolation(phase(ms, 0f, 320f))
            imageFrame.scaleX = .55f + .45f * grow; imageFrame.scaleY = imageFrame.scaleX
            imageFrame.translationY = dy * (1f - rise)
            imageFrame.translationX = dx * (1f - align)
            labels.forEachIndexed { index, label ->
                val p = spring.getInterpolation(phase(ms, 1100f + index * 40f, 1000f))
                label.alpha = ease.getInterpolation(phase(ms, 1100f + index * 40f, 300f))
                label.translationY = 66f * (1f - p) * root.height / 596f
                label.translationX = offsets[index] * (1f - align)
            }
        }

        fun finish() {
            if (finished) return
            finished = true
            observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this); observer = null
            animator?.removeAllListeners(); animator?.cancel(); animator = null
            frame?.let { it.alpha = 1f; it.scaleX = 1f; it.scaleY = 1f; it.translationX = 0f; it.translationY = 0f }
            labels.forEach { it.alpha = 1f; it.translationX = 0f; it.translationY = 0f }
            root.removeOnAttachStateChangeListener(this)
            if (active[root] === this) active.remove(root)
            root.invalidate()
        }
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) {
            finish()
        }
    }
}
