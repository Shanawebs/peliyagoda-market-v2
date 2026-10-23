package lk.peliyagoda.marketpro.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import lk.peliyagoda.marketpro.cloud.CloudSyncScheduler
import lk.peliyagoda.marketpro.cloud.FreshExistingWorkspaceAttach
import lk.peliyagoda.marketpro.cloud.FreshAttachState
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import lk.peliyagoda.marketpro.data.MarketDatabase
import kotlinx.coroutines.launch

/**
 * V3.37 standalone NEW-TRANSACTION test only. No automatic server enrollment.
 * Activates solely when the owner has arranged a verified single-writer handover
 * AND the fresh phone has no historical financial records.
 */
@Composable
fun FreshCloudSyncDialog(onDismiss: () -> Unit, onOpenOwnerLogin: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val cloud = remember(context) { MarketDatabase.get(context).cloudDao() }
    val device = remember(context) { CloudDeviceIdentity.get(context) }
    val attach = remember(context) { FreshExistingWorkspaceAttach(context) }
    val pending by cloud.observePendingCount().collectAsState(initial = 0)
    val enrollment by cloud.observeState(CloudSyncConfig.ENABLED)
        .collectAsState(initial = null)
    val lastUpload by cloud.observeState(CloudSyncConfig.LAST_SUCCESS)
        .collectAsState(initial = null)
    val lastError by cloud.observeState(CloudSyncConfig.LAST_ERROR)
        .collectAsState(initial = null)
    val attached by cloud.observeState(FreshAttachState.PROTOCOL)
        .collectAsState(initial = null)
    val enabled = enrollment?.value == "true" &&
        attached?.value == FreshAttachState.VERSION

    val scope = rememberCoroutineScope()
    var confirmed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Cloud Sync — Fresh V3 Test") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(if (enabled) "Automatic Cloud Sync: ENABLED"
                    else "Automatic Cloud Sync: LOCKED — Owner Handover Required")
                Text("Pending New Changes: $pending")
                Text("Last Successful Upload: ${lastUpload?.value ?: "None"}")
                if (!lastError?.value.isNullOrBlank()) {
                    Text("Last Sync Problem: ${lastError?.value}")
                }
                Text("Fresh V3 Device ID: $device")
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
                        as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Fresh V3 device ID",device))
                    result = "Device ID copied. Keep it available for secure writer registration."
                }) { Text("Copy This Device ID") }

                Text(
                    "Uses the ORIGINAL Supabase workspace and existing web dashboard. " +
                    "All 330 historic demo records are preserved. Nothing is uploaded " +
                    "unless this device becomes the ONLY authorized Cloud writer."
                )
                TextButton(onClick = {
                    onDismiss()
                    onOpenOwnerLogin()
                }, enabled = !busy) { Text("Owner Sign In / Reconnect") }

                if (!enabled) {
                    Text(
                        "Fresh V3 Test: Do not restore a V2/V3.34 JSON backup into " +
                        "this separate test app. Its initial catalog must be clean " +
                        "and there must be ZERO local financial transactions. " +
                        "Cloud catalog UUIDs are matched without writing Cloud data."
                    )
                    Row {
                        Checkbox(
                            checked = confirmed,
                            onCheckedChange = { confirmed = it },
                            enabled = !busy
                        )
                        Text(
                            "The old writer has been retired by the owner, " +
                            "this new device is registered, and I want fresh-only upload."
                        )
                    }
                    Button(
                        onClick = {
                            busy = true
                            result = ""
                            scope.launch {
                                try {
                                    val receipt = attach.activate()
                                    result = "Fresh-only Cloud active: " +
                                        "${receipt.linkedActiveItems} existing catalog items linked; " +
                                        "${receipt.remoteArchivedItemsPreserved} old archived items protected. " +
                                        "New transactions will sync automatically."
                                } catch (e: Exception) {
                                    result = e.message?.take(230)
                                        ?: "Fresh attach rejected safely; no Cloud upload."
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        enabled = confirmed && !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (busy) "Verifying Cloud Writer…" else "Activate Fresh-Only Cloud Sync") }
                } else {
                    Text(
                        "New purchases, requisitions, extra lots and cash sync " +
                        "automatically on network connection. Existing demo rows " +
                        "are protected from duplicate reimport. Invoice photos stay local."
                    )
                    Button(onClick = {
                        CloudSyncScheduler.schedulePeriodic(context)
                        CloudSyncScheduler.kick(context)
                        result = "Sync requested. Local changes remain pending until Cloud ACK."
                    },enabled = !busy,modifier = Modifier.fillMaxWidth()) {
                        Text("Sync Now (Optional Diagnostic)")
                    }
                }
                TextButton(onClick = {
                    val intent = Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://marketpro-cloud-dashboard.vercel.app/")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(intent) }
                        .onFailure { result = "No browser available" }
                }) { Text("Open Existing Cloud Dashboard ↗") }
                if (result.isNotBlank()) Text(result)
                Text("This test version never uploads invoice images.")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
        }
    )
}
