package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Bundle
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.SystemClock
import android.os.PowerManager
import android.view.Display
import android.view.SurfaceControl
import hk.uwu.reareye.hook.activityhost.ActivityHostReflection as Reflect
import hk.uwu.reareye.hook.activityhost.ActivityHostTaskConfigurationAdapter as Server
import hk.uwu.reareye.hook.hostbridge.HookHostBridgeBootstrapRegistry
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.internal.hostbridge.IHookHostBridgeBootstrap
import kotlin.math.max

/** Renders the existing Google navigation task and restores it when the rear client leaves. */
internal class RearGoogleNavigationHost(private val context: Context) {
    companion object {
        const val ACTION = "hk.uwu.reareye.REAR_GOOGLE_NAVIGATION"
        private const val GOOGLE = "com.google.android.apps.maps"
    }

    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var closed = false
    private val endpoint = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (closed || context.packageManager.getPackagesForUid(message.sendingUid)
                ?.contains("com.xiaomi.subscreencenter") != true) return@Handler true
        runCatching {
            val data = message.data
            val extra = data.getBundle("extra")
            val key = extra?.getBundle("livealert.immersive.card")?.getString("cardKey")
            when (message.what) {
                11 -> {
                    val parent = data.getParcelable("rearMapSurface", SurfaceControl::class.java)
                    val reply = message.replyTo
                    if (key != null && parent?.isValid == true && reply != null) {
                        session?.close()
                        Session(key, parent, reply, data).also { session = it }.start()
                    } else parent?.release()
                }
                12 -> if (session?.key == key) session?.layout(data)
                14 -> if (session?.key == key) session?.close()
            }
        }.onFailure { YLog.warn("Google navigation host message failed", it) }
        true
    })
    private val bridge = HookHostBridgeBootstrapRegistry(ACTION, { endpoint.binder })
    private val registration = object : Runnable {
        override fun run() {
            if (closed) return
            if (bridge.register(context))
                YLog.info("[RearNavigation] Google original-task renderer available")
            else main.postDelayed(this, 2_000L)
        }
    }

    fun register() {
        main.post(registration)
    }

    fun frontRequested(taskId: Int? = null) {
        main.post {
            session?.takeIf { taskId == null || it.taskId == taskId }?.returnToFront()
        }
    }

    fun close() {
        closed = true
        main.removeCallbacks(registration)
        bridge.unregister()
        if (Looper.myLooper() == Looper.getMainLooper()) session?.close()
        else main.post { session?.close() }
    }

    private inner class Session(
        val key: String,
        private val parent: SurfaceControl,
        private val reply: Messenger,
        initial: Bundle,
    ) {
        var taskId = -1
            private set
        private var originalDisplay = 0
        private var virtual: VirtualDisplay? = null
        private var images: ImageReader? = null
        private var displayWakeLock: PowerManager.WakeLock? = null
        private var mirror: SurfaceControl? = null
        private var taskControl: SurfaceControl? = null
        private var providerSurface: SurfaceControl? = null
        private var providerGeneration = ""
        private var mapServer: Messenger? = null
        private var sourceAnchorX = .5f
        private var sourceAnchorY = .5f
        private var ended = false
        private var outputWidth = initial.getInt("rearMapWidth")
        private var outputHeight = initial.getInt("rearMapHeight")
        private var visibleWidth = initial.getInt("rearMapVisibleWidth")
        private var anchor = initial.getParcelable("rearMapBounds", Rect::class.java) ?: Rect()
        private var sourceWidth = 1920
        private var sourceHeight = 1080
        private var changingDisplay = false
        private var frontGraceUntil = 0L
        private var attachAttempts = 0
        private val death = IBinder.DeathRecipient { main.post { close() } }
        private val mapDeath = IBinder.DeathRecipient { main.post { fail("谷歌地图进程已停止", IllegalStateException("Map process died")) } }
        private val bootstrap = object : IHookHostBridgeBootstrap.Stub() {
            override fun onBinderReady(binder: IBinder?) {
                if (context.packageManager.getPackagesForUid(Binder.getCallingUid())?.contains(GOOGLE) != true || binder == null) return
                main.post {
                    if (!ended && mapServer == null) runCatching {
                        binder.linkToDeath(mapDeath, 0)
                        mapServer = Messenger(binder)
                    }.onFailure { fail("谷歌地图图层连接失败", it) }
                }
            }
        }
        private val mapReplies = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (context.packageManager.getPackagesForUid(message.sendingUid)?.contains(GOOGLE) != true) return@Handler true
            val data = message.data
            val surface = data.getParcelable("surface", SurfaceControl::class.java)
            if (!ended && message.what == 10000120 && surface?.isValid == true) {
                if (providerGeneration != data.getString("generation")) {
                    providerSurface?.release()
                    providerSurface = surface
                    providerGeneration = data.getString("generation").orEmpty()
                } else surface.release()
                sourceWidth = data.getInt("width")
                sourceHeight = data.getInt("height")
                sourceAnchorX = data.getFloat("anchorX", .5f)
                sourceAnchorY = data.getFloat("anchorY", .5f)
            } else surface?.release()
            true
        })
        private val service by lazy { requireNotNull(Server.service(javaClass.classLoader)) }
        private val root by lazy { requireNotNull(Server.field(service, "mRootWindowContainer")) }
        private val lock by lazy { requireNotNull(Server.field(service, "mGlobalLock")) }
        private val tick = object : Runnable {
            override fun run() {
                if (ended) return
                if (changingDisplay) {
                    main.postDelayed(this, 500L)
                    return
                }
                runCatching {
                    val task = findTask() ?: error("Navigation task has ended")
                    val displayId = Server.displayId(task)
                    if (virtual != null && displayId != virtual?.display?.displayId) {
                        releaseVirtualDisplay()
                        frontGraceUntil = SystemClock.uptimeMillis() + 5_000L
                    }
                    if (displayId != originalDisplay && displayId != virtual?.display?.displayId)
                        error("Navigation task was moved by the user")
                    val front = context.getSystemService(DisplayManager::class.java).getDisplay(0)
                    val info = context.getSystemService(ActivityManager::class.java).getRunningTasks(100)
                        .firstOrNull { it.taskId == taskId }
                    if (virtual == null && !changingDisplay && SystemClock.uptimeMillis() >= frontGraceUntil &&
                        (front?.state != Display.STATE_ON || info?.isVisible != true)) startVirtualDisplay()
                    else {
                        requestMapLayer()
                        updateMirror(task)
                    }
                }.onFailure { fail("谷歌导航画面已停止", it) }
                if (!ended) main.postDelayed(this, 500L)
            }
        }

        fun start() {
            runCatching {
                require(outputWidth in 2..4096 && outputHeight in 2..4096 && visibleWidth in 2..outputWidth)
                val info = context.getSystemService(ActivityManager::class.java).getRunningTasks(100)
                    .firstOrNull { it.baseActivity?.packageName == GOOGLE && it.topActivity?.packageName == GOOGLE }
                    ?: error("Open the current Google navigation page first")
                taskId = info.taskId
                val task = findTask() ?: error("Navigation task is unavailable")
                originalDisplay = requireNotNull(Server.displayId(task))
                require(originalDisplay == 0) { "Google navigation is already on another display" }
                reply.binder.linkToDeath(death, 0)
                context.sendBroadcast(Intent(RearGoogleNavigationViewport.ACTION).setPackage(GOOGLE)
                    .putExtra("bundle", Bundle().apply { putBinder("binder", bootstrap) }))
                val front = context.getSystemService(DisplayManager::class.java).getDisplay(0)
                if (front?.state != Display.STATE_ON || !info.isVisible) startVirtualDisplay() else attach()
            }.onFailure { fail("请先在前屏打开谷歌导航页面", it) }
        }

        private fun startVirtualDisplay() {
            changingDisplay = true
            createVirtualDisplay()
            main.postDelayed({
                if (!ended) runCatching {
                    synchronized(lock) {
                        val task = findTask() ?: error("Navigation task disappeared")
                        Reflect.invoke(task, "setWindowingMode", 1)
                        Reflect.invoke(task, "setBounds", null)
                    }
                    Reflect.invoke(service, "moveRootTaskToDisplay", taskId, requireNotNull(virtual).display.displayId)
                    main.postDelayed({
                        if (!ended) {
                            changingDisplay = false
                            if (mirror == null) attach()
                        }
                    }, 750L)
                }.onFailure { fail("无法启动谷歌原版地图画面", it) }
            }, 250L)
        }

        private fun createVirtualDisplay() {
            sourceWidth = 1920
            sourceHeight = 1080
            val manager = context.getSystemService(DisplayManager::class.java)
            val flags = listOf("VIRTUAL_DISPLAY_FLAG_PUBLIC", "VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY",
                "VIRTUAL_DISPLAY_FLAG_TRUSTED", "VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP",
                "VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED")
                .fold(0) { result, name -> result or DisplayManager::class.java.getField(name).getInt(null) }
            val reader = ImageReader.newInstance(sourceWidth, sourceHeight, PixelFormat.RGBA_8888, 2)
            images = reader
            reader.setOnImageAvailableListener({ source -> runCatching { source.acquireLatestImage()?.close() } }, main)
            virtual = manager.createVirtualDisplay("Seine Google navigation", sourceWidth, sourceHeight, 240,
                reader.surface, flags) ?: error("Navigation display creation failed")
            @Suppress("DEPRECATION")
            val power = context.createDisplayContext(requireNotNull(virtual).display)
                .getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            displayWakeLock = power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP, "Seine:GoogleNavigationRender").apply { acquire() }
            val wm = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "window")
            if (wm != null) Reflect.invoke(wm, "setIgnoreOrientationRequest", requireNotNull(virtual).display.displayId, true)
        }

        private fun findTask(): Any? = synchronized(lock) {
            val task = Reflect.invoke(root, "anyTaskForId", taskId) ?: return@synchronized null
            val top = Server.call(task, "getTopMostActivity") ?: return@synchronized null
            check(Server.component(top)?.substringBefore('/') == GOOGLE) { "Task identity changed" }
            task
        }

        private fun attach() {
            runCatching {
                if (mapServer == null && attachAttempts % 5 == 0)
                    context.sendBroadcast(Intent(RearGoogleNavigationViewport.ACTION).setPackage(GOOGLE)
                        .putExtra("bundle", Bundle().apply { putBinder("binder", bootstrap) }))
                val task = findTask() ?: error("Navigation task disappeared")
                val top = synchronized(lock) { Server.call(task, "getTopMostActivity") }
                requestMapLayer()
                if (providerSurface?.isValid != true || top == null || Server.field(top, "mVisibleRequested") != true ||
                    Server.field(top, "allDrawn") == false) {
                    check(++attachAttempts < 25) { "Navigation window did not become visible" }
                    main.postDelayed({ if (!ended) attach() }, 200L)
                    return
                }
                updateMirror(task)
                reply.send(Message.obtain(null, 10000110))
                YLog.info("[RearNavigation] Google original navigation task attached")
                main.post(tick)
            }.onFailure { fail("谷歌原版地图画面暂不可用", it) }
        }

        private fun requestMapLayer() {
            mapServer?.send(Message.obtain(null, 11).apply { replyTo = mapReplies })
        }

        private fun updateMirror(task: Any) {
            val source = providerSurface
                ?: error("Navigation surface is unavailable")
            if (taskControl !== source || mirror?.isValid != true) {
                mirror?.let { old -> SurfaceControl.Transaction().use { it.reparent(old, null).apply() }; old.release() }
                mirror = SurfaceControl::class.java.getMethod("mirrorSurface", SurfaceControl::class.java)
                    .invoke(null, source) as SurfaceControl
                taskControl = source
            }
            val target = requireNotNull(mirror)
            val sourceX = sourceWidth * sourceAnchorX.coerceIn(.1f, .9f)
            val sourceY = sourceHeight * sourceAnchorY.coerceIn(.1f, .9f)
            val x = anchor.exactCenterX()
            val y = anchor.exactCenterY()
            val scale = max(max((visibleWidth-x)/(sourceWidth-sourceX), (outputHeight-y)/(sourceHeight-sourceY)),
                max(x/sourceX, y/sourceY))
            SurfaceControl.Transaction().use { transaction ->
                Reflect.invoke(transaction, "setMatrix", target, scale, 0f, 0f, scale)
                transaction.reparent(target, parent).setPosition(target, x-sourceX*scale, y-sourceY*scale)
                    .setLayer(target, 1)
                Reflect.invoke(transaction, "show", target)
                transaction.apply()
            }
        }

        fun layout(data: Bundle) {
            outputWidth = data.getInt("rearMapWidth", outputWidth)
            outputHeight = data.getInt("rearMapHeight", outputHeight)
            visibleWidth = data.getInt("rearMapVisibleWidth", visibleWidth)
            data.getParcelable("rearMapBounds", Rect::class.java)?.let { anchor = it }
        }

        fun returnToFront() {
            if (ended || virtual == null) return
            runCatching {
                restoreTask()
                releaseVirtualDisplay()
                changingDisplay = false
                frontGraceUntil = SystemClock.uptimeMillis() + 5_000L
                findTask()?.let(::updateMirror)
            }.onFailure { fail("谷歌导航已回到主屏", it) }
        }

        private fun restoreTask() {
            val displayId = virtual?.display?.displayId ?: return
            if (findTask()?.let(Server::displayId) == displayId)
                Reflect.invoke(service, "moveRootTaskToDisplay", taskId, originalDisplay)
        }

        private fun releaseVirtualDisplay() {
            val display = virtual
            val reader = images
            virtual = null
            images = null
            displayWakeLock?.let { runCatching { if (it.isHeld) it.release() } }
            displayWakeLock = null
            runCatching { display?.release() }.onFailure { YLog.warn("Google navigation display release failed", it) }
            runCatching { reader?.close() }
        }

        private fun fail(message: String, error: Throwable) {
            YLog.warn("Google rear navigation renderer unavailable", error)
            runCatching { reply.send(Message.obtain(null, 10000111).apply {
                data = Bundle().apply { putString("reason", message) }
            }) }
            close()
        }

        fun close() {
            if (ended) return
            ended = true
            runCatching { reply.send(Message.obtain(null, 10000111).apply {
                data = Bundle().apply { putString("reason", "导航地图正在恢复") }
            }) }
            main.removeCallbacks(tick)
            runCatching { reply.binder.unlinkToDeath(death, 0) }
            mapServer?.binder?.let { runCatching { it.unlinkToDeath(mapDeath, 0) } }
            mapServer = null
            runCatching { restoreTask() }.onFailure { YLog.warn("Google navigation task restoration failed", it) }
            mirror?.let { value ->
                runCatching { SurfaceControl.Transaction().use { it.reparent(value, null).apply() } }
                value.release()
            }
            mirror = null
            providerSurface?.release()
            providerSurface = null
            releaseVirtualDisplay()
            parent.release()
            if (session === this) session = null
        }
    }
}
