package hk.uwu.reareye.ui.components.config

import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.config.ConfigItem
import hk.uwu.reareye.utils.NativeNotificationPrivacy
import hk.uwu.reareye.utils.RootHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** This switch writes the OEM setting, never an independent module preference. */
@Composable
internal fun NativeNotificationPrivacyInput(item: ConfigItem) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    var checked by remember { mutableStateOf(NativeNotificationPrivacy.DEFAULT_ENABLED) }
    var busy by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var canEdit by remember { mutableStateOf(false) }
    val key = NativeNotificationPrivacy.FACE_DETAILS
    suspend fun read(): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val policy = NativeNotificationPrivacy.policy(context)
            val forced = !policy.allowPrivate || Settings.Secure.getInt(context.contentResolver,
                NativeNotificationPrivacy.REAR_DETAILS, 0) == 0
            policy.hidesDetails to (policy.showNotifications && !forced)
        }.getOrElse {
            val value = RootHelper.executeRootCommandOutput("settings --user current get secure $key")
                .trim().toIntOrNull()
            (value?.let { it != 0 } ?: NativeNotificationPrivacy.DEFAULT_ENABLED) to true
        }
    }
    LaunchedEffect(revision) { read().let { checked = it.first; canEdit = it.second } }
    DisposableEffect(context, lifecycle) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { revision++ }
        }
        NativeNotificationPrivacy.settings.forEach { setting ->
            runCatching { context.contentResolver.registerContentObserver(Settings.Secure.getUriFor(setting), false, observer) }
        }
        val resumed = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) revision++ }
        lifecycle.addObserver(resumed)
        onDispose { context.contentResolver.unregisterContentObserver(observer); lifecycle.removeObserver(resumed) }
    }
    ConfigSwitchRow(
        title = stringResource(item.titleRes),
        summary = item.descriptionRes?.let { stringResource(it) },
        checked = checked,
        enabled = canEdit && !busy,
        onCheckedChange = { value ->
            if (!busy) scope.launch {
                busy = true
                val success = withContext(Dispatchers.IO) {
                    runCatching { Settings.Secure.putInt(context.contentResolver, key, if (value) 1 else 0) }
                        .getOrDefault(false) || RootHelper.executeRootCommandSuccess(
                        "settings --user current put secure $key ${if (value) 1 else 0}")
                }
                read().let { checked = it.first; canEdit = it.second }
                if (!success || checked != value) Toast.makeText(context, R.string.rear_notification_privacy_failed, Toast.LENGTH_LONG).show()
                busy = false
            }
        },
    )
}
