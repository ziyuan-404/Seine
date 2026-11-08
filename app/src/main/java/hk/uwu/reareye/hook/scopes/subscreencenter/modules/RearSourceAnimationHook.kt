package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.Choreographer
import com.highcapable.kavaref.KavaRef.Companion.resolve
import hk.uwu.roxyhook.HotReloadPolicy
import hk.uwu.roxyhook.PackageScope
import hk.uwu.roxyhook.RoxyHooker
import hk.uwu.reareye.hook.activityhost.ActivityHostTaskConfigurationAdapter as Server
import hk.uwu.reareye.hook.support.YLog
import hk.uwu.reareye.hook.support.hookPrefs
import hk.uwu.reareye.ui.config.ConfigKeys
import hk.uwu.reareye.ui.config.RearAppDefaults
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.math.floor

/** Shell owns the task lifecycle; the stock rear LauncherCardView owns animation. */
class RearSourceAnimationHook : RoxyHooker() {
    override fun PackageScope.onHook() {
        loadApp("com.android.systemui") {
            var active = true
            val finishing = mutableSetOf<Runnable>()
            val prefs = hookPrefs
            data class PreviewHandoff(val task: Int, val targetPackage: String, val until: Long)
            val previewHandoff = java.util.concurrent.atomic.AtomicReference<PreviewHandoff>()
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.action == RearLaunchOrigin.CLOSING_ACTION) {
                        RearCameraAreaFill.onClosingTransition()
                        return
                    }
                    val target = intent.getStringExtra("package") ?: return
                    val until = intent.getLongExtra("until", 0L)
                    if (until <= android.os.SystemClock.elapsedRealtime()) return
                    previewHandoff.set(PreviewHandoff(intent.getIntExtra("taskId", -1), target, until))
                }
            }
            val receiverHandler = Handler(Looper.getMainLooper())
            var receiverContext: Context? = null
            val receiverFilter = IntentFilter().apply {
                addAction(RearLaunchOrigin.PREVIEW_ACTION)
                addAction(RearLaunchOrigin.CLOSING_ACTION)
            }
            val registerReceiver = object : Runnable {
                var attempts = 0
                override fun run() {
                    if (!active || receiverContext != null) return
                    val app = runCatching {
                        Class.forName("android.app.ActivityThread").getMethod("currentApplication")
                            .invoke(null) as? Context
                    }.getOrNull()
                    if (app?.packageName != "com.android.systemui") {
                        if (++attempts < 80) receiverHandler.postDelayed(this, 250L)
                        else YLog.warn("SystemUI application unavailable for rear animation handoff")
                        return
                    }
                    runCatching {
                        app.registerReceiver(receiver, receiverFilter,
                            "android.permission.INTERACT_ACROSS_USERS_FULL", null, Context.RECEIVER_EXPORTED)
                        receiverContext = app
                    }.onFailure { YLog.warn("Rear preview handoff receiver unavailable", it) }
                }
            }
            receiverHandler.post(registerReceiver)
            data class Frame(val task: Int, val origin: RearLaunchOrigin?, val opening: Boolean,
                val start: Any?, val leash: Any?, val buffer: Rect?, val preanimated: Boolean)
            val frames = ThreadLocal<kotlin.collections.ArrayDeque<Frame>>()
            val taskType = "android.app.TaskInfo".toClass()
            val animation = "com.android.wm.shell.multitasking.miuifreeform.MiuiFreeformModeAnimation".toClass()
            val openingMethods = setOf("startMoveToFrontAnimation", "startFullScreenToFreeformAnimation")
            val closingMethods = setOf("startMoveToBackCloseAnimation", "startCloseAnimation")
            animation.declaredMethods.filter { it.name in openingMethods || it.name in closingMethods }.forEach { method ->
                animation.resolve().firstMethod { name = method.name; parameters(*method.parameterTypes) }.hook {
                    hotReloadPolicy = HotReloadPolicy.REINSTALL
                    before {
                        val info = args.filterNotNull().firstNotNullOfOrNull {
                            when {
                                taskType.isInstance(it) -> it
                                it.javaClass.simpleName == "MiuiFreeformModeTaskInfo" -> Server.call(it, "getTaskInfo")
                                else -> null
                            }
                        }
                        val eligible = active && info != null && Server.field(info, "displayId") == 1 &&
                            prefs.getBoolean(ConfigKeys.REAR_APP_ENABLED, RearAppDefaults.ENABLED)
                        val origin = if (eligible) RearLaunchOrigin.fromTask(info!!) else null
                        if (origin != null && method.name in closingMethods) RearCameraAreaFill.onClosingTransition()
                        val taskId = info?.let { Server.field(it, "taskId") as? Int } ?: -1
                        val appPackage = info?.let { (Server.field(it, "topActivity") as? android.content.ComponentName)
                            ?: (Server.field(it, "baseActivity") as? android.content.ComponentName) }?.packageName
                        val handoff = previewHandoff.get()
                        val preanimated = origin?.preanimated == true ||
                            (method.name in openingMethods && handoff != null &&
                                handoff.until > android.os.SystemClock.elapsedRealtime() &&
                                handoff.targetPackage == appPackage &&
                                (handoff.task < 0 || handoff.task == taskId))
                        val change = args.filterNotNull().firstOrNull { it.javaClass.name == "android.window.TransitionInfo\$Change" }
                        val transactions = args.filterNotNull().filter { it.javaClass.name == "android.view.SurfaceControl\$Transaction" }
                        val native = args.filterNotNull().firstOrNull { it.javaClass.simpleName == "MiuiFreeformModeTaskInfo" }
                        val buffer = (native?.let { Server.call(it, "getTaskBounds") } as? Rect) ?:
                            (change?.let { Server.call(it, if (method.name in openingMethods) "getEndAbsBounds" else "getStartAbsBounds") } as? Rect)
                        val stack = frames.get() ?: kotlin.collections.ArrayDeque<Frame>().also { frames.set(it) }
                        stack.addLast(Frame(taskId, origin,
                            method.name in openingMethods, transactions.firstOrNull(),
                            change?.let { Server.call(it, "getLeash") }, buffer?.let(::Rect), preanimated))
                    }
                    after { frames.get()?.let { if (it.isNotEmpty()) it.removeLast(); if (it.isEmpty()) frames.remove() } }
                }
            }
            val stateType = "com.android.wm.shell.multitasking.common.animation.MultiTaskingFolmeState".toClass()
            val control = "com.android.wm.shell.multitasking.common.animation.MultiTaskingFolmeControl".toClass()
            val property = "com.android.wm.shell.multitasking.common.animation.MultiTaskingFolmeProperty".toClass()
            val valueProperty = property.getField("valueProperty")
            val from = property.getField("startValue"); val to = property.getField("endValue")
            val properties = listOf("CENTER_X", "ELEGANT_Y", "ANCHOR_Y", "WIDTH", "HEIGHT", "RADIUS")
                .associateWith { control.getField("FOLME_$it").get(null) }
            val surface = "android.view.SurfaceControl".toClass()
            val transaction = "android.view.SurfaceControl\$Transaction".toClass()
            val setPosition = transaction.getMethod("setPosition", surface, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            val setScale = transaction.getMethod("setScale", surface, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            val setCrop = transaction.getMethod("setWindowCrop", surface, Rect::class.java)
            val setAlpha = transaction.getMethod("setAlpha", surface, Float::class.javaPrimitiveType)
            val setCornerRadius = transaction.getMethod("setCornerRadius", surface,
                Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            val setCornerRadii = transaction.getMethod("setMiCornerRadii", surface, FloatArray::class.java)
            val resetRotation = transaction.getMethod("setSurfaceRotation", surface, FloatArray::class.java, FloatArray::class.java,
                FloatArray::class.java, Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            val apply = transaction.getMethod("apply")
            val setFrameTimelineVsync = transaction.getMethod("setFrameTimelineVsync", Long::class.javaPrimitiveType)
            val deferAnimation = transaction.getMethod("deferAnimation", surface, Int::class.javaPrimitiveType)
            val sfChoreographer = runCatching { Choreographer::class.java.getMethod("getSfInstance") }.getOrNull()
            val closeTransaction = transaction.getMethod("close")
            val listenerType = "com.android.wm.shell.multitasking.common.animation.IMultiTaskingAnimationListener".toClass()
            val begin = listenerType.getMethod("onAnimationBegin")
            val update = listenerType.getMethod("onAnimationUpdate", transaction)
            val executorType = "com.android.wm.shell.multitasking.common.animation.MultiTaskingFolmeAnimExecutor".toClass()
            val findTask = runCatching {
                executorType.getDeclaredField("mMultiTaskingTaskRepository").type
                    .getMethod("getMiuiFreeformTaskInfo", Int::class.javaPrimitiveType)
            }.getOrNull()
            val addRunning = executorType.getMethod("addRunningAnimInfo", control, stateType)
            val complete = executorType.getDeclaredMethod("notifyAnimFinishOrInterrupted", stateType, listenerType).apply { isAccessible = true }
            val remove = executorType.getMethod("removeFolmeStateAndRunFinishRunnable", stateType)
            val destination = control.getMethod("updateBaseAnimDestinationParam", stateType)
            var receipts = 6
            executorType.resolve().firstMethod { name = "executeFolmeAnimation"; parameterCount = 1 }.hook {
                hotReloadPolicy = HotReloadPolicy.REINSTALL
                before {
                    if (!active) return@before
                    val frame = frames.get()?.lastOrNull() ?: return@before
                    val origin = frame.origin ?: return@before
                    val state = args(0).value ?: return@before
                    if (Server.call(state, "getTaskId") != frame.task) return@before
                    if (frame.opening && Server.call(state, "getAnimationType") != 2) return@before
                    if ((Server.call(state, "getMultiTaskingAnimationType") as? Int) !in setOf(2, 13, 16, 20)) return@before
                    val owner = instance ?: return@before
                    val fc = Server.call(state, "getFolmeControl") ?: return@before
                    val buffer = ((Server.call(fc, "getAnimationTaskBounds") as? Rect) ?: frame.buffer)
                        ?.takeUnless { it.isEmpty }?.let(::Rect) ?: return@before
                    // Match the OEM executor: the organized task's animation
                    // leash can differ from TransitionInfo.Change's parent.
                    val leash = Server.call(fc, "getAnimationLeash") ?: return@before
                    val listener = Server.call(state, "getAnimationListener")
                    val map = Server.call(state, "getFolmeProperty") as? Map<*, *> ?: return@before
                    val entries = map.values.filterIsInstance<List<*>>().flatten().filterNotNull()
                    val picker = origin.kind == RearLaunchOrigin.PICKER
                    // Opening states carry the task radius. Native close states
                    // apply it in the start transaction without a Folme property.
                    val taskRadius = if (picker) {
                        if (frame.opening) {
                            entries.firstOrNull { valueProperty.get(it) === properties["RADIUS"] }
                                ?.let { to.getFloat(it) }
                        } else {
                            runCatching {
                                val repository = Server.field(owner, "mMultiTaskingTaskRepository")
                                findTask?.invoke(repository, frame.task)
                                    ?.let { Server.call(it, "getCornerRadius") as? Float }
                            }.getOrNull()
                        }
                    } else null
                    val surfaceRadius = taskRadius?.takeIf { it.isFinite() && it >= 0f }
                    val endpointSetters = entries.mapNotNull { item ->
                        val nativeProperty = valueProperty.get(item)
                        val name = Server.call(nativeProperty, "getName") as? String ?: return@mapNotNull null
                        val setter = control.methods.firstOrNull { it.name == "set" + name.replaceFirstChar(Char::uppercaseChar) &&
                            it.parameterTypes.contentEquals(arrayOf(Float::class.javaPrimitiveType)) } ?: return@mapNotNull null
                        setter to to.getFloat(item)
                    }
                    fun value(name: String): Float {
                        val entry = entries.firstOrNull { valueProperty.get(it) === properties[name] } ?: error("Native card destination missing: $name")
                        return (if (frame.opening) to else from).getFloat(entry)
                    }
                    val width = value("WIDTH"); val height = value("HEIGHT")
                    val x = value("CENTER_X") - width / 2f
                    val y = value("ELEGANT_Y") - height * value("ANCHOR_Y") / 10f
                    val full = Rect(x.roundToInt(), y.roundToInt(), (x + width).roundToInt(), (y + height).roundToInt())
                    if (full.isEmpty) return@before
                    val transitions = Server.field(owner, "mTransitions") ?: return@before
                    val shell = Server.call(transitions, "getMainExecutor") as? java.util.concurrent.Executor ?: return@before
                    val tx = transaction.getConstructor().newInstance()
                    val timer = Handler(Looper.getMainLooper())
                    var finished = false
                    var registered = false
                    var terminalFrameApplied = false
                    var last: FloatArray? = null
                    fun renderPicker(target: Any, values: FloatArray) {
                        // The OEM spring can pass its destination. Keep the task
                        // at the exact Shell bounds while the card settles.
                        val atEndpoint = values.getOrNull(9) == 1f
                        val unitsX = width / full.width()
                        val unitsY = height / full.height()
                        val left = if (atEndpoint) x else x + (values[3] - full.left) * unitsX
                        val top = if (atEndpoint) y else y + (values[4] - full.top) * unitsY
                        val visibleWidth = if (atEndpoint) width else (values[5] - values[3]) * unitsX
                        val visibleHeight = if (atEndpoint) height else (values[6] - values[4]) * unitsY
                        if (visibleWidth <= 0f || visibleHeight <= 0f) {
                            setAlpha.invoke(target, leash, 0f)
                            return
                        }
                        // Match the card's centered content crop, covering both
                        // axes even when the source is a square picker icon.
                        val cover = maxOf(visibleWidth / width, visibleHeight / height)
                        val cropWidth = (visibleWidth / (cover * width) * buffer.width()).roundToInt().coerceIn(1, buffer.width())
                        val cropHeight = (visibleHeight / (cover * height) * buffer.height()).roundToInt().coerceIn(1, buffer.height())
                        val cropLeft = (buffer.width() - cropWidth) / 2
                        val cropTop = (buffer.height() - cropHeight) / 2
                        val crop = Rect(cropLeft, cropTop, cropLeft + cropWidth, cropTop + cropHeight)
                        // Window crop is integer in buffer space. Compensate its
                        // rounding in the matrix so the screen edges stay put.
                        val scaleX = visibleWidth / cropWidth
                        val scaleY = visibleHeight / cropHeight
                        val radius = values[7].coerceIn(0f, minOf(visibleWidth, visibleHeight) / 2f)
                        setPosition.invoke(target, leash, left - cropLeft * scaleX, top - cropTop * scaleY)
                        setScale.invoke(target, leash, scaleX, scaleY)
                        setCrop.invoke(target, leash, crop)
                        setCornerRadius.invoke(target, leash, radius / scaleX, radius / scaleY)
                        setAlpha.invoke(target, leash, values[8].coerceIn(0f, 1f))
                    }
                    fun render(target: Any, values: FloatArray) {
                        resetRotation.invoke(target, leash, floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f),
                            floatArrayOf(0f, 0f, 0f), 30f, 5500f)
                        if (picker) {
                            renderPicker(target, values)
                            return
                        }
                        val scaleX = (values[2] * full.width() / buffer.width()).coerceAtLeast(.001f)
                        val scaleY = (values[2] * full.height() / buffer.height()).coerceAtLeast(.001f)
                        val left = values[3]; val top = values[4]; val right = values[5]; val bottom = values[6]
                        val crop = Rect(floor((left - values[0]) / scaleX).toInt(),
                            floor((top - values[1]) / scaleY).toInt(),
                            ceil((right - values[0]) / scaleX).toInt(),
                            ceil((bottom - values[1]) / scaleY).toInt())
                        crop.intersect(0, 0, buffer.width(), buffer.height())
                        setPosition.invoke(target, leash, values[0], values[1])
                        setScale.invoke(target, leash, scaleX, scaleY)
                        setCrop.invoke(target, leash, crop)
                        val radiusX = values[7].coerceIn(0f, (right - left).coerceAtLeast(0f) / 2f) / scaleX
                        val radiusY = values[7].coerceIn(0f, (bottom - top).coerceAtLeast(0f) / 2f) / scaleY
                        val corners = FloatArray(8) { index -> if (index % 2 == 0) radiusX else radiusY }
                        setCornerRadii.invoke(target, leash, corners)
                        setAlpha.invoke(target, leash, values[8].coerceIn(0f, 1f))
                    }
                    lateinit var finish: Runnable
                    finish = Runnable {
                        if (!finished) {
                            finished = true
                            timer.removeCallbacksAndMessages(null)
                            finishing.remove(finish)
                            val end = floatArrayOf(full.left.toFloat(), full.top.toFloat(), 1f,
                                full.left.toFloat(), full.top.toFloat(), full.right.toFloat(), full.bottom.toFloat(),
                                surfaceRadius ?: last?.get(7) ?: 100f, if (frame.opening) 1f else 0f, 1f)
                            runCatching {
                                endpointSetters.forEach { (setter, value) -> setter.invoke(fc, value) }
                                if (Server.call(state, "needUpdateBaseParamAnimComplete") == true)
                                    control.getMethod("updateBaseAnimParam", Boolean::class.javaPrimitiveType)
                                        .invoke(fc, map.containsKey("clip"))
                                if (!terminalFrameApplied) { render(tx, end); apply.invoke(tx) }
                            }.onFailure { YLog.warn("OEM rear card surface completion failed", it) }
                            // Let the ROM restore the parked task's alpha and
                            // crop in its finish transaction, even on interruption.
                            runCatching { if (registered) complete.invoke(owner, state, listener) }
                                .onFailure { YLog.warn("OEM rear card completion callback failed", it) }
                            runCatching { if (registered) remove.invoke(owner, state) }
                                .onFailure { YLog.warn("OEM rear card lifecycle completion failed", it) }
                            closeTransaction.invoke(tx)
                        }
                    }
                    fun applyFrame(values: FloatArray, done: Boolean, vsyncId: Long? = null) {
                        if (finished) return
                        last = values
                        runCatching {
                            if (listener != null) update.invoke(listener, tx)
                            render(tx, values)
                            if (vsyncId != null) {
                                deferAnimation.invoke(tx, leash, 3)
                                setFrameTimelineVsync.invoke(tx, vsyncId)
                            }
                            apply.invoke(tx)
                            terminalFrameApplied = done
                        }.onFailure { YLog.warn("OEM rear card surface frame failed", it); finish.run() }
                        if (done) finish.run()
                    }
                    var pendingFrame: FloatArray? = null
                    var pendingDone = false
                    val vsync = Choreographer.VsyncCallback { frameData ->
                        val values = pendingFrame
                        pendingFrame = null
                        if (values != null) applyFrame(values, pendingDone, frameData.preferredFrameTimeline.vsyncId)
                    }
                    val callback = object : Binder() {
                        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                            if (code != FIRST_CALL_TRANSACTION) return super.onTransact(code, data, reply, flags)
                            data.enforceInterface(RearLaunchOrigin.FRAME)
                            val values = data.createFloatArray() ?: return true
                            val done = data.readInt() == 1
                            if (values.size !in 9..10 || values.any { !it.isFinite() }) return true
                            shell.execute {
                                if (finished) return@execute
                                if (!picker) {
                                    applyFrame(values, done)
                                    return@execute
                                }
                                // Keep one frame per Shell vsync, as the native
                                // freeform executor does for its transactions.
                                val scheduled = pendingFrame != null
                                if (!scheduled || !pendingDone) {
                                    pendingFrame = values
                                    pendingDone = done
                                }
                                if (!scheduled) {
                                    val choreographer = runCatching { sfChoreographer?.invoke(null) as? Choreographer }.getOrNull()
                                        ?: Choreographer.getInstance()
                                    choreographer.postVsyncCallback(vsync)
                                }
                            }
                            return true
                        }
                    }
                    if (!frame.opening || !frame.preanimated) {
                        if (!origin.animate(frame.opening, full, callback, surfaceRadius)) { closeTransaction.invoke(tx); return@before }
                    }
                    // Preserve Shell's original running-state/finish bookkeeping.
                    // No Folme state, spring, alpha curve or geometry is replaced.
                    result = null
                    destination.invoke(fc, state)
                    addRunning.invoke(owner, fc, state); registered = true
                    if (listener != null) begin.invoke(listener)
                    if (frame.opening) frame.start?.let { setAlpha.invoke(it, leash, 0f) }
                    finishing.add(finish)
                    timer.postDelayed({ shell.execute(finish) }, 3500L)
                    if (frame.opening && frame.preanimated) {
                        // The OEM card already reached the task bounds before the
                        // activity was launched. Present Shell's ready surface at
                        // that endpoint instead of replaying an icon-to-task zoom.
                        timer.post { shell.execute {
                            if (!finished) {
                                val end = floatArrayOf(full.left.toFloat(), full.top.toFloat(), 1f,
                                    full.left.toFloat(), full.top.toFloat(), full.right.toFloat(), full.bottom.toFloat(),
                                    surfaceRadius ?: 100f, 1f, 1f)
                                runCatching { render(tx, end); apply.invoke(tx); terminalFrameApplied = true }
                                    .onFailure { YLog.warn("Rear ready-surface handoff failed", it) }
                                finish.run()
                            }
                        } }
                    }
                    if (receipts-- > 0) YLog.info("OEM LauncherCardView owns rear animation: task=" + frame.task + " open=" + frame.opening + " preview=" + frame.preanimated + " area=" + full + " leash=" + leash + " parent=" + frame.leash)
                }
            }
            runtime.onClose {
                active = false
                receiverHandler.removeCallbacks(registerReceiver)
                receiverContext?.let { runCatching { it.unregisterReceiver(receiver) } }
                finishing.toList().forEach { it.run() }
            }
            YLog.info("OEM rear LauncherCardView surface bridge installed")
        }
    }
}
