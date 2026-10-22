package lk.peliyagoda.marketpro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.cloud.CloudSyncConfig
import lk.peliyagoda.marketpro.cloud.CloudSyncEngine
import lk.peliyagoda.marketpro.cloud.CloudSyncScheduler
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import lk.peliyagoda.marketpro.data.MarketDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Explicit manual opt-in; just signing in must NEVER initiate cloud import. */
@Composable
fun CloudSyncDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val cloudDao = remember(context) { MarketDatabase.get(context).cloudDao() }
    val deviceUuid = remember(context) { CloudDeviceIdentity.get(context) }
    val pending by cloudDao.observePendingCount().collectAsState(initial = 0)
    val newestEdit by cloudDao.observeLatestMutation().collectAsState(initial = null)
    val enrolledState by cloudDao.observeState(CloudSyncConfig.ENABLED).collectAsState(initial = null)
    val lastSuccess by cloudDao.observeState(CloudSyncConfig.LAST_SUCCESS).collectAsState(initial = null)
    val lastError by cloudDao.observeState(CloudSyncConfig.LAST_ERROR).collectAsState(initial = null)
    val enabled = enrolledState?.value == "true"
    val scope = rememberCoroutineScope()
    var reviewedBackup by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var information by remember { mutableStateOf("") }

    LaunchedEffect(enabled, newestEdit, pending) {
        if (enabled) {
            CloudSyncScheduler.schedulePeriodic(context)
            if (pending > 0) CloudSyncScheduler.kick(context)
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Cloud Sync — DEV V2.31") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Text(if (enabled) "Cloud Sync: ENABLED" else "Cloud Sync: NOT ENABLED")
                Text("Pending Changes: $pending")
                Text("Last Successful Upload: ${lastSuccess?.value ?: "none"}")
                if (!lastError?.value.isNullOrBlank()) Text("Last Sync Problem: ${lastError?.value}")
                Text("Primary Phone ID: $deviceUuid")
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("DEV Device ID", deviceUuid))
                    information = "Device ID copied."
                }) { Text("Copy Device ID") }

                Text("Invoice Photos Are ALWAYS Local. Only Items, Kitchens, Purchases, Extra Lots And Cash Are Uploaded.")
                if (!enabled) {
                    Text("IMPORTANT: V2.31 Installs Separately From V2.28 And V2.30 Because GitHub DEV APK Signing Differs. Sign In Again Here. To Sync Existing V2.28 Records, Export Its JSON Backup And Restore Into THIS V2.31 App BEFORE Enabling. Original Invoice Images Are Not In JSON And Stay In V2.28.")
                    androidx.compose.foundation.layout.Row {
                        Checkbox(
                            checked = reviewedBackup,
                            onCheckedChange = { reviewedBackup = it },
                            enabled = !busy
                        )
                        Text("I Have Restored My V2.28 JSON Backup OR I Intentionally Want A Fresh Cloud Dataset.")
                    }
                    Button(
                        enabled = reviewedBackup && !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val queued = withContext(Dispatchers.IO) {
                                        CloudSyncEngine(context).enableAfterOwnerConfirmation()
                                    }
                                    information = "Initial snapshot safely queued ($queued pending). Automatic sync enabled."
                                } catch (e: Exception) {
                                    information = e.message?.take(160) ?: "Cloud enrollment failed; no changes uploaded."
                                } finally { busy = false }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Enable Verified DEV Cloud Sync") }
                } else {
                    Text("A Connected Network Triggers Background Sync. The Server Acknowledges Each Record Before The Local Queue Is Cleared.")
                    Button(
                        enabled = !busy,
                        onClick = {
                            CloudSyncScheduler.kick(context)
                            information = "Sync work requested. Pending records remain until server confirmation."
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Sync Now") }
                }
                if (information.isNotBlank()) Text(information)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } }
    )
}
