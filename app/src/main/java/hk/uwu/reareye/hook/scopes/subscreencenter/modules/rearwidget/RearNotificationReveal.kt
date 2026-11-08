package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import hk.uwu.reareye.hook.scopes.subscreencenter.modules.RearMotionCurves
import hk.uwu.reareye.hook.support.YLog
import java.util.WeakHashMap

/** Only the redacted placeholder may be retained; private content is never snapshotted. */
internal object RearNotificationReveal {
    private data class Binding(val source: String, val version: String, val hidden: Boolean, val message: Boolean)
    private val bindings = WeakHashMap<ViewGroup, Binding>()
    private val running = mutableMapOf<ViewGroup, () -> Unit>()

    fun forget(host: ViewGroup) {
        running.remove(host)?.invoke()
        bindings.remove(host)
    }

    fun replace(host: ViewGroup, content: View, hidden: Boolean, footer: View,
        source: String, version: String, message: Boolean, retainMessageContent: Boolean = false): View {
        val binding = Binding(source, version, hidden, message)
        val old = bindings[host]
        val previous = host.getChildAt(0)
        // Ranking/RemoteViews updates may bind the very same data again during
        // the reveal. Keep the current views and the running transition intact.
        if (old == binding && previous != null) return previous
        running.remove(host)?.invoke()
        val reveal = old?.source == source && old.hidden && !hidden && previous != null &&
            host.isAttachedToWindow && host.isShown && host.alpha > .1f &&
            ValueAnimator.areAnimatorsEnabled()
        bindings[host] = binding

        if (old?.source == source && old.message && message && previous is ViewGroup) {
            if (retainMessageContent) return previous
            // Reuse the mounted message root, especially its icon/frame. Text
            // updates never remeasure or fade the icon and never move the card.
            fun id(name: String) = content.resources.getIdentifier(name, "id", "com.xiaomi.subscreencenter")
            val labels = listOf("notification_content", "notification_title").mapNotNull { name ->
                val before = previous.findViewById<TextView>(id(name)) ?: return@mapNotNull null
                val after = content.findViewById<TextView>(id(name)) ?: return@mapNotNull null
                before to after
            }
            val ghosts = if (reveal) labels.mapNotNull { (label, _) ->
                snapshot(label, previous)?.also { previous.overlay.add(it) }
            } else emptyList()
            labels.forEach { (before, after) ->
                before.text = after.text
                before.setPadding(after.paddingLeft, after.paddingTop, after.paddingRight, after.paddingBottom)
            }
            listOf("notification_icon", "notification_small_icon").forEach { name ->
                val before = previous.findViewById<ImageView>(id(name))
                val after = content.findViewById<ImageView>(id(name))
                if (before != null && after != null) {
                    before.setImageDrawable(after.drawable)
                    before.visibility = after.visibility
                    before.outlineProvider = after.outlineProvider
                    before.clipToOutline = after.clipToOutline
                }
            }
            if (ghosts.isNotEmpty()) animate(host, previous, labels.map { it.first }, ghosts, footer, fadeFooter = false)
            return previous
        }
        val ghost = if (reveal) snapshot(previous!!, host) else null
        host.removeAllViews()
        host.addView(content)
        if (ghost != null) {
            host.overlay.add(ghost)
            animate(host, host, listOf(content), listOf(ghost), footer)
        }
        return content
    }

    private fun snapshot(view: View, overlayHost: ViewGroup): ImageView? {
        if (view.width <= 0 || view.height <= 0) return null
        return runCatching {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val bounds = Rect(0, 0, view.width, view.height)
            overlayHost.offsetDescendantRectToMyCoords(view, bounds)
            ImageView(overlayHost.context).apply {
                setImageBitmap(bitmap)
                layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
            }
        }.getOrNull()
    }

    private fun animate(host: ViewGroup, overlayHost: ViewGroup, targets: List<View>, ghosts: List<ImageView>, footer: View?,
        fadeFooter: Boolean = true) {
        targets.forEach { it.alpha = 0f }
        if (fadeFooter) footer?.alpha = 0f
        var animator: ValueAnimator? = null
        var finished = false
        lateinit var detached: View.OnAttachStateChangeListener
        val finish: () -> Unit = {
            if (!finished) {
                finished = true
                animator?.removeAllListeners(); animator?.cancel()
                targets.forEach { it.alpha = 1f }; footer?.alpha = 1f
                ghosts.forEach { overlayHost.overlay.remove(it); it.setImageDrawable(null) }
                // RenderThread may still hold the last draw; let Bitmap lifetime
                // follow its drawable rather than recycling it under that frame.
                host.removeOnAttachStateChangeListener(detached)
                running.remove(host)
            }
        }
        detached = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) { finish() }
        }
        running[host] = finish
        host.addOnAttachStateChangeListener(detached)
        host.postOnAnimation {
            if (!finished) {
                animator = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 520L; interpolator = RearMotionCurves.content
                    addUpdateListener {
                        val t = it.animatedValue as Float
                        ghosts.forEach { ghost -> ghost.alpha = (1f - t / .55f).coerceIn(0f, 1f) }
                        targets.forEach { target -> target.alpha = ((t - .22f) / .78f).coerceIn(0f, 1f) }
                        if (fadeFooter) footer?.alpha = ((t - .2f) / .8f).coerceIn(0f, 1f)
                    }
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) { finish() }
                    })
                    start()
                }
                YLog.debug("[RearNotificationReveal] started labels=${targets.size} duration=520")
            }
        }
    }

    fun clear() {
        running.values.toList().forEach { it() }
        bindings.clear()
    }
}
