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
import kotlinx.coroutines.CancellationException
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
    val lastAttempt by cloudDao.observeState(CloudSyncConfig.LAST_ATTEMPT).collectAsState(initial = null)
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
                Text("Pending changes: $pending")
                Text("Last successful upload: ${lastSuccess?.value ?: "none"}")
                Text("Last sync attempt: ${lastAttempt?.value ?: "none"}")
                if (!lastError?.value.isNullOrBlank()) Text("Last sync problem: ${lastError?.value}")
                Text("Primary Phone ID: $deviceUuid")
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("DEV Device ID", deviceUuid))
                    information = "Device ID copied."
                }) { Text("Copy Device ID") }

                Text("Invoice Photos are ALWAYS local. Only Items, Kitchens, Purchases, Extra Lots and Cash are uploaded.")
                if (!enabled) {
                    Text("IMPORTANT: V2.31 installs separately from V2.28 and V2.30 because GitHub DEV APK signing differs. Sign in again here. To sync existing V2.28 records, export its JSON backup and Restore into THIS V2.31 app BEFORE enabling. Original invoice images are not in JSON and stay in V2.28.")
                    androidx.compose.foundation.layout.Row {
                        Checkbox(
                            checked = reviewedBackup,
                            onCheckedChange = { reviewedBackup = it },
                            enabled = !busy
                        )
                        Text("I have restored my V2.28 JSON backup OR I intentionally want a fresh Cloud dataset.")
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
                    Text("A connected network triggers Background Sync. The server acknowledges each record before the local queue is cleared.")
                    Text("Sync Now uploads directly from this phone and reports the actual result. " +
                        "The usual 15-minute background sync remains active.")
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            information = "Connecting and uploading pending changes…"
                            scope.launch {
                                val engine = CloudSyncEngine(context)
                                try {
                                    // Do not queue another KEEP job: an existing retry can
                                    // suppress it. Run a bounded upload immediately on IO.
                                    // V2.32 CloudSyncRunGate serializes overlapping workers.
                                    val remaining = withContext(Dispatchers.IO) {
                                        engine.drain(maxChanges = 100)
                                    }
                                    information = if (remaining == 0) {
                                        "Sync verified. Pending changes: 0. " +
                                            "Refresh the Cloud dashboard to see uploaded values."
                                    } else {
                                        "Cloud accepted this batch; $remaining pending " +
                                            "change(s) remain. Background sync is scheduled."
                                    }
                                    if (remaining > 0) CloudSyncScheduler.kick(context)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    val detail = e.message?.take(160)
                                        ?: "Cloud request failed; please check login and network."
                                    withContext(Dispatchers.IO) {
                                        engine.recordFailure(detail)
                                    }
                                    information = "Sync failed: $detail. " +
                                        "Your local pending changes remain saved."
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "Syncing…" else "Sync Now — Verify Upload") }
                }
                if (information.isNotBlank()) Text(information)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } }
    )
}
