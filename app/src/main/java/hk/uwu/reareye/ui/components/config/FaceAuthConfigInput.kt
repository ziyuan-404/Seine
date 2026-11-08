package hk.uwu.reareye.ui.components.config

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.config.ConfigItem
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.config.rememberRemotePrefsStatusRevision
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ui.feice.mochi.basic.MochiButton
import ui.feice.mochi.basic.MochiButtonDefaults
import ui.feice.mochi.basic.MochiButtonStyle
import ui.feice.mochi.basic.MochiDialog
import ui.feice.mochi.basic.MochiDialogTextField
import ui.feice.mochi.basic.Text
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.theme.MochiTheme

private const val READING_MILLIS = 5_000L

@Composable
internal fun FaceAuthConfigInput(
    item: ConfigItem,
    defaultValue: Boolean,
    prefsManager: PrefsManager,
    onPreferenceChanged: (ConfigItem) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remoteRevision = rememberRemotePrefsStatusRevision()
    val ready = remember(remoteRevision) { prefsManager.isRemoteReady() }
    var checked by remember(item.key, remoteRevision) {
        mutableStateOf(prefsManager.getBoolean(item.key, defaultValue))
    }
    var confirming by remember(item.key) { mutableStateOf(false) }
    var attempt by remember(item.key) { mutableIntStateOf(0) }
    var saving by remember(item.key) { mutableStateOf(false) }

    fun save(value: Boolean) {
        if (saving) return
        saving = true
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { prefsManager.prefs.edit().putBoolean(item.key, value).commit() }
                    .getOrDefault(false)
            }
            saving = false
            if (saved) {
                checked = value
                onPreferenceChanged(item)
            } else {
                Toast.makeText(context, R.string.face_auth_save_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    ConfigSwitchRow(
        title = stringResource(item.titleRes),
        summary = item.descriptionRes?.let { stringResource(it) },
        checked = checked,
        enabled = ready && !saving,
        onCheckedChange = { value ->
            if (value) {
                save(true)
            } else {
                attempt++
                confirming = true
            }
        },
    )
    FaceAuthDisableDialog(
        visible = confirming,
        attempt = attempt,
        onDismiss = { confirming = false },
        onConfirm = { save(false) },
    )
}

@Composable
private fun FaceAuthDisableDialog(
    visible: Boolean,
    attempt: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val termsScroll = rememberScrollState()
    var now by remember(attempt) { mutableLongStateOf(0L) }
    var readAfter by remember(attempt) { mutableLongStateOf(0L) }
    var acceptedTerms by remember(attempt) { mutableStateOf(false) }
    var entered by remember(attempt) { mutableStateOf("") }

    LaunchedEffect(visible, attempt) {
        if (!visible) return@LaunchedEffect
        now = SystemClock.elapsedRealtime()
        readAfter = now + READING_MILLIS
        termsScroll.scrollTo(0)
        while (now < readAfter) {
            delay(250)
            now = SystemClock.elapsedRealtime()
        }
    }

    val confirmationPhrase = stringResource(R.string.face_auth_confirmation_phrase)
    val canRead = readAfter > 0L && now >= readAfter && !termsScroll.canScrollForward
    val cancelLabel = stringResource(android.R.string.cancel)

    MochiDialog(
        visible = visible,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.face_auth_disable_warning),
        actions = persistentListOf(),
        icon = { MochiIonicon("warning-outline", tint = MochiTheme.colorScheme.error, size = 28.dp) },
    ) {
        if (!acceptedTerms) {
            Text(
                text = stringResource(R.string.face_auth_disable_terms),
                modifier = Modifier.heightIn(max = 180.dp).verticalScroll(termsScroll),
                style = MochiTheme.textStyles.body,
            )
            val wait = ((readAfter - now + 999L) / 1_000L).coerceIn(0L, READING_MILLIS / 1_000L)
            MochiButton(
                onClick = { acceptedTerms = true },
                enabled = canRead,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        wait > 0 -> stringResource(R.string.face_auth_read_wait, wait.toInt())
                        termsScroll.canScrollForward -> stringResource(R.string.face_auth_scroll_to_end)
                        else -> stringResource(R.string.face_auth_read_and_agree)
                    },
                )
            }
            MochiButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                style = MochiButtonStyle.Tonal,
            ) { Text(cancelLabel) }
        } else {
            Text(
                stringResource(R.string.face_auth_type_confirmation, confirmationPhrase),
                style = MochiTheme.textStyles.body,
            )
            MochiDialogTextField(
                value = entered,
                onValueChange = { entered = it },
                placeholder = stringResource(R.string.face_auth_confirmation_input),
                autoFocus = false,
            )
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MochiButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    style = MochiButtonStyle.Tonal,
                ) { Text(cancelLabel) }
                MochiButton(
                    onClick = {
                        if (entered == confirmationPhrase) {
                            onConfirm()
                            onDismiss()
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    enabled = entered == confirmationPhrase,
                    style = MochiButtonStyle.Tonal,
                    colors = MochiButtonDefaults.colors(MochiButtonStyle.Tonal).copy(
                        content = MochiTheme.colorScheme.error,
                    ),
                ) { Text(stringResource(R.string.face_auth_confirm_disable)) }
            }
        }
    }
}
