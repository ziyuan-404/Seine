package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.app.Notification
import android.content.Context
import android.view.View
import org.json.JSONObject

/** Retain the clock's native data and PendingIntents, using the OEM timer scene. */
internal object NativeRearStopwatchCard {
    // The OEM focus protocol changes action_1 from lap to end and action_2
    // from pause to continue; retain the original action objects in both states.
    fun actions(notification: Notification): List<Notification.Action> {
        val focus = notification.extras.getBundle("miui.focus.actions") ?: return emptyList()
        // The named protocol slots, not the declared display order, define
        // lap/end and pause/continue. Notification.actions contains only reset
        // on this ROM and must never be used as a substitute for lap.
        val actions = listOf("miui.focus.action_1", "miui.focus.action_2").mapNotNull {
            focus.getParcelable(it, Notification.Action::class.java)
                ?.takeIf { action -> action.actionIntent != null && action.remoteInputs.isNullOrEmpty() }
        }
        return actions.takeIf { it.size == 2 }.orEmpty()
    }
        private fun timing(node: JSONObject): JSONObject? {
            if (node.has("timerWhen")) return node
            node.keys().forEach { key -> node.optJSONObject(key)?.let { timing(it)?.let { value -> return value } } }
            return null
        }

        fun parameters(notification: Notification): JSONObject? = runCatching {
            val raw = notification.extras.getString("miui.focus.param")
                ?: notification.extras.getString("miui.rear.param") ?: return null
            val root = JSONObject(raw)
            val v2 = root.optJSONObject("param_v2")
            // This is the exact timer shown by the front focus card. Other
            // protocol branches can contain placeholders or stale snapshots.
            v2?.optJSONObject("highlightInfo")?.optJSONObject("timerInfo")
                ?: timing(v2 ?: root.optJSONObject("rear_param_v1") ?: root)
        }.getOrNull()

        fun isStopwatch(packageName: String, notification: Notification): Boolean {
            if (packageName != "com.android.deskclock") return false
            val raw = notification.extras.getString("miui.focus.param").orEmpty() +
                notification.extras.getString("miui.rear.param").orEmpty()
            return notification.channelId.orEmpty().contains("stopwatch", true) || raw.contains("stopwatch", true) ||
                notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.contains("秒表") == true
        }

    fun bind(context: Context, root: View, notification: Notification,
             actions: List<Notification.Action>, execute: (Int) -> Unit): Boolean =
        actions.size == 2 && NativeRearTimerScene.bind(context, root, notification, false, execute)
}
