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
            if (intent.action == "android.intent.action.USER_SWITCHED" ||
                intent.action == "miui.intent.action.SUB_SCREEN_OFF") reset()
            else checkDisplay(1)
        }
    }
    fun attach(ctx: Context) {
        if (context != null) return
        context = ctx.applicationContext
        displays = ctx.getSystemService(DisplayManager::class.java)
        awake = displays?.getDisplay(1)?.state == Display.STATE_ON
        displays?.registerDisplayListener(listener, main)
        context?.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction("android.intent.action.USER_SWITCHED")
            addAction("miui.intent.action.SUB_SCREEN_OFF")
        }, Context.RECEIVER_NOT_EXPORTED)
    }
    fun isAuthorized(): Boolean {
        return awake && authorized
    }
    fun accept(expectedGeneration: Long) {
        checkDisplay(1)
        if (expectedGeneration != generation || displays?.getDisplay(1)?.state != Display.STATE_ON) return
        if (!authorized) {
            authorized = true
            onNativeAuthorizationChanged?.invoke(true)
            YLog.info("[RearFaceSession] OEM success accepted for wake=$generation")
            main.post { onChanged?.invoke() }
        }
    }
    private fun checkDisplay(id: Int) {
        if (id != 1) return
        val now = displays?.getDisplay(1)?.state == Display.STATE_ON
        if (awake && !now) reset()
        awake = now
    }
    fun reset() {
        if (authorized) YLog.info("[RearFaceSession] cleared on rear sleep/user change")
        authorized = false
        generation++
        onNativeAuthorizationChanged?.invoke(false)
        main.post { onChanged?.invoke() }
    }
    fun close() {
        displays?.unregisterDisplayListener(listener)
        context?.unregisterReceiver(receiver)
        context = null; displays = null; onChanged = null; onNativeAuthorizationChanged = null
        authorized = false; generation++
    }
}
