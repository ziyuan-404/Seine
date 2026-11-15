package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import hk.uwu.reareye.hook.support.YLog
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/** The ROM card owns spring frames and geometry. The bridge carries its current
 * state across interruptions and passes the factory gesture velocity to Folme. */
internal class NativeRearCardAnimation(source: View) {
    companion object {
        var panelClass: Class<*>? = null
        var preparePicker: (() -> Unit)? = null
        var animatePickerList: ((Boolean) -> Unit)? = null
        private val live = mutableSetOf<NativeRearCardAnimation>()
        private val byCard = java.util.IdentityHashMap<View, NativeRearCardAnimation>()
        private val byPanel = java.util.IdentityHashMap<Any, NativeRearCardAnimation>()
        val isolatedPanels = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        private var closingSetup: NativeRearCardAnimation? = null
        private var gestureSpeed = 0f
        private var gestureTime = 0L
        private var closeReceipts = 10
        fun recordHomeVelocity(pixelsPerSecond: Float) {
            gestureSpeed = pixelsPerSecond.coerceAtLeast(0f)
            gestureTime = android.os.SystemClock.uptimeMillis()
        }
        fun beforeStockCardProgress(card: View) { byCard[card]?.applyClosingStart() }
        fun configureStockSpring(arguments: Array<*>) {
            closingSetup?.configureSpring(arguments)
        }
        fun closeAll() { live.toList().forEach { it.dispose() } }
        fun onStockCardProgress(card: View) { byCard[card]?.emit() }
        fun onStockPanelProgress(panel: Any) { byPanel[panel]?.emit() }
    }

    private val source = WeakReference(source)
    private val root = WeakReference(source.rootView as ViewGroup)
    private val context = source.context.applicationContext.createDisplayContext(source.display)
    private val panelType = requireNotNull(panelClass)
    private val panel = panelType.getConstructor(android.content.Context::class.java).newInstance(context)
    private val card = panelType.getMethod("getCardExpandContainer").invoke(panel) as ViewGroup
    private val type = card.javaClass
    private fun field(name: String) = type.getDeclaredField(name).apply { isAccessible = true }
    private val clip = field("c").get(card) as RectF
    private val radius = field("d")
    private val nativeSourceRadius = field("h")
    private val nativeExpandedRadius = field("i")
    private val nativeClosing = field("l")
    private val startClip = field("o").get(card) as RectF
    private val startRadius = field("q")
    private val expanded = field("y")
    private val animating = panelType.getDeclaredField("p").apply { isAccessible = true }
    private val wrapper = panelType.getDeclaredField("k").apply { isAccessible = true }
    private val callbackType = context.classLoader.loadClass("U1.j")
    private val open = type.getMethod("c", View::class.java, callbackType)
    private val reset = type.getMethod("d")
    private val close = panelType.getMethod("s", Boolean::class.javaPrimitiveType)
    private val mark = panelType.getMethod("z", String::class.java, Boolean::class.javaPrimitiveType)
    private val child = View(context)
    private val main = Handler(Looper.getMainLooper())
    private val clipping = WeakHashMap<ViewGroup, Boolean>()
    private var callback: ((FloatArray, Boolean) -> Unit)? = null
    private var opening = true
    private var area = Rect()
    private var ready = false
    private var disposed = false
    private var replacing = false
    private data class OpeningFrame(val clip: RectF, val radius: Float, val alpha: Float)
    private var pausedOpening: OpeningFrame? = null
    private var closingStart: OpeningFrame? = null
    private var closingAlpha = 1f
    private var closingSpeed = 0f
    val isClosing: Boolean get() = !opening && callback != null && !disposed
    fun pauseOpening() {
        if (opening && !disposed && callback != null && pausedOpening == null) {
            pausedOpening = OpeningFrame(RectF(clip), radius.getFloat(card), card.alpha)
            replacing = true
            try { clean(card) } finally { replacing = false }
        }
    }
    private var last = floatArrayOf()
    private val timeout = Runnable { finish() }
    private val detached = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) { dispose() }
    }

    init {
        // The isolated panel is only an owner for the ROM's actual callbacks.
        // It must never subscribe as a second launcher to AOD changes.
        val listener = panelType.getDeclaredField("t").apply { isAccessible = true }.get(panel)
        context.applicationContext.javaClass.getMethod("g", listener.javaClass).invoke(context.applicationContext, listener)
        isolatedPanels.add(panel)
        byCard[card] = this
        byPanel[panel] = this
        (card.parent as ViewGroup).removeView(card)
        card.addView(child, ViewGroup.LayoutParams(-1, -1))
        source.addOnAttachStateChangeListener(detached)
        live.add(this)
    }

    fun animate(opening: Boolean, bounds: Rect, origin: RearLaunchOrigin, taskRadius: Float?,
                callback: (FloatArray, Boolean) -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        check(!disposed)
        val target = requireNotNull(source.get()).also { check(it.isAttachedToWindow) }
        val root = requireNotNull(root.get())
        val continuation = if (!opening && this.opening && this.callback != null)
            pausedOpening ?: OpeningFrame(RectF(clip), radius.getFloat(card), card.alpha) else null
        continuation?.clip?.offset((area.left - bounds.left).toFloat(), (area.top - bounds.top).toFloat())
        pausedOpening = null
        if (this.callback != null) {
            replacing = true
            try {
                clean(card)
                finish(release = false)
            } finally { replacing = false }
            ready = continuation != null
        }
        if (!opening && origin.kind == RearLaunchOrigin.PICKER) preparePicker?.invoke()
        this.opening = opening; area.set(bounds); last = floatArrayOf()
        closingAlpha = continuation?.alpha ?: 1f
        closingSpeed = if (android.os.SystemClock.uptimeMillis() - gestureTime < 1000L)
            (gestureSpeed / bounds.height().coerceAtLeast(1)).coerceIn(0f, 8f) else 0f
        if (!opening && closeReceipts-- > 0)
            YLog.info("OEM rear close: interrupted=${continuation != null} speed=$closingSpeed clip=${continuation?.clip}")
        val picker = origin.kind == RearLaunchOrigin.PICKER
        // When rebuilding a card for the return, do not publish the temporary
        // opening frames produced by the native open-and-cancel setup.
        if (opening || !picker) this.callback = callback
        if (picker) {
            nativeSourceRadius.setFloat(card, origin.radius.coerceIn(0f, minOf(target.width, target.height) / 2f))
            taskRadius?.let { nativeExpandedRadius.setFloat(card, it) }
        }
        var parent = target.parent as? ViewGroup
        while (parent != null && parent !== root) {
            clipping.putIfAbsent(parent, parent.clipChildren); parent.clipChildren = false
            parent = parent.parent as? ViewGroup
        }
        if (!card.isAttachedToWindow) root.overlay.add(card)
        card.measure(View.MeasureSpec.makeMeasureSpec(bounds.width(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(bounds.height(), View.MeasureSpec.EXACTLY))
        card.layout(bounds.left, bounds.top, bounds.right, bounds.bottom)
        child.layout(0, 0, bounds.width(), bounds.height())
        if (opening || !ready) {
            // The isolated card uses the app source size and task corner radii.
            // c() still owns its original state capture and open animation.
            field("f").setFloat(card, target.width.toFloat())
            field("g").setFloat(card, target.height.toFloat())
            mark.invoke(panel, "externalCard", true)
            open.invoke(card, target, callbackType.getConstructor(panelType, Int::class.javaPrimitiveType).newInstance(panel, 1))
            if (opening && origin.kind == RearLaunchOrigin.PICKER) animatePickerList?.invoke(true)
            ready = true
            if (!opening) {
                clean(card)
                // Native cancellation settles the opening at its full state.
                // Closing a notification into a newly mounted picker uses that
                // same full-state initialization without rendering another open.
            }
        }
        if (!opening) {
            val nativeWrapper = context.classLoader.loadClass("K0.d").getConstructor(Any::class.java).newInstance(null)
            wrapper.set(panel, nativeWrapper)
            closingStart = continuation
            closingSetup = this
            try {
                close.invoke(panel, true)
                if (origin.kind == RearLaunchOrigin.PICKER) animatePickerList?.invoke(false)
            } finally { closingSetup = null }
        }
        if (!opening && picker) this.callback = callback
        main.postDelayed(timeout, 3000L)
        emit()
    }

    private fun applyClosingStart() {
        val frame = closingStart ?: return
        if (!nativeClosing.getBoolean(card)) return
        startClip.set(frame.clip)
        startRadius.setFloat(card, frame.radius)
        closingStart = null
    }

    private fun configureSpring(arguments: Array<*>) {
        if (closingSpeed <= 0f) return
        val config = arguments.firstOrNull { it?.javaClass?.name == "miuix.animation.base.AnimConfig" } ?: return
        val strength = (closingSpeed / 8f).coerceIn(0f, 1f)
        val ease = context.classLoader.loadClass("miuix.animation.FolmeEase")
            .getMethod("spring", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .invoke(null, .78f - .20f * strength, .32f - .06f * strength)
        config.javaClass.getField("ease").set(config, ease)
        // The property measures normalized card progress or list scale.
        val progressProperty = field("z").get(card)
        val speed = if (arguments.firstOrNull() === progressProperty)
            closingSpeed.coerceAtMost(4f) else (closingSpeed * .12f).coerceAtMost(.9f)
        config.javaClass.getMethod("setFromSpeed", Float::class.javaPrimitiveType).invoke(config, speed)
    }

    private fun emit() {
        if (disposed || replacing) return
        val callback = callback ?: return
        val done = if (opening) !animating.getBoolean(panel) else !expanded.getBoolean(card)
        val terminal = if (done) 1f else 0f
        val frame = if (!opening && done) floatArrayOf(area.left.toFloat(), area.top.toFloat(), 1f,
            area.left.toFloat(), area.top.toFloat(), area.right.toFloat(), area.bottom.toFloat(),
            0f, 0f, 1f)
        else floatArrayOf(area.left + child.translationX, area.top + child.translationY, child.scaleX,
            area.left + clip.left, area.top + clip.top, area.left + clip.right, area.top + clip.bottom,
            radius.getFloat(card), card.alpha * (if (opening) 1f else closingAlpha), terminal)
        if (done || !frame.contentEquals(last)) {
            last = frame
            callback(frame, done)
        }
        if (done) finish(notify = false)
    }

    private fun finish(notify: Boolean = true, release: Boolean = true) {
        main.removeCallbacks(timeout)
        val callback = callback; this.callback = null
        clipping.forEach { (view, value) -> view.clipChildren = value }; clipping.clear()
        if (notify && callback != null) callback(floatArrayOf(
            area.left.toFloat(), area.top.toFloat(), 1f, area.left.toFloat(), area.top.toFloat(),
            area.right.toFloat(), area.bottom.toFloat(), radius.getFloat(card),
            if (opening) 1f else 0f, 1f), true)
        if (!opening && release) dispose()
    }

    fun dispose() {
        if (disposed) return
        disposed = true
        finish()
        runCatching { clean(card); reset.invoke(card) }
        root.get()?.overlay?.remove(card)
        source.get()?.removeOnAttachStateChangeListener(detached)
        byCard.remove(card); byPanel.remove(panel)
        isolatedPanels.remove(panel); live.remove(this)
    }

    private fun clean(view: View) {
        context.classLoader.loadClass("miuix.animation.Folme").getMethod("clean", View::class.java).invoke(null, view)
    }
}
