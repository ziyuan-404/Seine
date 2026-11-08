package hk.uwu.reareye.hook.scopes.subscreencenter.modules.rearwidget

import android.app.Notification
import android.content.Context
import android.view.View
import org.json.JSONObject

/** Reuses the recorder's timer/actions and the installed SystemUI's artwork. */
internal object NativeRearRecorder {
    const val CONTROLS = "rear_notification_recorder_controls"

    fun actions(notification: Notification): List<Notification.Action> {
        val focus = notification.extras.getBundle("miui.focus.actions")
        val params = parameters(notification)
        val declared = params?.optJSONArray("actions")
        val keys = if (declared != null) (0 until declared.length()).mapNotNull {
            declared.optJSONObject(it)?.optString("action")?.takeIf(String::isNotBlank)
        } else focus?.keySet().orEmpty().sorted()
        val result = keys.mapNotNull { key ->
            focus?.getParcelable(key, Notification.Action::class.java)
        }.filter { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() }
        return result.ifEmpty {
            notification.actions.orEmpty().filter { it.actionIntent != null && it.remoteInputs.isNullOrEmpty() }
        }.take(3)
    }

    private fun parameters(notification: Notification): JSONObject? = runCatching {
        JSONObject(notification.extras.getString("miui.focus.param") ?: return null).let {
            it.optJSONObject("param_v2") ?: it
        }
    }.getOrNull()

    fun bind(context: Context, root: View, notification: Notification, execute: (Int) -> Unit): Boolean =
        actions(notification).isNotEmpty() && NativeRearTimerScene.bind(context, root, notification, true, execute)
}
