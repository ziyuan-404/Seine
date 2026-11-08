package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Point
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.doOnPreDraw
import hk.uwu.reareye.hook.support.YLog
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Element
import kotlin.math.roundToInt

/** Execute the ROM timer scene, including its own geometry, digit motion and AOD. */
internal class NativeRearTimerScene private constructor(
    context: Context, val recording: Boolean, path: File,
) : FrameLayout(context) {
    companion object {
        const val TAG = "rear_native_timer_scene"

        fun bind(context: Context, root: View, notification: Notification, recording: Boolean,
                 execute: (Int) -> Unit): Boolean = runCatching {
            val existing = root.findViewWithTag<NativeRearTimerScene>(TAG)
            val scene = existing?.takeIf { it.recording == recording } ?: NativeRearTimerScene(
                context, recording, template(context, recording),
            ).also { scene ->
                val container = root.findViewById<View>(context.resources.getIdentifier("notification_container", "id", context.packageName))
                container?.visibility = View.GONE
                (root as ViewGroup).addView(scene, ViewGroup.LayoutParams(-1, -1))
            }
            scene.execute = execute
            scene.update(notification)
            true
        }.onFailure { YLog.warn("Native rear timer scene unavailable", it) }.getOrDefault(false)

        fun aod(root: View, asleep: Boolean): Boolean {
            val scene = root.findViewWithTag<NativeRearTimerScene>(TAG) ?: return false
            scene.aod(asleep)
            return true
        }

        private fun template(context: Context, recording: Boolean): File {
            val version = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            val cache = File(context.cacheDir, "reareye-native-${if (recording) "recorder" else "stopwatch"}-$version-v15.zip")
            if (cache.isFile) return cache
            val entries = linkedMapOf<String, ByteArray>()
            ZipInputStream(context.assets.open("smart_assistant/timer")).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                }
            }
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(ByteArrayInputStream(requireNotNull(entries["manifest.xml"])))
            // Countdown's minute alarm is sufficient for its AOD layout. An
            // elapsed timer must keep second accuracy even when the renderer
            // lowers its frame rate or resumes from an inactive pager page.
            document.documentElement.setAttribute("useVariableUpdater", "HyperMaterial,DateTime.Second")
            // OEM Layer creates a separately invalidated Android View for each
            // material surface. Embedded pager cards can then composite digits
            // and controls from different frames. Keep the OEM scene/geometry,
            // but render the adapted card atomically on one MAML canvas.
            val layers = document.getElementsByTagName("Layer")
            (0 until layers.length).map { layers.item(it) as Element }.forEach { old ->
                val group = document.createElement("Group")
                val attrs = old.attributes
                for (index in 0 until attrs.length) {
                    val attr = attrs.item(index)
                    if (attr.nodeName !in setOf("blurRadius", "blurColors", "colorModes", "reuseContainer", "frameRate"))
                        group.setAttribute(attr.nodeName, attr.nodeValue)
                }
                while (old.hasChildNodes()) group.appendChild(old.firstChild)
                old.parentNode.replaceChild(group, old)
            }
            val all = document.getElementsByTagName("*")
            val elements = (0 until all.length).map { all.item(it) as Element }
            elements.forEach { element ->
                // Keep the factory countdown renderer; elapsed time grows from the
                // same wall-clock base instead of subtracting from a deadline.
                val attrs = element.attributes
                for (index in 0 until attrs.length) {
                    val attr = attrs.item(index)
                    attr.nodeValue = attr.nodeValue
                        .replace("(num(@timer_when) - num(@timer_system_current))", "(num(@timer_system_current) - num(@timer_when))")
                        .replace("(num(@timer_when) - #time_sys)", "(#time_sys - num(@timer_when))")
                        .replace("(num(@timer_when) - #req_suspend_time)", "(#req_suspend_time - num(@timer_when))")
                        .replace("ceil((max(", "int((max(")
                }
                if (element.tagName == "Group" && element.getAttribute("y").contains("#timer_total"))
                    element.parentNode?.removeChild(element)
                if (element.tagName == "Var") {
                    // Count up: the next digit rises from below and the old digit
                    // leaves above. Reverse both translation and rotation together.
                    val name = element.getAttribute("name")
                    if (name.startsWith("pre_")) {
                        val radix = if (name.contains("minute_ten_num") || name.contains("second_ten_num")) 6 else 10
                        element.setAttribute("expression", element.getAttribute("expression")
                            .replace("+ 1)", "+ ${radix - 1})").replace("% 3)", "% 10)"))
                    }
                    if (name.startsWith("appear_y_start_")) element.setAttribute("expression", element.getAttribute("expression").replace("((- 1) * ", "(") )
                    if (name.startsWith("disappear_y_end_")) element.setAttribute("expression", "((- 1) * ${element.getAttribute("expression")})")
                    if (name.startsWith("appear_rotation_x_start_")) element.setAttribute("expression", "80")
                    if (name.startsWith("disappear_rotation_x_end_")) element.setAttribute("expression", "(- 80)")
                }
                // Keep the native primary control at the left in both states:
                // pause/continue. The right control remains lap/end.
                if (!recording && element.tagName == "Image" && element.getAttribute("src") == "assets/x.webp") {
                    element.removeAttribute("src")
                    element.setAttribute("srcExp", "ifelse(eq(#maml_type,(- 1)),'assets/reareye_mark.png','assets/x.webp')")
                }
                if (!recording && element.hasAttribute("contentDescriptionExp")) {
                    val description = element.getAttribute("contentDescriptionExp")
                    when {
                        description.contains("end_countdown") -> element.setAttribute("contentDescriptionExp", "ifelse(eq(#maml_type,(- 1)),'标记','结束')")
                        description.contains("pause_countdown") -> element.setAttribute("contentDescriptionExp", "'暂停'")
                        description.contains("continue_countdown") -> element.setAttribute("contentDescriptionExp", "'继续'")
                    }
                }
                if (element.tagName == "IntentCommand") {
                    val action = element.getAttribute("action")
                    val command = document.createElement("ExternCommand")
                    command.setAttribute("command", "reareye_timer_action")
                    command.setAttribute("strPara", "'${if (action.contains("pause")) "pause" else if (action.contains("continue")) "resume" else "secondary"}'")
                    element.parentNode.replaceChild(command, element)
                }
                // No tracker or screen-wake requests are needed for a mirror card.
                if (element.tagName == "ExternCommand" && element.getAttribute("command") in setOf("oneTrack", "userActivity"))
                    element.parentNode?.removeChild(element)
                if (recording && element.tagName == "Var" && element.getAttribute("name") == "remain_text_color")
                    element.setAttribute("expression", "'#A67A66'")
                if (recording && element.hasAttribute("expression"))
                    element.setAttribute("expression", element.getAttribute("expression").replace("#E6D4E9FF", "#E6FFE1D4"))
            }
            fun renderIcon(drawable: android.graphics.drawable.Drawable, crop: Boolean = false,
                           preserveAspectRatio: Boolean = false): ByteArray {
                val bitmap = Bitmap.createBitmap(156, 156, Bitmap.Config.ARGB_8888)
                if (preserveAspectRatio && drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0) {
                    val scale = 156f / maxOf(drawable.intrinsicWidth, drawable.intrinsicHeight)
                    val width = (drawable.intrinsicWidth * scale).roundToInt()
                    val height = (drawable.intrinsicHeight * scale).roundToInt()
                    val left = (156 - width) / 2; val top = (156 - height) / 2
                    drawable.setBounds(left, top, left + width, top + height)
                } else drawable.setBounds(0, 0, 156, 156)
                drawable.draw(Canvas(bitmap))
                val result = if (crop) Bitmap.createBitmap(bitmap, 31, 31, 94, 94) else bitmap
                val bytes = ByteArrayOutputStream().also { result.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                if (result !== bitmap) result.recycle()
                bitmap.recycle()
                return bytes
            }
            if (!recording) {
                // The original secondary material layer colours a white alpha
                // mask blue. Preserve that contrast when drawing on one canvas.
                entries["assets/btnBg.webp"]?.let { bytes ->
                    val source = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        colorFilter = android.graphics.PorterDuffColorFilter(0xff2d4f91.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)
                    }
                    Canvas(bitmap).drawBitmap(source, 0f, 0f, paint)
                    entries["assets/btnBg.webp"] = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    source.recycle(); bitmap.recycle()
                }
                val clock = context.createPackageContext("com.android.deskclock", 0)
                val lapId = clock.resources.getIdentifier("fab_lap_icon", "drawable", clock.packageName)
                val lap = requireNotNull(clock.getDrawable(lapId)).mutate().apply { setTint(android.graphics.Color.WHITE) }
                entries["assets/reareye_mark.png"] = renderIcon(lap)
                val iconId = clock.resources.getIdentifier("stopwatch_notification_icon", "drawable", clock.packageName)
                entries["assets/sandglass.webp"] = renderIcon(requireNotNull(clock.getDrawable(iconId)), preserveAspectRatio = true)
                // Stopwatch has no sandglass animation. Keep the factory header's
                // anchor and use the clock's own notification icon instead.
                elements.filter { it.tagName == "Lottie" && it.getAttribute("name") == "sandglass_lottie" }.forEach { old ->
                    val image = document.createElement("Image")
                    listOf("x", "y", "w", "h", "align").forEach { image.setAttribute(it, old.getAttribute(it)) }
                    image.setAttribute("src", "assets/sandglass.webp")
                    old.parentNode.replaceChild(image, old)
                }
                elements.filter { it.tagName == "LottieCommand" && it.getAttribute("target") == "sandglass_lottie" }
                    .forEach { it.parentNode?.removeChild(it) }
            }
            if (recording) {
                val plugin = context.createPackageContext("miui.systemui.plugin", 0)
                val night = android.content.res.Configuration(plugin.resources.configuration).apply {
                    uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or android.content.res.Configuration.UI_MODE_NIGHT_YES
                }
                val art = plugin.createConfigurationContext(night)
                // The single-canvas card has no material colour filter. Use the
                // OEM dark-surface waveform so its bars remain visible here.
                val raw = art.resources.getIdentifier("voice_wave_big", "raw", art.packageName)
                require(raw != 0) { "Native recorder waveform unavailable" }
                entries["assets/sandglass_lottie_normal.json"] = art.resources.openRawResource(raw).use { it.readBytes() }
                val endId = art.resources.getIdentifier("record_end_v3", "drawable", art.packageName)
                if (endId != 0) {
                    entries["assets/reareye_record_stop.png"] = renderIcon(requireNotNull(art.getDrawable(endId)))
                    elements.filter { it.tagName == "Group" }.forEach { group ->
                        when (group.getAttribute("name")) {
                            "go_close_btn" -> {
                                (0 until group.childNodes.length).map { group.childNodes.item(it) }.filterIsInstance<Element>()
                                    .filter { it.tagName == "Image" }.forEach { it.setAttribute("alpha", "0") }
                                group.appendChild(document.createElement("Image").apply {
                                    setAttribute("w", "(56 * #scale)"); setAttribute("h", "(56 * #scale)")
                                    setAttribute("align", "center"); setAttribute("alignV", "center")
                                    setAttribute("src", "assets/reareye_record_stop.png")
                                })
                            }
                            "go_close_btn_circle", "go_close_x_btn" -> group.setAttribute("visibility", "0")
                        }
                    }
                }
                listOf("assets/bg.webp", "assets/bg_of_aod.webp", "assets/ripple.webp").forEach { name ->
                    val source = android.graphics.BitmapFactory.decodeByteArray(requireNotNull(entries[name]), 0, entries.getValue(name).size)
                    val bitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
                            0f, 0f, 1f, 0f, 0f, 0f, .8f, 0f, 0f, 0f,
                            1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
                    }
                    Canvas(bitmap).drawBitmap(source, 0f, 0f, paint)
                    entries[name] = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                    source.recycle(); bitmap.recycle()
                }
                val waveId = art.resources.getIdentifier("voice_wave_big", "drawable", art.packageName)
                entries["assets/sandglass.webp"] = renderIcon(requireNotNull(art.getDrawable(waveId)))
                elements.filter { it.tagName == "Lottie" && it.getAttribute("name") == "sandglass_lottie" }.forEach { lottie ->
                    lottie.setAttribute("visibility", "0")
                    val paused = document.createElement("Image")
                    listOf("x", "y", "w", "h", "align").forEach { paused.setAttribute(it, lottie.getAttribute(it)) }
                    paused.setAttribute("src", "assets/sandglass.webp")
                    paused.setAttribute("visibility", "eq(#maml_type,(- 2))")
                    lottie.parentNode.insertBefore(paused, lottie)
                }
                elements.filter { it.tagName == "LottieCommand" && it.getAttribute("target") == "sandglass_lottie" }
                    .forEach { it.parentNode?.removeChild(it) }
            }
            // An empty deadline is harmless for countdown (clamped to zero),
            // but reversing it would show time since 1970 during root init.
            // Prime the factory temp digits before enabling its per-digit
            // threshold triggers. Keep every native animation/function intact.
            val currentElements = document.getElementsByTagName("*")
            val live = (0 until currentElements.length).map { currentElements.item(it) as Element }
            live.filter { it.tagName == "Var" && it.getAttribute("expression").contains("@timer_when") }.forEach { variable ->
                variable.setAttribute("expression", "ifelse(eq(num(@timer_when),0),0,(${variable.getAttribute("expression")}))")
            }
            live.filter { it.tagName == "Var" && it.getAttribute("name").startsWith("watch") &&
                it.getAttribute("name").endsWith("_JRIuLn5n") }.forEach { watch ->
                val triggers = watch.getElementsByTagName("Trigger")
                for (index in 0 until triggers.length) (triggers.item(index) as Element)
                    .setAttribute("condition", "#rear_timer_initialized")
            }
            live.filter { it.tagName == "Group" && it.getAttribute("name") in setOf("desk_group", "aod_group") }.forEach { group ->
                group.setAttribute("alpha", "(${group.getAttribute("alpha")} * #rear_timer_initialized)")
            }
            val prime = document.createElement("Var").apply {
                setAttribute("name", "rear_timer_initialized"); setAttribute("type", "number")
                setAttribute("expression", "ifelse(eq(num(@timer_when),0),0,1)"); setAttribute("threshold", "1")
            }
            val ready = document.createElement("Trigger").apply { setAttribute("condition", "#rear_timer_initialized") }
            live.filter { it.tagName == "Var" && it.getAttribute("name").startsWith("temp_") &&
                it.getAttribute("name").endsWith("_num_JRIuLn5n") }.forEach { temp ->
                ready.appendChild(document.createElement("VariableCommand").apply {
                    setAttribute("name", temp.getAttribute("name")); setAttribute("type", "number")
                    setAttribute("expression", "#${temp.getAttribute("name").removePrefix("temp_")}")
                })
            }
            prime.appendChild(ready)
            document.documentElement.appendChild(prime)
            entries["manifest.xml"] = ByteArrayOutputStream().also {
                TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(it))
            }.toByteArray()
            ZipOutputStream(cache.outputStream()).use { zip -> entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            } }
            return cache
        }
    }

    private val type = context.classLoader.loadClass("com.miui.maml.component.MamlView")
    private val configType = context.classLoader.loadClass("com.miui.maml.component.MamlView\$Config")
    private val config = configType.getConstructor().newInstance().also { config ->
        val size = Point()
        @Suppress("DEPRECATION")
        context.display?.getRealSize(size)
        configType.getField("path").set(config, path.path)
        configType.getField("mode").setInt(config, 1)
        configType.getField("viewWidth").setInt(config, size.x)
        configType.getField("viewHeight").setInt(config, size.y)
        configType.getField("flags").setInt(config, configType.getField("FLAG_WIDE_DEVICE").getInt(null))
        configType.getField("touchable").setBoolean(config, true)
    }
    private fun createMaml() = type.getConstructor(Context::class.java, configType).newInstance(context, config) as View
    private var maml = createMaml()
    private var waveform: ImageView? = null
    private var waveformElement: Any? = null
    private var waveformPlaying = false
    private var finished = false
    private var lastPayload: String? = null
    private var sourceBase = Long.MIN_VALUE
    private var sourceRunning: Boolean? = null
    private var sourceCurrent = Long.MIN_VALUE
    private var execute: (Int) -> Unit = {}
    private var asleep: Boolean? = null
    private val listenerType = context.classLoader.loadClass("com.miui.maml.component.MamlView\$OnExternCommandListener")
    private val listener = Proxy.newProxyInstance(context.classLoader, arrayOf(listenerType)) { proxy, method, args ->
        when (method.name) {
            "onCommand" -> {
                if (args?.getOrNull(0) == "reareye_timer_action" && asleep != true) post {
                    val secondary = args.getOrNull(2) == "secondary"
                    execute(if (recording) { if (secondary) 1 else 0 } else { if (secondary) 0 else 1 })
                }
                null
            }
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "toString" -> "Rear native timer actions"
            else -> null
        }
    }

    init {
        tag = TAG
        addView(maml, LayoutParams(-1, -1))
        bindWaveform()
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionWaveform() }
        type.getMethod("setOnExternCommandListener", listenerType).invoke(maml, listener)
    }

    private fun bindWaveform() {
        if (!recording) return
        runCatching {
            val root = type.getDeclaredField("mRoot").apply { isAccessible = true }.get(maml)
            val element = requireNotNull(root.javaClass.getMethod("findElement", String::class.java).invoke(root, "sandglass_lottie"))
            val drawable = element.javaClass.getDeclaredField("mDrawable").apply { isAccessible = true }.get(element) as Drawable
            drawable.javaClass.getField("j").setBoolean(drawable, true)
            drawable.javaClass.getMethod("c").invoke(drawable)
            waveformElement = element
            // Let Android own this drawable's bounds and invalidation callback.
            // The MAML scene remains the authority for its anchor and state.
            waveform = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageDrawable(drawable)
                visibility = View.GONE
            }.also { addView(it, LayoutParams(0, 0)) }
            doOnPreDraw { positionWaveform(); syncWaveform() }
        }.onFailure { YLog.warn("Native recorder waveform renderer unavailable", it) }
    }

    private fun positionWaveform() {
        val image = waveform ?: return
        val element = waveformElement ?: return
        fun dimension(method: String) = (element.javaClass.getMethod(method).invoke(element) as Number).toFloat().roundToInt()
        val width = dimension("getWidth"); val height = dimension("getHeight")
        if (width <= 0 || height <= 0) return
        val left = dimension("getAbsoluteLeft"); val top = dimension("getAbsoluteTop")
        val params = image.layoutParams as LayoutParams
        if (params.width == width && params.height == height && params.leftMargin == left && params.topMargin == top) return
        image.layoutParams = LayoutParams(width, height).apply { leftMargin = left; topMargin = top }
    }

    private fun syncWaveform() {
        val image = waveform ?: return
        val element = waveformElement ?: return
        val playing = sourceRunning == true && asleep != true && isAttachedToWindow && isShown
        image.visibility = if (playing) View.VISIBLE else View.GONE
        if (playing == waveformPlaying) return
        element.javaClass.getMethod(if (playing) "playAnimation" else "pauseAnimation").invoke(element)
        waveformPlaying = playing
    }

    private fun string(name: String, value: String) = type.getMethod("putVariableString", String::class.java, String::class.java).invoke(maml, name, value)
    private fun command(name: String) = type.getMethod("sendCommand", String::class.java).invoke(maml, name)

    private fun update(notification: Notification) {
        val timing = NativeRearStopwatchCard.parameters(notification)
        val running = timing?.let { it.optInt("timerType", 1) == 1 }
            ?: notification.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, true)
        val now = System.currentTimeMillis()
        val current = timing?.optLong("timerSystemCurrent", now) ?: now
        val base = timing?.optLong("timerWhen", notification.`when`) ?: notification.`when`
        // Use the clock's own wall-clock base verbatim, exactly as its service
        // and front focus card do. Never start a second elapsed-time source or
        // discard corrections while running. Lap changes actions, not this base.
        if (sourceBase == base && sourceRunning == running && (running || sourceCurrent == current)) return
        sourceBase = base; sourceRunning = running; sourceCurrent = current
        val config = JSONObject().put("timer_type", 0).put("maml_type", if (running) -1 else -2)
            .put("timer_when", base.toString())
            .put("timer_system_current", current.toString())
            .put("timer_total", 3600000)
            .put("timer_rear_content", if (recording) { if (running) "录音中" else "录音已暂停" } else "秒表")
            .put("timer_alert_title", "")
        val payload = JSONObject().put("maml_config", config).toString()
        lastPayload = payload
        string("miui.rear.param", requireNotNull(lastPayload))
        command("notification_received")
        syncWaveform()
    }

    private fun aod(value: Boolean) {
        if (asleep == value) return
        asleep = value
        string("aod_desk_state", if (value) "1" else "0")
        command(if (value) "enterAod" else "exitAod")
        syncWaveform()
    }

    override fun onAttachedToWindow() {
        // A pager may retain the outer RemoteViews root after detaching it. A
        // finished MAML root cannot resume; seed its replacement before drawing.
        if (finished) {
            removeView(maml)
            waveform?.let(::removeView)
            waveform = null; waveformElement = null; waveformPlaying = false
            maml = createMaml()
            type.getMethod("setOnExternCommandListener", listenerType).invoke(maml, listener)
            lastPayload?.let { string("miui.rear.param", it); command("notification_received") }
            val wasAsleep = asleep == true
            asleep = null
            aod(wasAsleep)
            addView(maml, LayoutParams(-1, -1))
            bindWaveform()
            finished = false
        }
        super.onAttachedToWindow()
        type.getMethod("onResume").invoke(maml)
        syncWaveform()
    }
    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        syncWaveform()
    }
    override fun onDetachedFromWindow() {
        waveformElement?.javaClass?.getMethod("pauseAnimation")?.invoke(waveformElement)
        waveformPlaying = false
        type.getMethod("onDestroy").invoke(maml)
        waveform?.setImageDrawable(null)
        finished = true
        super.onDetachedFromWindow()
    }
}
