package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.view.Choreographer
import android.view.Display
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import hk.uwu.reareye.hook.support.YLog
import kotlin.math.max
import kotlin.math.roundToInt

/** One map layer and one legible information layer inside the OEM card's clipping/gestures. */
internal class RearNavigationCard(context: Context, initial: RearNavigationData) : FrameLayout(context) {
    companion object {
        private var mapOwner = java.lang.ref.WeakReference<RearNavigationCard>(null)
        private val leftInstruction = Regex("左|left|gauche")
        private val rightInstruction = Regex("右|right|droite")
        private val straightInstruction = Regex("直行|前行|前进|straight|head|continue|tout droit")
    }
    private data class GuidanceLayout(val title: String, val detail: String, val width: Int,
        val unit: Float, val color: Int)
    private val main = Handler(Looper.getMainLooper())
    private val mapScale = .72f
    private var data = initial
    private val mapSurface = SurfaceView(context)
    private var capturedMap: Bitmap? = null
    private var capturedAt = 0L
    private val materialBackdrop = object : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val target = RectF()
        override fun onDraw(canvas: Canvas) {
            // A single complete frame supplies both the visible map and the native
            // glass input. No older map patches are overlaid on a live surface.
            canvas.drawColor(if (darkMap) 0xff10242c.toInt() else 0xffe8edf0.toInt())
            val bitmap = capturedMap ?: return
            val scale = mapSurface.height.toFloat()/bitmap.height
            target.set(0f, 0f, bitmap.width*scale, bitmap.height*scale)
            if (canvas.isHardwareAccelerated || bitmap.config != Bitmap.Config.HARDWARE) {
                canvas.drawBitmap(bitmap, null, target, paint)
            } else {
                // The OEM pager also draws cards onto a software snapshot canvas.
                bitmap.copy(Bitmap.Config.ARGB_8888, false)?.let { readable ->
                    try { canvas.drawBitmap(readable, null, target, paint) }
                    finally { readable.recycle() }
                }
            }
        }
    }

    private val headerGlass = View(context)
    private val footerGlass = View(context)
    private val guidance = Guidance(context)
    private var service: RearNavigationMapService? = null
    private var asleep = false
    private var active = false
    private var ambientPresentation: Boolean? = null
    private var requestedAmbient: Boolean? = null
    private var mismatchedThemeReported = false
    private var disposed = false
    private var mapAttempts = 0
    private var retryMapAt = 0L
    private var mapStatus = ""
    private var mapConnectingAt = 0L
    private var darkMap = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private var samplePending = false
    private var nextAppearanceAt = 0L
    private var providerReady = false
    private var frameGeneration = 0
    private var lastFrameTime = 0L
    private var lastFrameNanos = 0L
    private var resumeFrameAt = 0L
    private var captureUntil = 0L
    private var compositionSettlesAt = 0L
    private var perspectiveReported = false
    private fun transformedMapHost(): View? {
        var node: View? = mapSurface
        while (node != null) {
            if (kotlin.math.abs(node.rotationX) > .01f || kotlin.math.abs(node.rotationY) > .01f ||
                kotlin.math.abs(node.scaleX-node.scaleY) > .01f) return node
            node = node.parent as? View
        }
        return null
    }
    private fun refreshThemeFrames() {
        captureUntil = SystemClock.uptimeMillis() + 4_000L
        nextAppearanceAt = 0L
        Choreographer.getInstance().removeFrameCallback(frames)
        if (isAttachedToWindow && !disposed) Choreographer.getInstance().postFrameCallback(frames)
    }
    private val releaseIdle = Runnable {
        if (!active && (!isAmbient() || !NativeRearCardHost.isSelected(this))) releaseProvider()
    }
    private val frames = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if ((!active && SystemClock.uptimeMillis() >= captureUntil) || disposed || !isAttachedToWindow) return
            val now = SystemClock.uptimeMillis()
            val transforming = transformedMapHost()
            if (transforming != null) {
                compositionSettlesAt = now + 100L
                if (!perspectiveReported) YLog.info("[RearNavigation] native host perspective " +
                    "x=${transforming.rotationX} y=${transforming.rotationY} scale=${transforming.scaleX},${transforming.scaleY}")
                perspectiveReported = true
            } else perspectiveReported = false
            if (transforming == null && now >= compositionSettlesAt && providerReady && !samplePending && now >= resumeFrameAt &&
                frameTimeNanos-lastFrameNanos >= 30_000_000L) {
                lastFrameNanos = frameTimeNanos
                sampleAppearance()
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
    private var samplingFailureReported = false
    private var lastMapLuma = -1f
    private var lastBarLuma = -1f
    private var bar: View? = null
    private var previousBarDark: Boolean? = null
    private var providerArrival = ""
    private var providerProgress: Float? = null
    val packageName: String get() = data.packageName
    var sourceKey: String? = null
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || disposed) return
            updateDisplayPresentation()
            val selected = isShown && NativeRearCardHost.isSelected(this@RearNavigationCard)
            val shown = selected && windowVisibility == VISIBLE && !asleep && display?.state == Display.STATE_ON
            if (shown != active) {
                frameGeneration++
                active = shown
                keepScreenOn = shown
                main.removeCallbacks(releaseIdle)
                Choreographer.getInstance().removeFrameCallback(frames)
                if (!shown) {
                    service?.visible(false)
                    if (packageName == "com.google.android.apps.maps") releaseProvider()
                    else main.postDelayed(releaseIdle, 600L)
                    restoreBar()
                    if (SystemClock.uptimeMillis() < captureUntil)
                        Choreographer.getInstance().postFrameCallback(frames)
                } else {
                    mapSurface.visibility = VISIBLE
                    service?.visible(true)
                    lastFrameTime = SystemClock.uptimeMillis()
                    Choreographer.getInstance().postFrameCallback(frames)
                    applyGlass()
                }
            }
            if (service?.isClosed == true) service = null
            if (!shown && service != null && !selected &&
                !main.hasCallbacks(releaseIdle)) main.postDelayed(releaseIdle, 600L)
            val now = SystemClock.uptimeMillis()
            val ambientMap = isAmbient() && selected &&
                packageName != "com.google.android.apps.maps"
            if ((shown || ambientMap) && service == null && now >= retryMapAt) {
                mapSurface.visibility = VISIBLE
                startProvider()
            }
            if (shown && providerReady && now-lastFrameTime > 6_000L) {
                releaseProvider()
                retryMapAt = now + 350L
            }
            if (shown && !guidance.mapAvailable) guidance.invalidate()
            main.postDelayed(this, if (selected) 100L else 300L)
        }
    }

    init {
        clipChildren = true
        setBackgroundColor(0xff10242c.toInt())
        mapSurface.visibility = INVISIBLE
        addView(mapSurface, LayoutParams(-1, -1))
        addView(materialBackdrop, LayoutParams(-1, -1))
        addView(headerGlass, LayoutParams(0, 0))
        addView(footerGlass, LayoutParams(0, 0))
        addView(guidance, LayoutParams(-1, -1))
    }

    fun update(value: RearNavigationData) {
        if (data.packageName != value.packageName) {
            releaseProvider()
            capturedMap = null
            materialBackdrop.invalidate()
            Choreographer.getInstance().removeFrameCallback(frames)
            active = false
            keepScreenOn = false
            mapAttempts = 0
            providerArrival = ""
            providerProgress = null
        }
        data = value
        guidance.bind()
    }

    fun aod(value: Boolean) {
        asleep = value
        updateDisplayPresentation()
        main.removeCallbacks(heartbeat)
        if (isAttachedToWindow && !disposed) main.post(heartbeat)
    }

    private fun isAmbient(): Boolean = asleep || display?.state != Display.STATE_ON

    private fun presentedAmbient(): Boolean = ambientPresentation ?: isAmbient()

    fun syncFrame() {
        val cached = RearNavigationFrames.find(sourceKey) ?: return
        if (capturedMap != null && cached.time <= capturedAt) return
        capturedMap = cached.bitmap
        capturedAt = cached.time
        commitPresentation(cached.ambient, cached.dark)
        materialBackdrop.invalidate()
    }

    private fun commitPresentation(ambient: Boolean, dark: Boolean) {
        if (ambientPresentation == ambient && darkMap == dark) return
        ambientPresentation = ambient
        darkMap = dark
        headerGlass.visibility = if (ambient) INVISIBLE else VISIBLE
        footerGlass.visibility = if (ambient) INVISIBLE else VISIBLE
        if (ambient) {
            NativeRearGlass.clearNavigation(headerGlass)
            NativeRearGlass.clearNavigation(footerGlass)
        } else applyGlass()
        guidance.bind()
        YLog.info("[RearNavigation] presentation committed ambient=$ambient dark=$dark")
    }

    private fun updateDisplayPresentation() {
        val ambient = isAmbient()
        syncFrame()
        if (requestedAmbient == ambient) return
        requestedAmbient = ambient
        frameGeneration++
        mismatchedThemeReported = false
        if (capturedMap != null) resumeFrameAt = SystemClock.uptimeMillis() + 600L
        // AMap's map frame and information panels change presentation together.
        // The other providers keep their existing ambient lifecycle.
        if (capturedMap == null || packageName != "com.autonavi.minimap")
            commitPresentation(ambient, darkMap)
        service?.ambient(ambient)
        refreshThemeFrames()
        if (ambient) {
            keepScreenOn = false
            restoreBar()
        }
    }

    private fun startProvider() {
        if (service != null) return
        mapOwner.get()?.releaseProvider(retain = true)
        mapOwner = java.lang.ref.WeakReference(this)
        mapAttempts++
        retryMapAt = SystemClock.uptimeMillis() + when (mapAttempts) {
            1 -> 350L
            2 -> 1_000L
            else -> 4_000L
        }
        mapStatus = "正在连接导航地图"
        mapConnectingAt = SystemClock.uptimeMillis()
        val next = RearNavigationMapService.obtain(sourceKey, context, mapSurface, data.packageName, mapScale, { width })
        service = next
        frameGeneration++
        lastFrameTime = SystemClock.uptimeMillis()
        var firstContent = !next.hasContent
        next.also {
            it.onStatus = { status ->
                if (service === next) { mapStatus = status; guidance.invalidate() }
            }
            it.onContent = { available ->
                if (service === next) {
                    frameGeneration++
                    providerReady = available
                    // Retain a complete frame while a new remote renderer starts.
                    resumeFrameAt = maxOf(resumeFrameAt, SystemClock.uptimeMillis() +
                        if (available && firstContent && capturedMap != null) 800L else 0L)
                    if (available) firstContent = false
                    guidance.mapAvailable = false
                    if (available && isAmbient()) refreshThemeFrames()
                    guidance.invalidate()
                }
            }
            it.onThemeChanged = { if (service === next) refreshThemeFrames() }
            it.onNavigation = { remaining ->
                val seconds = remaining.getInt("remainingSeconds", -1)
                providerArrival = if (seconds >= 0) "预计 " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(System.currentTimeMillis() + seconds*1_000L)) + " 到达" else ""
                val total = remaining.getInt("totalMeters", -1)
                val meters = remaining.getInt("remainingMeters", -1)
                providerProgress = if (total > 0 && meters >= 0) (1f - meters.toFloat()/total).coerceIn(0f, 1f) else null
                guidance.bind()
            }
            it.bounds(mapBounds())
            it.visible(!isAmbient())
            it.ambient(isAmbient())
            it.start()
        }
    }

    private fun safeLeft(): Int {
        val cutout = display?.cutout?.safeInsetLeft ?: 0
        val cameraId = resources.getIdentifier("camera_place_holder", "id", context.packageName)
        val camera = rootView.findViewById<View>(cameraId)?.width ?: 0
        // The factory template reserves 103 of its 213 height units for the cameras.
        return max(max(cutout, camera), (height * 103f / 213f).roundToInt()).coerceAtMost(width / 2)
    }

    private fun mapBounds(): Rect {
        val margin = (height * .04f).toInt()
        val top = if (headerGlass.height > 0) headerGlass.bottom + margin else (height * .25f).toInt()
        val bottom = if (footerGlass.height > 0) footerGlass.top - margin else (height * .65f).toInt()
        val topOverhang = height * .15f
        return Rect(((safeLeft() + margin)/mapScale).roundToInt(), ((top+topOverhang)/mapScale).roundToInt(),
            ((width - margin)/mapScale).roundToInt(),
            ((bottom.coerceAtLeast(top + 1)+topOverhang)/mapScale).roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Providers which centre their walking marker in the viewport also land
        // at the centre of the camera-free region. The parent clips the overhang.
        mapSurface.layoutParams = LayoutParams(w + safeLeft(), h)
        service?.bounds(mapBounds())
        materialBackdrop.invalidate()
        guidance.bind()
        applyGlass()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        layoutPanels()
    }

    private fun layoutPanels() {
        if (width <= 0 || height <= 0) return
        val u = height / 213f
        val left = safeLeft() + 12f * u
        val right = width - 16f * u
        val bottom = footerBottom()
        val footerHeight = guidance.footerHeight(u)
        headerGlass.layout(left.roundToInt(), (15*u).roundToInt(), right.roundToInt(), (45*u).roundToInt())
        footerGlass.layout(left.roundToInt(), (bottom-footerHeight).coerceAtLeast(93*u).roundToInt(),
            right.roundToInt(), bottom.roundToInt())
        service?.bounds(mapBounds())
        guidance.invalidate()
    }

    private fun bottomBar(): View? {
        val id = resources.getIdentifier("bottom_bar", "id", context.packageName)
        return rootView.findViewById(id)
    }

    private fun footerBottom(): Float {
        val line = bottomBar() ?: return height.toFloat()
        if (line.height <= 0 || !isAttachedToWindow) return height.toFloat()
        // Native pages animate with translationY. Layout coordinates retain
        // the card's resting geometry throughout the pager's transition.
        fun layoutTop(view: View): Int {
            var top = 0
            var node: View? = view
            while (node != null && node !== rootView) {
                top += node.top
                node = node.parent as? View
            }
            return top
        }
        val lineTop = layoutTop(line)
        val bottomGap = rootView.height - lineTop - line.height
        return (lineTop - bottomGap - layoutTop(this)).toFloat().coerceIn(height*.7f, height.toFloat())
    }

    private fun applyGlass() {
        if (presentedAmbient() || height <= 0) return
        val radius = height * 12f / 213f
        NativeRearGlass.navigation(headerGlass, darkMap, radius)
        NativeRearGlass.navigation(footerGlass, darkMap, radius)
        guidance.bind()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        guidance.clearAppResources()
        if (!guidance.mapAvailable && capturedMap == null) {
            darkMap = newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            applyGlass()
        }
    }

    private fun sampleAppearance() {
        val source = service?.captureSource() ?: return
        samplePending = true
        val generation = frameGeneration
        val now = SystemClock.uptimeMillis()
        val frameAmbient = isAmbient()
        val expectedDark = service?.expectedDark
        val validateTheme = expectedDark != null && (frameAmbient || now < captureUntil)
        val inspect = validateTheme || now >= nextAppearanceAt || !guidance.mapAvailable
        if (inspect) nextAppearanceAt = now + 1_000L
        val left = safeLeft()
        val cardWidth = width
        RearNavigationFrames.execute {
            val appearance = runCatching {
                RearNavigationFrames.capture(source, inspect, left, cardWidth)
            }.onFailure {
                if (!samplingFailureReported) {
                    samplingFailureReported = true
                    YLog.warn("Rear navigation frame capture unavailable", it)
                }
            }.getOrNull()
            main.post {
                samplePending = false
                if (appearance == null) {
                    if (inspect && generation == frameGeneration) guidance.mapAvailable = false
                    return@post
                }
                if ((!active && SystemClock.uptimeMillis() >= captureUntil) || disposed || generation != frameGeneration) {
                    appearance.bitmap.recycle()
                    return@post
                }
                val frameDark = if (appearance.mapLuma >= 0) appearance.mapLuma < .5f else darkMap
                if (validateTheme && frameDark != expectedDark) {
                    if (!mismatchedThemeReported) {
                        YLog.info("[RearNavigation] waiting for map palette ambient=$frameAmbient expectedDark=$expectedDark actualDark=$frameDark")
                        mismatchedThemeReported = true
                    }
                    appearance.bitmap.recycle()
                    return@post
                }
                capturedMap = appearance.bitmap
                capturedAt = SystemClock.uptimeMillis()
                commitPresentation(frameAmbient, frameDark)
                sourceKey?.let { RearNavigationFrames.store(
                    RearNavigationFrames.Poster(it, appearance.bitmap, capturedAt, frameDark, frameAmbient)) }
                lastFrameTime = capturedAt
                if (frameAmbient && expectedDark != null) {
                    captureUntil = 0L
                    service?.frameCommitted()
                }
                if (!guidance.mapAvailable) {
                    guidance.mapAvailable = true
                    mapAttempts = 0
                    YLog.info("[RearNavigation] first complete map frame package=$packageName")
                    guidance.invalidate()
                }
                materialBackdrop.invalidate()
                if (appearance.mapLuma >= 0 && kotlin.math.abs(appearance.mapLuma-lastMapLuma) > .05f) {
                    lastMapLuma = appearance.mapLuma
                }
                if (appearance.barLuma >= 0 && kotlin.math.abs(appearance.barLuma-lastBarLuma) > .05f) {
                    lastBarLuma = appearance.barLuma
                    val line = bottomBar() ?: return@post
                    if (bar !== line) {
                        bar = line
                        previousBarDark = runCatching {
                            line.javaClass.getDeclaredField("d").apply { isAccessible = true }.getBoolean(line)
                        }.getOrNull()
                    }
                    runCatching { line.javaClass.getMethod("setDark", Boolean::class.javaPrimitiveType)
                        .invoke(line, appearance.barLuma < .5f) }
                }
            }
        }
    }

    private fun restoreBar() {
        val line = bar
        val dark = previousBarDark
        if (line != null && dark != null) runCatching {
            line.javaClass.getMethod("setDark", Boolean::class.javaPrimitiveType).invoke(line, dark)
        }
        bar = null
        previousBarDark = null
        lastBarLuma = -1f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (disposed) return
        syncFrame()
        main.removeCallbacks(heartbeat)
        main.post(heartbeat)
    }

    override fun onDetachedFromWindow() {
        keepScreenOn = false
        capturedMap = null
        main.removeCallbacks(heartbeat)
        main.removeCallbacks(releaseIdle)
        Choreographer.getInstance().removeFrameCallback(frames)
        releaseProvider(retain = true)
        restoreBar()
        NativeRearGlass.clearNavigation(headerGlass)
        NativeRearGlass.clearNavigation(footerGlass)
        active = false
        mapAttempts = 0
        super.onDetachedFromWindow()
    }

    fun dispose(retainSession: Boolean = true) {
        disposed = true
        keepScreenOn = false
        capturedMap = null
        main.removeCallbacks(heartbeat)
        main.removeCallbacks(releaseIdle)
        Choreographer.getInstance().removeFrameCallback(frames)
        releaseProvider(retain = retainSession)
        restoreBar()
    }

    private fun releaseProvider(retain: Boolean = false) {
        frameGeneration++
        providerReady = false
        guidance.mapAvailable = false
        val previous = service
        service = null
        if (previous != null && (!retain || !previous.park(sourceKey))) previous.close()
        if (mapOwner.get() === this) mapOwner.clear()
    }

    private inner class Guidance(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private var appIcon: Drawable? = null
        private var appPackage: String? = null
        private var appLabel = ""
        private var headerText: CharSequence = ""
        private var boundLayout: GuidanceLayout? = null
        private var sourceArrow: Drawable? = null
        private var arrow: Drawable? = null
        private var direction: Float? = null
        private val mediumTypeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        private val panelRect = RectF()
        private var title: StaticLayout? = null
        private var detail: StaticLayout? = null
        private val arrowPath = Path()
        var mapAvailable = false

        init { bind() }

        fun clearAppResources() {
            appPackage = null
            bind()
        }

        fun bind() {
            if (appPackage != data.packageName) {
                appPackage = data.packageName
                val manager = context.packageManager
                appIcon = runCatching { manager.getApplicationIcon(data.packageName) }.getOrNull()
                appLabel = runCatching { manager.getApplicationLabel(
                    manager.getApplicationInfo(data.packageName, 0)).toString() }.getOrDefault("")
            }
            val unit = this@RearNavigationCard.height / 213f
            val available = (this@RearNavigationCard.width - safeLeft() - 34 * unit).toInt()
            val color = if (presentedAmbient() || darkMap) Color.WHITE else 0xff17191c.toInt()
            if (unit > 0 && available > 0) {
                val next = GuidanceLayout(data.title, data.detail, available, unit, color)
                if (next != boundLayout) {
                    boundLayout = next
                    val previousHeight = footerHeight(unit)
                    title = layout(data.title, 17f * unit, available - (35 * unit).toInt(), 2, true)
                    detail = layout(data.detail, 12f * unit, available - (35 * unit).toInt(), 1, false)
                    if (previousHeight != footerHeight(unit)) this@RearNavigationCard.requestLayout()
                }
                text.textSize = 12f * unit
                text.typeface = mediumTypeface
                headerText = TextUtils.ellipsize(data.arrival.ifBlank { providerArrival }.ifBlank { appLabel },
                    text, (available - 95 * unit).coerceAtLeast(1f), TextUtils.TruncateAt.END)
            }
            if (sourceArrow !== data.arrow) {
                sourceArrow = data.arrow
                arrow = data.arrow?.let { it.constantState?.newDrawable(resources)?.mutate() ?: it.mutate() }
            }
            arrow?.setTint(color)
            val instruction = "${data.title} ${data.detail}".lowercase(java.util.Locale.ROOT)
            direction = when {
                leftInstruction.containsMatchIn(instruction) -> -90f
                rightInstruction.containsMatchIn(instruction) -> 90f
                straightInstruction.containsMatchIn(instruction) -> 0f
                else -> null
            }
            invalidate()
        }

        private fun layout(value: String, size: Float, width: Int, lines: Int, bold: Boolean): StaticLayout {
            val style = TextPaint(text).apply {
                textSize = size
                color = if (presentedAmbient() || darkMap) Color.WHITE else 0xff17191c.toInt()
                typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            }
            return StaticLayout.Builder.obtain(value, 0, value.length, style, width.coerceAtLeast(1))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
                .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).build()
        }

        override fun onDraw(canvas: Canvas) {
            val u = height / 213f
            if (u <= 0f) return
            val left = safeLeft() + 12f * u
            val right = width - 16f * u
            val ambient = presentedAmbient()
            if (ambient) {
                paint.color = 0x55000000
                val radius = 12*u
                panelRect.set(headerGlass.left.toFloat(), headerGlass.top.toFloat(),
                    headerGlass.right.toFloat(), headerGlass.bottom.toFloat())
                canvas.drawRoundRect(panelRect, radius, radius, paint)
                panelRect.set(footerGlass.left.toFloat(), footerGlass.top.toFloat(),
                    footerGlass.right.toFloat(), footerGlass.bottom.toFloat())
                canvas.drawRoundRect(panelRect, radius, radius, paint)
            }
            if (!ambient && active && !mapAvailable && capturedMap == null &&
                SystemClock.uptimeMillis()-mapConnectingAt >= 2_000L) {
                text.textSize = 11*u
                text.color = 0xffbdc9d4.toInt()
                val status = mapStatus.ifBlank { "正在连接导航地图" }
                canvas.drawText(status, left+8*u, 83*u, text)
            }
            drawIcon(canvas, appIcon, left + 7*u, 20*u, 20*u)
            text.textSize = 12f*u
            text.typeface = mediumTypeface
            text.color = if (ambient || darkMap) Color.WHITE else 0xff17191c.toInt()
            canvas.drawText(headerText, 0, headerText.length, left + 34*u, 34*u, text)

            val titleHeight = title?.height ?: 0
            val detailHeight = detail?.takeIf { data.detail.isNotBlank() }?.height ?: 0
            val footerTop = footerGlass.top.toFloat()
            canvas.save()
            canvas.translate(left + 10*u, footerTop + 7*u)
            title?.draw(canvas)
            if (detailHeight > 0) {
                canvas.translate(0f, titleHeight + 2*u)
                detail?.draw(canvas)
            }
            canvas.restore()
            if (arrow != null) {
                drawIcon(canvas, arrow, right-33*u, footerTop+12*u, 23*u)
            } else drawDirection(canvas, right-22*u, footerTop+25*u, u)
            drawProgress(canvas, left+12*u, right-12*u, footerGlass.bottom-9*u, u)
        }

        fun footerHeight(u: Float): Float = max(54f*u,
            (title?.height ?: 0) + (detail?.takeIf { data.detail.isNotBlank() }?.height ?: 0) + 26f*u)

        private fun drawProgress(canvas: Canvas, left: Float, right: Float, y: Float, u: Float) {
            val span = right-left
            if (span <= 0) return
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 5*u
            paint.color = 0xff647382.toInt()
            canvas.drawLine(left, y, right, y, paint)
            var start = left
            data.segments.forEach { segment ->
                val end = (start + span*segment.fraction).coerceIn(left, right)
                paint.color = segment.color
                canvas.drawLine(start, y, end, y, paint)
                start = end
            }
            (data.progress ?: providerProgress)?.let { progress ->
                paint.color = 0xff4285f4.toInt()
                canvas.drawLine(left, y, left+span*progress, y, paint)
            }
            data.stations.forEach { station ->
                paint.color = 0xff10151e.toInt()
                canvas.drawCircle(left+span*station, y, 4*u, paint)
                paint.color = Color.WHITE
                canvas.drawCircle(left+span*station, y, 2.1f*u, paint)
            }
            (data.progress ?: providerProgress)?.let { progress ->
                val x = left+span*progress
                paint.color = Color.WHITE
                canvas.drawCircle(x, y, 7*u, paint)
                if (data.tracker != null) drawIcon(canvas, data.tracker, x-5*u, y-5*u, 10*u)
                else {
                    paint.color = 0xff4285f4.toInt()
                    canvas.drawCircle(x, y, 4.5f*u, paint)
                }
            }
        }

        private fun drawIcon(canvas: Canvas, icon: Drawable?, x: Float, y: Float, size: Float) {
            icon ?: return
            icon.setBounds(x.toInt(), y.toInt(), (x+size).toInt(), (y+size).toInt())
            icon.draw(canvas)
        }

        private fun drawDirection(canvas: Canvas, x: Float, y: Float, u: Float) {
            val rotation = direction ?: return
            canvas.save()
            canvas.rotate(rotation, x, y)
            paint.color = text.color
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3*u
            paint.strokeJoin = Paint.Join.ROUND
            arrowPath.reset()
            arrowPath.moveTo(x, y+9*u)
            arrowPath.lineTo(x, y-9*u)
            arrowPath.moveTo(x-6*u, y-3*u)
            arrowPath.lineTo(x, y-9*u)
            arrowPath.lineTo(x+6*u, y-3*u)
            canvas.drawPath(arrowPath, paint)
            paint.style = Paint.Style.FILL
            canvas.restore()
        }
    }
}
