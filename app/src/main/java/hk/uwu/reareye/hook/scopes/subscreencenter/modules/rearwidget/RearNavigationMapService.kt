package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Binder
import android.os.Process
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.view.SurfaceControlViewHost
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.internal.hostbridge.IHookHostBridgeBootstrap

/** Hosts the navigation provider's own LiveAlert surface on the rear display. */
internal class RearNavigationMapService(
    context: Context, surface: SurfaceView, private val packageName: String,
    private val viewportScale: Float,
    private var visibleWidth: () -> Int,
) {
    companion object {
        private val packageType = SurfaceControlViewHost.SurfacePackage::class.java
        private val pageControl by lazy { packageType.getMethod("getSurfaceControl") }
        private val surfaceControl by lazy { SurfaceView::class.java.getMethod("getSurfaceControl") }
        private val copyPackage by lazy { packageType.getConstructor(packageType) }
        private var idle: Pair<String, RearNavigationMapService>? = null
        private var liveSources: Set<String>? = null

        fun obtain(key: String?, context: Context, surface: SurfaceView, packageName: String,
            scale: Float, visibleWidth: () -> Int): RearNavigationMapService {
            val previous = idle
            idle = null
            if (key != null && previous != null && previous.first == key && !previous.second.isClosed &&
                previous.second.packageName == packageName) {
                return previous.second.apply {
                    this.hostSurface = surface
                    this.visibleWidth = visibleWidth
                    parked = false
                    surface.holder.setFormat(PixelFormat.TRANSLUCENT)
                    surface.holder.addCallback(callback)
                }
            }
            previous?.second?.close()
            return RearNavigationMapService(context, surface, packageName, scale, visibleWidth)
        }

        fun retainSources(keys: Set<String>) {
            liveSources = keys.toSet()
            RearNavigationFrames.retainSources(keys)
            if (idle?.first !in keys) closeIdle()
        }

        fun closeIdle() {
            val previous = idle
            idle = null
            previous?.second?.close()
        }
    }
    private val context = context.applicationContext
    private var hostSurface: SurfaceView? = surface
    private var hostToken: IBinder? = null
    private var parked = false
    private var nativePage: SurfaceControlViewHost.SurfacePackage? = null
    val hasContent: Boolean get() = nativePage != null
    private var ambientRequested = false
    private var lastAmbientSent: Boolean? = null
    private var themeSequence = 0
    private var awakeMapDark: Boolean? = null
    private var ambientFrameReady = false

    fun frameCommitted() {
        if (packageName != "com.autonavi.minimap" || !ambientRequested) return
        ambientFrameReady = true
        send(10000107, card())
    }
    val expectedDark: Boolean? get() = when {
        packageName != "com.autonavi.minimap" -> null
        ambientRequested -> true
        else -> awakeMapDark
    }
    var onThemeChanged: (() -> Unit)? = null
    private val applyTheme = Runnable { if (!closed) sendTheme() }

    fun ambient(value: Boolean) {
        ambientRequested = value
        main.removeCallbacks(applyTheme)
        if (server != null && lastAmbientSent != value) main.postDelayed(applyTheme, 300L)
    }

    private fun sendTheme() {
        val value = ambientRequested
        if (lastAmbientSent == value) return
        if (send(10000103, Bundle().apply {
            putBoolean("ambient", value)
            putInt("themeSequence", ++themeSequence)
            putBundle("extra", card())
        })) {
            lastAmbientSent = value
            ambientFrameReady = false
        }
    }

    fun park(key: String?): Boolean {
        if (key == null || google || closed || liveSources?.contains(key) == false) return false
        val surface = hostSurface ?: return false
        closeIdle()
        onContent = null
        onNavigation = null
        onStatus = null
        onThemeChanged = null
        visible(false)
        main.removeCallbacks(applyTheme)
        if (packageName == "com.autonavi.minimap") send(10000107, card())
        parked = true
        surface.holder.removeCallback(callback)
        surface.clearChildSurfacePackage()
        hostSurface = null
        visibleWidth = { 0 }
        idle = key to this
        return true
    }
    private var connection: ServiceConnection? = null
    private var server: Messenger? = null
    private var bound = false
    private var requested = false
    private var contentAttached = false
    private data class Viewport(val width: Int, val height: Int, val visibleWidth: Int,
        val bounds: Rect, val orientation: Int)
    private var sentViewport: Viewport? = null
    private fun viewport(): Viewport? {
        val surface = hostSurface ?: return null
        return Viewport(surface.width, surface.height, visibleWidth(), Rect(safeBounds),
            surface.resources.configuration.orientation)
    }
    private var closed = false
    val isClosed: Boolean get() = closed
    private var visible = true
    private var safeBounds = Rect()
    private val cardKey = "536879184||0|${System.currentTimeMillis()}"
    var onContent: ((Boolean) -> Unit)? = null
    var onNavigation: ((Bundle) -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val google: Boolean get() = packageName == "com.google.android.apps.maps"
    private val responseTimeout = Runnable {
        onStatus?.invoke("暂未取得地图画面")
        close()
    }
    private val serverDeath = IBinder.DeathRecipient { main.post { close() } }
    private val discovery: Runnable = object : Runnable {
        override fun run() {
            if (closed || server != null) return
            context.sendBroadcast(Intent(RearGoogleNavigationHost.ACTION).setPackage("android")
                .putExtra("bundle", Bundle().apply { putBinder("binder", bootstrap) }))
            main.postDelayed(this, 1_000L)
        }
    }
    private val bootstrap = object : IHookHostBridgeBootstrap.Stub() {
        override fun onBinderReady(binder: IBinder?) {
            if (Binder.getCallingUid() != Process.SYSTEM_UID || binder == null) return
            main.post {
                if (!closed && server == null) runCatching {
                    binder.linkToDeath(serverDeath, 0)
                    server = Messenger(binder)
                    main.removeCallbacks(discovery)
                    request()
                }.onFailure { close() }
            }
        }
    }

    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == 21) {
            val page = message.data.getParcelable("SurfacePackage", SurfaceControlViewHost.SurfacePackage::class.java)
            if (closed) page?.release() else if (page != null) {
                main.removeCallbacks(responseTimeout)
                nativePage?.release()
                nativePage = page
                contentAttached = true
                if (!parked) attachNativePage()
                YLog.info("[RearNavigation] provider map surface attached")
            }
        }
        if (!closed && message.what == 10000101) onNavigation?.invoke(message.data)
        if (!closed && message.what == 10000104 &&
            message.data.getInt("themeSequence") == themeSequence &&
            message.data.getBoolean("ambient") == ambientRequested) {
            val time = message.data.getInt("mapTime", -1)
            if (!ambientRequested && time >= 0) awakeMapDark = time == 1
            onThemeChanged?.invoke()
        }
        if (!closed && google && message.what == 10000110) {
            main.removeCallbacks(responseTimeout)
            onContent?.invoke(true)
        }
        if (!closed && google && message.what == 10000111) {
            onStatus?.invoke(message.data.getString("reason") ?: "谷歌地图画面暂不可用")
            close()
        }
        true
    })
    private val callback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            if (closed) return
            if (requested && contentAttached) {
                attachNativePage()
            } else request()
        }
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            if (requested) resize() else request()
        }
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            if (google) close() else onContent?.invoke(false)
        }
    }

    init {
        surface.holder.setFormat(PixelFormat.TRANSLUCENT)
        surface.holder.addCallback(callback)
    }

    fun start() {
        if (closed) return
        if (nativePage != null) {
            attachNativePage()
            return
        }
        if (connection != null) return
        main.removeCallbacks(responseTimeout)
        main.postDelayed(responseTimeout, 8_000L)
        if (google) {
            main.post(discovery)
            return
        }
        val next = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (closed || connection !== this) return
                server = Messenger(binder)
                YLog.info("[RearNavigation] map service connected package=$packageName")
                request()
            }
            override fun onServiceDisconnected(name: ComponentName) {
                close()
            }
            override fun onBindingDied(name: ComponentName) { close() }
            override fun onNullBinding(name: ComponentName) {
                YLog.warn("[RearNavigation] map service returned no binder package=$packageName")
                close()
            }
        }
        connection = next
        bound = runCatching {
            val service = when (packageName) {
                "com.autonavi.minimap" -> "com.autonavi.minimap.immersenavi.AMapImmerseNaviService"
                "com.baidu.BaiduMap" -> "com.baidu.baidumaps.oem.mapshare.MapShareService"
                else -> return@runCatching false
            }
            context.bindService(Intent("hk.uwu.reareye.REAR_NAVIGATION_MAP")
                .setComponent(ComponentName(packageName, service)), next, Context.BIND_AUTO_CREATE)
        }.onFailure { YLog.warn("Rear navigation map binding unavailable", it) }.getOrDefault(false)
        if (!bound) connection = null
        if (!bound) close()
    }

    data class CaptureSource(val control: SurfaceControl, val scale: Float, val crop: Rect)

    fun captureSource(): CaptureSource? = runCatching {
        val surface = hostSurface ?: return@runCatching null
        val page = nativePage
        if (page != null) {
            val control = pageControl.invoke(page) as SurfaceControl
            // Capture in the remote window's local coordinates. Capturing the
            // embedding SurfaceView also samples the OEM pager's perspective.
            CaptureSource(control, viewportScale, Rect(0,
                (surface.height*.15f/viewportScale).toInt(),
                (visibleWidth().coerceIn(1, surface.width)/viewportScale).toInt(),
                (surface.height*1.15f/viewportScale).toInt()))
        } else {
            val control = surfaceControl.invoke(surface) as SurfaceControl
            CaptureSource(control, 1f, Rect(0, 0, visibleWidth().coerceIn(1, surface.width), surface.height))
        }
    }.getOrNull()

    private fun attachNativePage() {
        val surface = hostSurface ?: return
        val page = nativePage ?: return
        if (parked || surface.hostToken == null || !surface.holder.surface.isValid) return
        runCatching {
            // Keep the original package owned by this session. SurfaceView owns
            // its copy and can release it when the OEM replaces a widget View.
            val child = copyPackage.newInstance(page)
            surface.setChildSurfacePackage(child)
            val control = pageControl.invoke(page) as SurfaceControl
            SurfaceControl.Transaction().use { transaction ->
                SurfaceControl.Transaction::class.java.getMethod("setMatrix", SurfaceControl::class.java,
                    Float::class.javaPrimitiveType, Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
                    .invoke(transaction, control, viewportScale, 0f, 0f, viewportScale)
                transaction.setPosition(control, 0f, -surface.height * .15f).apply()
            }
            send(13, state(if (visible) 1 else 2))
            ambient(ambientRequested)
            if (packageName == "com.autonavi.minimap" && (visible || !ambientFrameReady))
                send(10000108, card())
            onContent?.invoke(true)
        }.onFailure {
            YLog.warn("Rear navigation retained surface attachment failed", it)
            close()
        }
    }

    fun bounds(bounds: Rect) {
        if (safeBounds == bounds) return
        safeBounds = Rect(bounds)
        if (requested) resize()
    }

    fun visible(value: Boolean) {
        if (visible == value) return
        visible = value
        send(13, state(if (value) 1 else 2))
    }

    private fun request() {
        val surface = hostSurface ?: return
        if (closed || requested || surface.width <= 0 || surface.height <= 0) return
        val token = surface.hostToken ?: return
        hostToken = token
        requested = runCatching { send(11, Bundle().apply {
            putBinder("hostToken", token)
            putInt("rearMapDisplayId", surface.display?.displayId ?: 1)
            putBundle("extra", display())
            if (google) {
                putParcelable("rearMapSurface", surfaceControl.invoke(surface) as SurfaceControl)
                googleDisplay(this)
            }
        }) }.onFailure {
            YLog.warn("Rear navigation surface request failed", it)
            onStatus?.invoke("导航画面连接失败")
            close()
        }.getOrDefault(false)
        if (requested) {
            sentViewport = viewport()
            main.removeCallbacks(responseTimeout)
            main.postDelayed(responseTimeout, 8_000L)
        }
    }

    private fun resize() {
        val next = viewport() ?: return
        if (next == sentViewport) return
        runCatching { send(12, Bundle().apply {
            putInt("event", 1)
            putBundle("extra", display())
            if (google) googleDisplay(this)
        }) }.onSuccess { if (it) sentViewport = next }
            .onFailure { YLog.warn("Rear navigation surface resize failed", it) }
    }

    private fun googleDisplay(data: Bundle) {
        val surface = hostSurface ?: return
        data.putInt("rearMapWidth", surface.width)
        data.putInt("rearMapHeight", surface.height)
        data.putInt("rearMapVisibleWidth", visibleWidth())
        data.putParcelable("rearMapBounds", Rect((safeBounds.left*viewportScale).toInt(),
            (safeBounds.top*viewportScale-surface.height*.15f).toInt(),
            (safeBounds.right*viewportScale).toInt(),
            (safeBounds.bottom*viewportScale-surface.height*.15f).toInt()))
    }

    private fun card() = Bundle().apply {
        putBundle("livealert.immersive.card", Bundle().apply { putString("cardKey", cardKey) })
    }

    private fun display() = card().apply {
        val surface = hostSurface ?: return@apply
        val safe = safeBounds.takeUnless { it.isEmpty } ?: Rect(0, 0, surface.width, surface.height)
        val half = (safe.width() * 0.06f).toInt().coerceAtLeast(1)
        putBundle("livealert.immersive.display", Bundle().apply {
            putInt("width", (surface.width/viewportScale).toInt())
            putInt("height", (surface.height*1.15f/viewportScale).toInt())
            putInt("orientation", surface.resources.configuration.orientation)
            putParcelable("infoBounds", safe)
            putParcelable("criticalBounds", Rect(safe.centerX() - half, safe.centerY() - half,
                safe.centerX() + half, safe.centerY() + half))
        })
    }

    private fun state(value: Int) = Bundle().apply {
        putInt("state", value)
        putBundle("extra", card())
    }

    private fun send(what: Int, data: Bundle): Boolean {
        val remote = server ?: return false
        return runCatching {
            remote.send(Message.obtain(null, what).apply { this.data = data; replyTo = replies })
            true
        }.onFailure { YLog.warn("Rear navigation map message $what failed", it) }.getOrDefault(false)
    }

    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(responseTimeout)
        main.removeCallbacks(discovery)
        main.removeCallbacks(applyTheme)
        ambientRequested = false
        sendTheme()
        send(14, Bundle().apply {
            putBinder("hostToken", hostToken)
            putBundle("extra", card())
        })
        hostSurface?.let { surface ->
            surface.holder.removeCallback(callback)
            surface.clearChildSurfacePackage()
        }
        hostSurface = null
        hostToken = null
        visibleWidth = { 0 }
        nativePage?.release()
        nativePage = null
        if (bound) connection?.let { runCatching { context.unbindService(it) } }
        connection = null
        if (google) server?.binder?.let { runCatching { it.unlinkToDeath(serverDeath, 0) } }
        server = null
        bound = false
        onContent?.invoke(false)
        onContent = null
        onNavigation = null
        onStatus = null
        onThemeChanged = null
    }
}
