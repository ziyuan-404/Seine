package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.graphics.Outline
import android.graphics.Rect
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.View
import hk.uwu.reareye.hook.activityhost.ActivityHostTaskConfigurationAdapter as Server
import hk.uwu.reareye.hook.support.YLog
import java.lang.ref.WeakReference

/** Launch cookies identify a weak source and its OEM card-animation endpoint. */
internal data class RearLaunchOrigin(val kind: Int, val bounds: Rect, val radius: Float,
                                     val motion: IBinder? = null, val preanimated: Boolean = false) {
    companion object {
        const val PICKER = 1
        const val NOTIFICATION = 2
        const val PREVIEW_ACTION = "hk.uwu.reareye.REAR_CARD_PREVIEW_READY"
        const val CLOSING_ACTION = "hk.uwu.reareye.REAR_APP_RETURN_HOME"
        const val FRAME = "hrs.rear.native.card.frame.v2"
        private const val PREFIX = "hrs.rear.origin.v2|"
        private const val MOTION = "hrs.rear.native.card.v2"
        private const val RESTORE = IBinder.FIRST_CALL_TRANSACTION
        private const val QUERY = RESTORE + 1
        private const val ANIMATE = RESTORE + 2
        private const val RETURNED_HOME = RESTORE + 3
        private val main = Handler(Looper.getMainLooper())
        private val ownedSources = java.util.WeakHashMap<View, Source>()
        private val bindings = java.util.Collections.synchronizedMap(java.util.WeakHashMap<IBinder, Source>())
        private val redirects = java.util.Collections.synchronizedMap(java.util.WeakHashMap<IBinder, RearLaunchOrigin>())
        fun ownsSource(view: View) = ownedSources.containsKey(view)

        fun capture(view: View, kind: Int, onOpeningComplete: (() -> Unit)? = null,
                    preanimated: Boolean = false): RearLaunchOrigin? {
            ownedSources[view]?.restore()
            val area = Rect()
            if (!view.getGlobalVisibleRect(area) || area.isEmpty) return null
            val outline = Outline().also { view.outlineProvider?.getOutline(view, it) }
            val radius = outline.radius.takeIf { it >= 0f } ?: minOf(area.width(), area.height()) / 2f
            val origin = RearLaunchOrigin(kind, area, radius, preanimated = preanimated)
            val source = Source(view, origin, onOpeningComplete)
            val channel = object : Binder() {
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    if (code !in RESTORE..RETURNED_HOME) return super.onTransact(code, data, reply, flags)
                    data.enforceInterface(MOTION)
                    val redirect = redirects[this]
                    when (code) {
                        QUERY -> reply?.writeString((redirect ?: origin).descriptor())
                        RESTORE -> if (redirect != null) redirect.sourceAlpha(1f) else source.restore()
                        RETURNED_HOME -> if (redirect != null) redirect.returnedToHome() else source.returnedToHome()
                        ANIMATE -> {
                            val opening = data.readInt() == 1
                            val full = Rect(data.readInt(), data.readInt(), data.readInt(), data.readInt())
                            val callback = requireNotNull(data.readStrongBinder())
                            val openingRadius = if (data.dataAvail() >= Float.SIZE_BYTES)
                                data.readFloat().takeIf { it.isFinite() && it >= 0f } else null
                            val accepted = if (redirect != null) redirect.animate(opening, full, callback, openingRadius)
                                else source.animate(opening, full, callback, openingRadius)
                            reply?.writeInt(if (accepted) 1 else 0)
                        }
                    }
                    return true
                }
            }.apply { attachInterface(null, origin.descriptor()) }
            bindings[channel] = source
            return origin.copy(motion = channel)
        }

        fun fromTask(info: Any): RearLaunchOrigin? {
            val cookies = Server.field(info, "launchCookies") as? List<*> ?: return null
            return cookies.asReversed().firstNotNullOfOrNull { cookie -> runCatching {
                val binder = cookie as? IBinder ?: return@runCatching null
                val descriptor = binder.interfaceDescriptor ?: return@runCatching null
                if (!descriptor.startsWith(PREFIX)) return@runCatching null
                val request = Parcel.obtain(); val response = Parcel.obtain()
                val current = try {
                    request.writeInterfaceToken(MOTION)
                    if (binder.transact(QUERY, request, response, 0)) response.readString() else descriptor
                } finally { request.recycle(); response.recycle() }
                val parts = current?.takeIf { it.length < 180 }?.removePrefix(PREFIX)?.split(',') ?: return@runCatching null
                if (parts.size !in 6..7) return@runCatching null
                val kind = parts[0].toInt()
                val bounds = Rect(parts[1].toInt(), parts[2].toInt(), parts[3].toInt(), parts[4].toInt())
                val radius = parts[5].toFloat()
                if (kind !in PICKER..NOTIFICATION || bounds.isEmpty || !radius.isFinite()) return@runCatching null
                RearLaunchOrigin(kind, bounds, radius, binder, parts.getOrNull(6) == "1")
            }.getOrNull() }
        }

        private class Source(view: View, val origin: RearLaunchOrigin, val onOpened: (() -> Unit)?) {
            private val original = SourceViewState(view)
            val view = WeakReference(view)
            var native: NativeRearCardAnimation? = null
            var opened = false
            @Volatile private var retired = false
            private var restored = false
            private var animationSequence = 0L
            private var returned = false
            private val releaseReturned = Runnable {
                if (returned && !retired && native?.isClosing != true) restore()
            }
            fun previewOpen(full: Rect, openingRadius: Float, onComplete: () -> Unit) {
                check(!retired) { "Rear source session ended" }
                val target = requireNotNull(view.get()) { "Rear source disappeared" }
                check(target.isAttachedToWindow)
                val sequence = ++animationSequence
                ownedSources[target] = this
                val renderer = native ?: NativeRearCardAnimation(target).also { native = it }
                renderer.animate(true, full, origin, openingRadius) { _, done ->
                    if (!retired && native === renderer && animationSequence == sequence && done && !opened) {
                        opened = true
                        onOpened?.invoke()
                        onComplete()
                    }
                }
            }
            fun restore() {
                retired = true
                main.removeCallbacks(releaseReturned)
                val reset = reset@ {
                    if (restored) return@reset
                    restored = true
                    animationSequence++
                    val renderer = native
                    native = null
                    val target = view.get()
                    val ownsTarget = target != null && (ownedSources[target] === this || ownedSources[target] == null)
                    try {
                        renderer?.dispose()
                    } finally {
                        if (ownsTarget) target?.let {
                            original.restore(it)
                            if (ownedSources[it] === this) ownedSources.remove(it)
                        }
                    }
                }
                if (Looper.myLooper() == Looper.getMainLooper()) reset() else main.post { reset() }
            }

            fun returnedToHome() {
                val settle = {
                    if (!retired) {
                        returned = true
                        if (native?.isClosing != true) {
                            native?.pauseOpening()
                            view.get()?.let { target ->
                                if (ownedSources[target] === this || ownedSources[target] == null) {
                                    original.restore(target)
                                    if (ownedSources[target] === this) ownedSources.remove(target)
                                }
                            }
                        }
                        // Keep the ready renderer for a closing request already
                        // in flight. Reclaim it if Shell sends no close request.
                        main.removeCallbacks(releaseReturned)
                        main.postDelayed(releaseReturned, 3_500L)
                    }
                }
                if (Looper.myLooper() == Looper.getMainLooper()) settle() else main.post { settle() }
            }
            fun animate(opening: Boolean, full: Rect, callback: IBinder, openingRadius: Float?): Boolean {
                if (retired) return false
                val target = view.get()?.takeIf { it.isAttachedToWindow } ?: return false
                if (NativeRearCardAnimation.panelClass == null || full.isEmpty) return false
                main.post {
                    if (retired) return@post
                    runCatching {
                        returned = false
                        main.removeCallbacks(releaseReturned)
                        val sequence = ++animationSequence
                        ownedSources[target] = this
                        val renderer = native ?: NativeRearCardAnimation(target).also { native = it }
                        renderer.animate(opening, full, origin, openingRadius) frame@ { frame, done ->
                            if (retired || native !== renderer || animationSequence != sequence) {
                                if (done) send(callback, frame, true)
                                return@frame
                            }
                            send(callback, frame, done)
                            if (done) {
                                if (opening && !opened) { opened = true; onOpened?.invoke() }
                                if (!opening) {
                                    restore()
                                }
                            }
                        }
                    }.onFailure {
                        YLog.warn("OEM rear card animation failed", it)
                        restore()
                        send(callback, floatArrayOf(full.left.toFloat(), full.top.toFloat(), 1f,
                            full.left.toFloat(), full.top.toFloat(), full.right.toFloat(), full.bottom.toFloat(), openingRadius ?: 100f,
                            if (opening) 1f else 0f), true)
                    }
                }
                return true
            }
            private fun send(callback: IBinder, frame: FloatArray, done: Boolean) {
                val packet = Parcel.obtain()
                try {
                    packet.writeInterfaceToken(FRAME); packet.writeFloatArray(frame); packet.writeInt(if (done) 1 else 0)
                    callback.transact(IBinder.FIRST_CALL_TRANSACTION, packet, null, IBinder.FLAG_ONEWAY)
                } catch (_: android.os.RemoteException) { restore() } finally { packet.recycle() }
            }
        }

        private class SourceViewState(view: View) {
            private val alpha = view.alpha
            private val translationX = view.translationX
            private val translationY = view.translationY
            private val scaleX = view.scaleX
            private val scaleY = view.scaleY
            private val pivotX = view.pivotX
            private val pivotY = view.pivotY

            fun restore(view: View) {
                view.alpha = alpha
                view.translationX = translationX
                view.translationY = translationY
                view.scaleX = scaleX
                view.scaleY = scaleY
                view.pivotX = pivotX
                view.pivotY = pivotY
                view.invalidate()
            }
        }
    }

    private fun descriptor() = PREFIX + kind + "," + bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom + "," + radius + "," + if (preanimated) "1" else "0"
    fun cookie(): IBinder = requireNotNull(motion)
    fun previewOpen(full: Rect, openingRadius: Float, onComplete: () -> Unit) {
        check(Looper.myLooper() == Looper.getMainLooper())
        requireNotNull(bindings[motion]).previewOpen(full, openingRadius, onComplete)
    }
    fun retarget(view: View, kind: Int): RearLaunchOrigin {
        val channel = motion ?: return this
        if (bindings[channel]?.view?.get() === view && this.kind == kind) return this
        sourceAlpha(1f)
        val target = capture(view, kind) ?: return this
        redirects[channel] = target
        return copy(kind = kind, bounds = target.bounds, radius = target.radius)
    }
    fun sourceAlpha(alpha: Float) {
        if (alpha < 1f) return
        val channel = motion ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(MOTION)
            channel.transact(RESTORE, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: android.os.RemoteException) { } finally { data.recycle() }
    }
    fun returnedToHome() {
        val channel = motion ?: return
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(MOTION)
            channel.transact(RETURNED_HOME, data, null, IBinder.FLAG_ONEWAY)
        } catch (_: android.os.RemoteException) { } finally { data.recycle() }
    }
    fun animate(opening: Boolean, full: Rect, callback: IBinder, openingRadius: Float?): Boolean {
        val channel = motion ?: return false
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(MOTION); data.writeInt(if (opening) 1 else 0)
            data.writeInt(full.left); data.writeInt(full.top); data.writeInt(full.right); data.writeInt(full.bottom)
            data.writeStrongBinder(callback)
            // Optional tail keeps in-flight launch cookies usable across a hot reload.
            openingRadius?.let(data::writeFloat)
            channel.transact(ANIMATE, data, reply, 0) && reply.readInt() == 1
        } catch (_: android.os.RemoteException) { false } finally { data.recycle(); reply.recycle() }
    }
}
