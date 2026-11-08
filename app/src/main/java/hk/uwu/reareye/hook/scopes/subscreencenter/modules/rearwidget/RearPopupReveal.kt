package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.animation.ValueAnimator
import android.app.Notification
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.core.view.doOnPreDraw
import androidx.core.view.doOnLayout
import hk.uwu.reareye.R
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.utils.NativeNotificationPrivacy
import java.util.WeakHashMap
import kotlin.math.floor
import kotlin.math.roundToInt

/** Shared privacy-card reveal, sampled from every reference frame at 30 fps. */
internal object RearPopupReveal {
    const val QUICK_OPEN_TAG = "rear_notification_quick_open"
    const val POPUP_REVEAL_MS = 650L
    private data class Binding(val source: String, val hidden: Boolean)
    private val bindings = WeakHashMap<ViewGroup, Binding>()
    private val identities = WeakHashMap<ViewGroup, String>()
    private val running = mutableMapOf<ViewGroup, () -> Unit>()
    private val originalLayouts = WeakHashMap<ViewGroup, () -> Unit>()
    // The stock manager clears the avatar when it redacts the entry. Keep its
    // original bitmap by immutable identity, never by the mutable entry hash.
    private val avatars = object : LinkedHashMap<String, Bitmap>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 16
    }

    fun capture(entry: Any) {
        fun field(name: String) = entry.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(entry)
        val bitmap = (field("d") as? Bitmap)?.takeIf(RearNotificationImages::hasPixels) ?: return
        val key = "${field("f")}:${field("g")}:${field("h")}"
        synchronized(avatars) { avatars[key] = bitmap }
    }

    private fun identity(entry: Any): String {
        fun field(name: String) = entry.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(entry)
        return "${field("f")}:${field("g")}:${field("h")}"
    }
    fun owns(host: ViewGroup, entry: Any) = identities[host] == identity(entry)

    private fun id(host: View, name: String) = host.resources.getIdentifier(name, "id", "com.xiaomi.subscreencenter")
    private fun bounds(host: ViewGroup, view: View) = Rect(0, 0, view.width, view.height).also {
        host.offsetDescendantRectToMyCoords(view, it)
    }

    fun usesDownloadLayout(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        return notification.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
            notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false) ||
            notification.category == Notification.CATEGORY_PROGRESS ||
            notification.channelId.orEmpty().contains("download", ignoreCase = true) ||
            sbn.packageName == "com.android.providers.downloads"
    }

    fun bind(host: ViewGroup, sbn: StatusBarNotification, hidden: Boolean, appName: String, entry: Any?,
             footer: View? = null, quickOpen: TextView? = null) {
        val source = RearMessageEntryMotion.version(sbn)
        if (entry == null && bindings[host] == Binding(source, hidden)) return
        running.remove(host)?.invoke()
        val reveal = bindings[host] == Binding(source, true) && !hidden && host.isShown &&
            host.isAttachedToWindow && (entry != null || NativeRearCardHost.isSelected(host)) &&
            ValueAnimator.areAnimatorsEnabled()
        val icon = host.findViewById<ImageView>(id(host, "notification_icon")) ?: return
        val badge = host.findViewById<ImageView>(id(host, "notification_small_icon")) ?: return
        val frame = icon.parent as FrameLayout
        val container = host.findViewById<LinearLayout>(id(host, "notification_container")) ?: return
        val body = host.findViewById<TextView>(id(host, "notification_content")) ?: return
        val sender = host.findViewById<TextView>(id(host, "notification_title")) ?: return
        if (entry != null && !originalLayouts.containsKey(host)) {
            removeQuickOpen(host)
            fun copy(params: ViewGroup.LayoutParams): ViewGroup.LayoutParams = when (params) {
                is RelativeLayout.LayoutParams -> RelativeLayout.LayoutParams(params)
                is FrameLayout.LayoutParams -> FrameLayout.LayoutParams(params)
                is LinearLayout.LayoutParams -> LinearLayout.LayoutParams(params)
                else -> ViewGroup.LayoutParams(params)
            }
            val children = (0 until container.childCount).map { container.getChildAt(it) to copy(container.getChildAt(it).layoutParams) }
            val containerParams = copy(container.layoutParams)
            val iconParams = copy(icon.layoutParams); val badgeParams = copy(badge.layoutParams)
            val outline = icon.outlineProvider
            fun labelStyle(label: TextView): () -> Unit {
                val gravity = label.gravity; val max = label.maxLines; val min = label.minLines
                val padding = intArrayOf(label.paddingLeft,label.paddingTop,label.paddingRight,label.paddingBottom)
                val size = label.textSize; val colors = label.textColors; val face = label.typeface
                val fontPadding = label.includeFontPadding; val lineHeight = label.lineHeight
                return {
                    label.gravity=gravity;label.maxLines=max;label.minLines=min;label.includeFontPadding=fontPadding
                    label.setTextSize(TypedValue.COMPLEX_UNIT_PX,size);label.setTextColor(colors);label.typeface=face
                    label.setLineHeight(lineHeight);label.setPadding(padding[0],padding[1],padding[2],padding[3])
                }
            }
            val restoreBody=labelStyle(body);val restoreSender=labelStyle(sender)
            originalLayouts[host] = {
                container.removeAllViews()
                children.forEach { (child, params) -> container.addView(child,params) }
                container.layoutParams=containerParams;icon.layoutParams=iconParams;badge.layoutParams=badgeParams
                icon.outlineProvider=outline;body.tag=null
                restoreBody();restoreSender()
            }
        }
        val oldFrame = if (reveal) bounds(host, frame) else Rect()
        val oldIcon = Rect(icon.left, icon.top, icon.right, icon.bottom)
        val ghosts = if (reveal) listOfNotNull(snapshot(body, host), snapshot(sender, host)) else emptyList()
        val oldApp = icon.drawable
        val appBitmap = entry?.let { it.javaClass.getDeclaredField("b").apply { isAccessible = true }.get(it) as? Bitmap }
        val appIcon = appBitmap?.let { bitmap ->
            val drawable = BitmapDrawable(host.resources, bitmap)
            runCatching { host.context.packageManager.getUserBadgedIcon(drawable, sbn.user) }.getOrDefault(drawable)
        } ?: runCatching { host.context.packageManager.getApplicationIcon(sbn.packageName) }.getOrNull() ?: oldApp
        val avatarKey = "${sbn.packageName}:${sbn.user.hashCode()}:${sbn.postTime}"
        val avatar = if (hidden) null else (synchronized(avatars) { avatars[avatarKey] }?.let {
            BitmapDrawable(host.resources, it)
        } ?: RearNotificationImages.avatar(sbn.notification, host.context))
        val hasAvatar = avatar != null
        icon.setImageDrawable(avatar ?: appIcon)
        badge.setImageDrawable(if (hasAvatar) appIcon else null)
        badge.visibility = if (hasAvatar) View.VISIBLE else View.GONE
        icon.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, view.width * .24f)
            }
        }
        icon.clipToOutline = hasAvatar
        val download = !hidden && usesDownloadLayout(sbn)
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)
        val text = sbn.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""
        body.text = if (hidden) NativeNotificationPrivacy.placeholder(host.context) else
            if (download) title?.takeUnless { it.isBlank() } ?: appName else text
        sender.text = if (hidden) appName else if (download) text else title?.takeUnless { it.isBlank() } ?: appName
        prepare(host, container, frame, icon, badge, body, sender, hidden, hasAvatar)
        val maxBodyLines = if (hidden || download) 1 else 2
        body.maxLines = maxBodyLines
        if (entry != null) {
            quickOpen?.let(container::addView)
            if (!hidden) {
                container.layoutParams = (container.layoutParams as RelativeLayout.LayoutParams).apply {
                    removeRule(RelativeLayout.ALIGN_PARENT_TOP)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                    topMargin = 0
                }
            }
        } else {
            // The footer and body are siblings. Wait for their common parent's
            // layout, including preloaded pages whose first bounds are zero.
            (footer?.parent as? View ?: host).doOnLayout { positionCard(host, container, footer, maxBodyLines) }
            host.doOnPreDraw { positionCard(host, container, footer, maxBodyLines) }
        }
        bindings[host] = Binding(source, hidden)
        if (entry != null) identities[host] = identity(entry)
        if (!reveal) return
        val controls = if (entry == null) listOf(R.id.rear_notification_actions,
            R.id.rear_notification_progress, R.id.rear_notification_subtext,
            R.id.rear_notification_chronometer).mapNotNull { footer?.findViewById<View>(it) }
        else listOfNotNull(quickOpen)
        controls.forEach { it.alpha = 0f }
        ghosts.forEach { host.overlay.add(it) }
        // Hold the first frame until the public layout has been measured. No
        // public glyph/avatar may flash before the first animation callback.
        body.alpha = 0f; sender.alpha = 0f
        if (hasAvatar) icon.alpha = 0f
        var finished = false
        var tick: Runnable? = null
        lateinit var detached: View.OnAttachStateChangeListener
        val finish: () -> Unit = {
            if (!finished) {
                finished = true
                tick?.let(host::removeCallbacks)
                listOf(frame, icon, badge, body, sender).forEach {
                    it.alpha = 1f; it.translationX = 0f; it.translationY = 0f; it.scaleX = 1f; it.scaleY = 1f
                }
                controls.forEach { it.alpha = 1f }
                ghosts.forEach { host.overlay.remove(it); it.setImageDrawable(null) }
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
        host.doOnPreDraw {
            if (finished) return@doOnPreDraw
            val finalFrame = bounds(host, frame)
            val dx = (oldFrame.left - finalFrame.left).toFloat()
            val dy = (oldFrame.top - finalFrame.top).toFloat()
            val pixels = host.height / 240f
            badge.pivotX = 0f; badge.pivotY = 0f
            icon.pivotX = icon.width / 2f; icon.pivotY = icon.height / 2f
            val startScale = oldIcon.width().toFloat() / badge.width.coerceAtLeast(1)
            val oldCenterX = oldFrame.left + oldIcon.exactCenterX()
            val oldCenterY = oldFrame.top + oldIcon.exactCenterY()
            val badgeCenterX = finalFrame.left + badge.left + badge.width / 2f
            val badgeCenterY = finalFrame.top + badge.top + badge.height / 2f
            fun render(t: Float) {
                val movement = monotone(geometry, t)
                frame.translationX = dx * (1f - movement)
                frame.translationY = dy * (1f - movement)
                if (hasAvatar) {
                    val p = monotone(appMorph, t)
                    badge.scaleX = startScale + (1f - startScale) * p; badge.scaleY = badge.scaleX
                    // Interpolate the visible center in panel coordinates.
                    // Adding a separate local move to the parent's upward move
                    // made the badge dip upward then double back near the end.
                    badge.translationX = oldCenterX + (badgeCenterX - oldCenterX) * p -
                        (finalFrame.left + frame.translationX + badge.left + badge.width * badge.scaleX / 2f)
                    badge.translationY = oldCenterY + (badgeCenterY - oldCenterY) * p -
                        (finalFrame.top + frame.translationY + badge.top + badge.height * badge.scaleY / 2f)
                    icon.alpha = ((t - 4f) / 5f).coerceIn(0f, 1f)
                    icon.scaleX = monotone(avatarScale, t); icon.scaleY = icon.scaleX
                }
                ghosts.forEachIndexed { index, ghost ->
                    ghost.alpha = sample(if (index == 0) oldBody else oldSender, t).coerceIn(0f, 1f)
                    ghost.translationY = -dy * movement
                }
                body.alpha = sample(newBody, t).coerceIn(0f, 1f)
                sender.alpha = sample(newSender, t).coerceIn(0f, 1f)
                body.translationY = sample(bodyOffset, t) * pixels
                sender.translationY = sample(senderOffset, t) * pixels
                // Old private labels leave before controls enter their slots.
                controls.forEach { it.alpha = ((t - 10f) / 7f).coerceIn(0f, 1f) }
            }
            render(0f)
            val started = SystemClock.uptimeMillis()
            val duration = if (entry != null) POPUP_REVEAL_MS else 700L
            tick = object : Runnable {
                override fun run() {
                    if (finished) return
                    val elapsed = SystemClock.uptimeMillis() - started
                    render((elapsed.toFloat() / duration * 21f).coerceIn(0f, 21f))
                    if (elapsed >= duration) finish() else host.postOnAnimation(this)
                }
            }
            host.postOnAnimation(tick!!)
            YLog.debug("[RearPopupReveal] reference duration=$duration avatar=$hasAvatar dy=$dy")
        }
    }

    private fun prepare(host: ViewGroup, container: LinearLayout, frame: FrameLayout, icon: ImageView,
                        badge: ImageView, body: TextView, sender: TextView, hidden: Boolean, hasAvatar: Boolean) {
        fun dp(value: Float) = (value * host.resources.displayMetrics.density).roundToInt()
        listOf(frame, body, sender).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        container.removeAllViews()
        container.orientation = LinearLayout.VERTICAL
        container.gravity = Gravity.TOP
        container.clipChildren = false; frame.clipChildren = false
        icon.layoutParams = FrameLayout.LayoutParams(dp(if (hasAvatar) 58f else 56f), dp(if (hasAvatar) 58f else 56f), Gravity.START).apply {
            if (hasAvatar) { marginStart = dp(-1f); topMargin = dp(-1f) }
        }
        badge.layoutParams = FrameLayout.LayoutParams(dp(24.5f), dp(24.5f), Gravity.START).apply {
            marginStart = dp(36f); topMargin = dp(36f)
        }
        container.addView(frame, LinearLayout.LayoutParams(-1, dp(if (hasAvatar) 61f else 56f)))
        body.apply {
            tag = "rear_message_privacy_label"
            gravity = Gravity.START; minLines = 1; maxLines = if (hidden) 1 else 2
            includeFontPadding = false; isFallbackLineSpacing = true
            setTextColor(-1); setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(20f).toFloat())
            typeface = Typeface.create(Typeface.create("sans-serif", 0), 500, false)
            setLineHeight(dp(24f)); setPadding(0, 0, if (hidden) dp(28f) else 0, 0)
        }
        sender.apply {
            gravity = Gravity.START; maxLines = 1; includeFontPadding = false
            setTextColor(0x99ffffff.toInt()); setTextSize(TypedValue.COMPLEX_UNIT_PX, dp(14f).toFloat())
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setLineSpacing(0f, 1f); setLineHeight(dp(18f)); setPadding(0, 0, 0, 0)
        }
        container.addView(body, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (hidden) 8f else 2f) })
        container.addView(sender, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4f) })
        val height = host.height.takeIf { it > 0 } ?: host.resources.displayMetrics.heightPixels
        container.layoutParams = (container.layoutParams as RelativeLayout.LayoutParams).apply {
            removeRule(RelativeLayout.CENTER_VERTICAL); addRule(RelativeLayout.ALIGN_PARENT_TOP)
            topMargin = (height * if (hidden) .225f else .125f).roundToInt()
        }
    }

    private fun positionCard(host: ViewGroup, container: LinearLayout, footer: View?, maxBodyLines: Int) {
        if (host.height <= 0 || container.height <= 0 ||
            footer?.visibility == View.VISIBLE && (footer.height <= 0 || footer.bottom <= 0)) return
        val density = host.resources.displayMetrics.density
        // Keep the factory clock and footer slots. Center the actual measured
        // content between them; action rows never cover the icon or labels.
        val top = (32f * density).roundToInt()
        val bottom = if (footer?.visibility == View.VISIBLE && footer.height > 0)
            minOf(host.height - 12f * density, footer.y - 8f * density)
        else host.height - 16f * density
        val body = host.findViewById<TextView>(id(host, "notification_content")) ?: return
        val bodySpace = bottom - top - (container.height - body.height)
        val lines = (bodySpace / body.lineHeight.coerceAtLeast(1)).toInt().coerceIn(1, maxBodyLines)
        if (body.maxLines != lines) {
            body.maxLines = lines
            // Apply the same measured bounds to this frame and the next parent
            // layout, before the reveal animation samples its final geometry.
            container.measure(View.MeasureSpec.makeMeasureSpec(container.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            container.layout(container.left, container.top, container.right, container.top + container.measuredHeight)
        }
        val target = (top + (bottom - top - container.height) / 2f).roundToInt().coerceAtLeast(top)
        val params = container.layoutParams as RelativeLayout.LayoutParams
        if (params.topMargin == target && container.top == target) return
        params.topMargin = target
        container.offsetTopAndBottom(target - container.top)
        container.layoutParams = params
    }

    private fun snapshot(view: TextView, host: ViewGroup): ImageView? {
        if (view.width <= 0 || view.height <= 0) return null
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val b = bounds(host, view)
        return ImageView(host.context).apply { setImageBitmap(bitmap); layout(b.left, b.top, b.right, b.bottom) }
    }

    fun removeQuickOpen(host: ViewGroup) {
        host.findViewWithTag<View>(QUICK_OPEN_TAG)?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    fun forget(host: ViewGroup) {
        running.remove(host)?.invoke(); bindings.remove(host); identities.remove(host)
        originalLayouts.remove(host)?.invoke()
        removeQuickOpen(host)
    }
    fun clear() {
        running.values.toList().forEach { it() }; bindings.clear(); identities.clear()
        originalLayouts.keys.toList().forEach(::forget)
        synchronized(avatars) { avatars.clear() }
    }

    // Independent measured curves, interpolated between source frames. A
    // generic ease applied to the entire group loses the stagger in the clip.
    private fun sample(values: FloatArray, t: Float): Float {
        val x = t.coerceIn(0f, values.lastIndex.toFloat())
        val i = floor(x).toInt(); val f = x - i
        fun at(n: Int) = values[n.coerceIn(0, values.lastIndex)]
        val a = at(i - 1); val b = at(i); val c = at(i + 1); val d = at(i + 2)
        return .5f * ((2f * b) + (-a + c) * f + (2f * a - 5f * b + 4f * c - d) * f * f +
            (-a + 3f * b - 3f * c + d) * f * f * f)
    }

    /** Shape-preserving Hermite interpolation. Zero/one endpoints and plateaus
     * cannot overshoot or reverse the icon's direction between video frames. */
    private fun monotone(values: FloatArray, t: Float): Float {
        val x = t.coerceIn(0f, values.lastIndex.toFloat())
        val i = floor(x).toInt().coerceAtMost(values.lastIndex - 1)
        val f = x - i
        fun slope(n: Int): Float {
            if (n == 0 || n == values.lastIndex) return 0f
            val before = values[n] - values[n - 1]
            val after = values[n + 1] - values[n]
            return if (before <= 0f || after <= 0f) 0f else 2f * before * after / (before + after)
        }
        val f2 = f * f; val f3 = f2 * f
        return ((2f*f3 - 3f*f2 + 1f) * values[i] + (f3 - 2f*f2 + f) * slope(i) +
            (-2f*f3 + 3f*f2) * values[i+1] + (f3-f2) * slope(i+1)).coerceIn(values[i], values[i+1])
    }
    private val geometry = floatArrayOf(0f,.01f,.04f,.09f,.17f,.29f,.41f,.52f,.62f,.71f,.78f,.83f,.86f,.90f,.94f,.96f,.98f,.995f,1f,1f,1f,1f)
    // The old raw measurements (.917 → .889) included bitmap/bounds noise.
    // Fit the same 700 ms travel without feeding that reversal into the UI.
    private val appMorph = floatArrayOf(0f,.018f,.05f,.09f,.145f,.22f,.305f,.395f,.485f,.575f,.66f,.735f,.80f,.85f,.895f,.93f,.955f,.973f,.985f,.993f,.998f,1f)
    private val avatarScale = floatArrayOf(.5f,.5f,.5f,.5f,.55f,.59f,.63f,.67f,.71f,.76f,.82f,.85f,.88f,.91f,.94f,.96f,.98f,.995f,1f,1f,1f,1f)
    private val oldBody = floatArrayOf(1f,1f,1f,.73f,.20f,.02f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f)
    private val oldSender = floatArrayOf(1f,1f,1f,1f,1f,.81f,.34f,.06f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,0f)
    private val newBody = floatArrayOf(0f,0f,0f,0f,0f,0f,0f,0f,.24f,.62f,.83f,.97f,1f,1f,1f,1f,1f,1f,1f,1f,1f,1f)
    private val newSender = floatArrayOf(0f,0f,0f,0f,0f,0f,0f,0f,0f,0f,.33f,.64f,.85f,.95f,.96f,.95f,.96f,.97f,.97f,.98f,.99f,1f)
    private val bodyOffset = floatArrayOf(24f,24f,24f,24f,22f,21f,18f,14f,10f,8f,6f,5f,4f,3f,2f,2f,1f,1f,0f,0f,0f,0f)
    private val senderOffset = floatArrayOf(26f,26f,26f,26f,26f,26f,24f,22f,18f,14f,10f,8f,6f,4f,3f,2f,2f,1f,0f,0f,0f,0f)
}
