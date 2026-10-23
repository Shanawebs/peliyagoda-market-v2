package lk.peliyagoda.marketpro.ui

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.cloud.CloudPreflightReport
import lk.peliyagoda.marketpro.cloud.CloudReadinessInspector
import lk.peliyagoda.marketpro.cloud.CloudReadinessRules
import kotlinx.coroutines.launch

/** Preview's cloud dialog is read-only by design: never expose an upload button. */
@Composable
fun CloudPreflightDialog(onDismiss: () -> Unit, onOpenOwnerLogin: () -> Unit) {
    val context = LocalContext.current
    val inspector = remember(context) { CloudReadinessInspector(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<CloudPreflightReport?>(null) }

    AlertDialog(
        onDismissRequest = { if (!checking) onDismiss() },
        title = {
            Text("Cloud Connection & Safety Check", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 490.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(13.dp)
            ) {
                Text(
                    "Phase 1 — Read-only connection to existing Market Pro Cloud. " +
                        "Local records are not uploaded, merged or deleted. " +
                        "Original V2.31 cloud writer stays unchanged.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text("☁  Supabase Auth + private workspace RLS", fontWeight = FontWeight.Bold)
                Button(
                    onClick = {
                        checking = true
                        error = ""
                        report = null
                        scope.launch {
                            try {
                                report = inspector.inspect()
                            } catch (e: Exception) {
                                error = e.message?.take(180)
                                    ?: "Read-only cloud check unavailable. Sign in first."
                            } finally {
                                checking = false
                            }
                        }
                    },
                    enabled = !checking,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (checking) "Checking Cloud…" else "Check My Cloud Connection")
                }
                TextButton(onClick = {
                    onDismiss()
                    onOpenOwnerLogin()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Owner Login / Reconnect")
                }

                report?.let { result ->
                    Text("Owner authentication: Verified", color = ProColors.green)
                    Text(
                        "This preview device writer permission: " +
                            if (result.deviceRegisteredAsWriter) "Present (still locked)" else "Not enrolled",
                        color = ProColors.navy,
                        fontWeight = FontWeight.SemiBold
                    )
                    HorizontalDivider()
                    Text("Record Comparison (counts only)", fontWeight = FontWeight.Bold)
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text("Record Type", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                        Text("Phone", modifier = Modifier.weight(.55f), fontWeight = FontWeight.Bold)
                        Text("Cloud", modifier = Modifier.weight(.45f), fontWeight = FontWeight.Bold)
                    }
                    for ((key, cloudCount) in result.remote) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(key.replace('_', ' '), modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall)
                            Text(result.local[key].toString(), modifier = Modifier.weight(.55f),
                                style = MaterialTheme.typography.bodySmall)
                            Text(cloudCount.toString(), modifier = Modifier.weight(.45f),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider()
                    Text("Cloud upload: LOCKED — safe preview mode.",
                        color = ProColors.red, fontWeight = FontWeight.Bold)
                    Text(
                        "Do not enable a second writer or restore another app's " +
                            "backup over this preview without reconciling cloud UUIDs. " +
                            result.writeBlockedReason,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (error.isNotBlank()) {
                    Text(error, color = ProColors.red, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(
                    onClick = {
                        val intent = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(CloudReadinessInspector.WEB_DASHBOARD)
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }
                            .onFailure { error = "No browser found to open dashboard" }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Open Cloud Web Dashboard ↗") }
                Text(
                    "Invoice photos remain on this phone. This screen never " +
                        "sends passwords, records or photos to ChatGPT.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ProColors.muted
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !checking) { Text("Close") }
        }
    )
}
