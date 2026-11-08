package hk.uwu.reareye.ui.components.config

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import hk.uwu.reareye.R
import hk.uwu.reareye.ui.config.PrefsManager
import hk.uwu.reareye.ui.config.SeineConfigBackup
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ui.feice.mochi.basic.MochiDialog
import ui.feice.mochi.basic.MochiDialogAction
import ui.feice.mochi.basic.MochiDialogActionRole
import ui.feice.mochi.list.MochiListRow
import java.io.ByteArrayOutputStream

@Composable
internal fun ConfigBackupPage(prefsManager: PrefsManager, onRestored: () -> Unit) {
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<Map<String, Any?>?>(null) }

    fun message(id: Int) {
        Toast.makeText(context, id, Toast.LENGTH_SHORT).show()
    }

    fun work(onFailure: Int, action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message(onFailure)
            } finally {
                busy = false
            }
        }
    }

    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) work(R.string.seine_backup_export_failed) {
            withContext(Dispatchers.IO) {
                val json = SeineConfigBackup.export(prefsManager)
                val stream = requireNotNull(context.contentResolver.openOutputStream(uri, "wt"))
                stream.bufferedWriter(Charsets.UTF_8).use { it.write(json) }
            }
            message(R.string.seine_backup_exported)
        }
    }
    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) work(R.string.seine_backup_import_failed) {
            pendingRestore = withContext(Dispatchers.IO) {
                val stream = requireNotNull(context.contentResolver.openInputStream(uri))
                val json = stream.use { input ->
                    val buffer = ByteArray(8192)
                    val output = ByteArrayOutputStream()
                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        require(output.size() + count <= SeineConfigBackup.MAX_BYTES)
                        output.write(buffer, 0, count)
                    }
                    output.toString(Charsets.UTF_8.name())
                }
                SeineConfigBackup.parse(json)
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = ConfigUi.HorizontalInset, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ConfigCard {
            MochiListRow(stringResource(R.string.seine_backup_export), chevron = true, enabled = !busy,
                onClick = { createDocument.launch("seine-config.json") })
            MochiListRow(stringResource(R.string.seine_backup_copy), chevron = true, enabled = !busy,
                onClick = {
                    work(R.string.seine_backup_export_failed) {
                        val json = withContext(Dispatchers.IO) { SeineConfigBackup.export(prefsManager) }
                        clipboard.setPrimaryClip(ClipData.newPlainText("Seine", json))
                        message(R.string.seine_backup_copied)
                    }
                })
        }
        ConfigCard {
            MochiListRow(stringResource(R.string.seine_backup_import), chevron = true, enabled = !busy,
                onClick = { openDocument.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
            MochiListRow(stringResource(R.string.seine_backup_paste), chevron = true, enabled = !busy,
                onClick = {
                    work(R.string.seine_backup_import_failed) {
                        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                            ?.getItemAt(0)?.text?.toString().orEmpty()
                        pendingRestore = withContext(Dispatchers.IO) { SeineConfigBackup.parse(text) }
                    }
                })
        }
    }

    pendingRestore?.let { values ->
        MochiDialog(
            visible = true,
            title = stringResource(R.string.seine_backup_restore_confirm),
            message = stringResource(R.string.seine_backup_restore_summary, values.size),
            onDismissRequest = { pendingRestore = null },
            actions = persistentListOf(
                MochiDialogAction(stringResource(android.R.string.cancel), MochiDialogActionRole.Cancel),
                MochiDialogAction(stringResource(android.R.string.ok), MochiDialogActionRole.Primary) {
                    pendingRestore = null
                    work(R.string.seine_backup_import_failed) {
                        withContext(Dispatchers.IO) { SeineConfigBackup.restore(prefsManager, values) }
                        onRestored()
                        message(R.string.seine_backup_restored)
                    }
                },
            ),
        )
    }
}
