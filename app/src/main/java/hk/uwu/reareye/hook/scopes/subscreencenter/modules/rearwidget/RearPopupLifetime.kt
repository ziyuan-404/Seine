package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.os.SystemClock
import android.view.ViewGroup
import hk.uwu.reareye.hook.support.YLog
import java.util.WeakHashMap

/** Retains the stock timeout/dismissal path, but budgets readable time after
 * the entrance and privacy reveal rather than from notification arrival. */
internal object RearPopupLifetime {
    private const val READ_MS = 10_000L
    private const val PRIVATE_MS = 22_000L
    private data class State(val source: String, var hidden: Boolean, var deadline: Long,
                             val originalKeepScreenOn: Boolean)
    private val states = WeakHashMap<ViewGroup, State>()

    fun bind(panel: ViewGroup, source: String, hidden: Boolean) {
        val old = states[panel]
        val now = SystemClock.elapsedRealtime()
        val state = if (old == null || old.source != source) {
            State(source, hidden, now + if (hidden) PRIVATE_MS else RearMessageEntryMotion.POPUP_ENTRANCE_MS + READ_MS,
                old?.originalKeepScreenOn ?: panel.keepScreenOn).also { states[panel] = it }
        } else {
            if (old.hidden && !hidden) old.deadline = now + RearPopupReveal.POPUP_REVEAL_MS + READ_MS
            old.hidden = hidden
            old
        }
        panel.keepScreenOn = true
        arm(panel, state)
        YLog.debug("[RearPopupLifetime] bound hidden=$hidden remaining=${state.deadline - now}")
    }

    // The native show task may replace its alarm after binding the children.
    // Re-arm the remaining budget; duplicate/ranking updates never restart it.
    fun shown(panel: ViewGroup) {
        val entry = runCatching { panel.javaClass.getDeclaredField("q").apply { isAccessible = true }.get(panel) }.getOrNull()
        if (entry != null && RearPopupReveal.owns(panel, entry)) states[panel]?.let { arm(panel, it) }
    }

    fun touch(panel: ViewGroup) {
        val state = states[panel] ?: return
        state.deadline = maxOf(state.deadline, SystemClock.elapsedRealtime() + if (state.hidden) PRIVATE_MS else READ_MS)
        arm(panel, state)
    }

    private fun arm(panel: ViewGroup, state: State) {
        runCatching {
            val alarm = panel.javaClass.getDeclaredField("t").apply { isAccessible = true }.get(panel) ?: return
            val remaining = (state.deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
            alarm.javaClass.getDeclaredMethod("b", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(alarm, remaining, 2)
        }.onFailure { YLog.warn("Rear popup readable timeout unavailable", it) }
    }

    fun forget(panel: ViewGroup) {
        states.remove(panel)?.let { panel.keepScreenOn = it.originalKeepScreenOn }
    }

    fun clear() { states.keys.toList().forEach(::forget) }
}
