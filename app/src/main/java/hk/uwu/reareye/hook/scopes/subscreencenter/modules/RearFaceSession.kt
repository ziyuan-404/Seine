package hk.uwu.reareye.hook.scopes.subscreencenter.modules

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import hk.uwu.reareye.hook.support.YLog

/** A successful OEM authentication belongs to one continuous rear-display wake. */
internal object RearFaceSession {
    private var displays: DisplayManager? = null
    private var context: Context? = null
    private var authorized = false
    private var awake = false
    private var inAod = false
    var generation = 0L
        private set
    var onChanged: (() -> Unit)? = null
    var onNativeAuthorizationChanged: ((Boolean) -> Unit)? = null
    private val main = Handler(Looper.getMainLooper())
    private val listener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = checkDisplay(displayId)
        override fun onDisplayRemoved(displayId: Int) = checkDisplay(displayId)
        override fun onDisplayChanged(displayId: Int) = checkDisplay(displayId)
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                "android.intent.action.USER_SWITCHED" -> reset()
                "miui.intent.action.SUB_SCREEN_OFF" -> {
                    if (!inAod && displays?.getDisplay(1)?.state == Display.STATE_ON) reset()
                    else setAwake(false)
                }
            }
        }
    }
    fun attach(ctx: Context) {
        if (context != null) return
        val app = ctx.applicationContext ?: ctx
        context = app
        displays = ctx.getSystemService(DisplayManager::class.java)
        inAod = runCatching {
            if (app.javaClass.name == "com.xiaomi.subscreencenter.SubScreenCenterApp") {
                app.javaClass.getDeclaredField("m").apply { isAccessible = true }.getBoolean(app)
            } else false
        }.getOrDefault(false)
        checkDisplay(1)
        displays?.registerDisplayListener(listener, main)
        context?.registerReceiver(receiver, IntentFilter().apply {
            addAction("android.intent.action.USER_SWITCHED")
            addAction("miui.intent.action.SUB_SCREEN_OFF")
        }, Context.RECEIVER_NOT_EXPORTED)
    }
    val isInAod: Boolean
        get() = inAod
    fun isAuthorized(): Boolean {
        return authorized && isAwake()
    }
    fun isAwake(): Boolean = awake && !inAod && displays?.getDisplay(1)?.state == Display.STATE_ON

    fun setAod(value: Boolean) {
        inAod = value
        checkDisplay(1)
    }

    fun accept(expectedGeneration: Long) {
        if (expectedGeneration != generation || !isAwake()) return
        if (!authorized) {
            authorized = true
            onNativeAuthorizationChanged?.invoke(true)
            YLog.info("[RearFaceSession] OEM success accepted for wake=$generation")
            main.post { onChanged?.invoke() }
        }
    }
    private fun checkDisplay(id: Int) {
        if (id != 1) return
        setAwake(!inAod && displays?.getDisplay(1)?.state == Display.STATE_ON)
    }
    private fun setAwake(now: Boolean) {
        if (awake == now) return
        awake = now
        if (now) main.post { onChanged?.invoke() } else reset()
    }
    fun reset() {
        val wasAuthorized = authorized
        if (wasAuthorized) YLog.info("[RearFaceSession] cleared on rear sleep/user change")
        authorized = false
        generation++
        if (wasAuthorized) onNativeAuthorizationChanged?.invoke(false)
        main.post { onChanged?.invoke() }
    }
    fun close() {
        main.removeCallbacksAndMessages(null)
        displays?.unregisterDisplayListener(listener)
        context?.unregisterReceiver(receiver)
        context = null; displays = null; onChanged = null; onNativeAuthorizationChanged = null
        awake = false; inAod = false; authorized = false; generation++
    }
}
