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
    var showMigrationBlockers by remember { mutableStateOf(false) }

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
                    val catalog = result.catalogAudit
                    Text("Master Item Reconciliation", fontWeight = FontWeight.Bold)
                    Text(
                        if (catalog.fullyMatched)
                            "MATCHED: ${catalog.matchedActive} active names and categories."
                        else "REVIEW: ${catalog.matchedActive} active items matched.",
                        color = if (catalog.fullyMatched) ProColors.green else ProColors.red,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Phone active: ${catalog.localActive}  |  Cloud active: ${catalog.remoteActive}" +
                            "  |  Archived in Cloud: ${catalog.remoteArchived}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (!catalog.fullyMatched) {
                        if (catalog.onlyOnPhone.isNotEmpty()) {
                            Text("Only on phone: " + catalog.onlyOnPhone.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (catalog.onlyInCloud.isNotEmpty()) {
                            Text("Only in cloud: " + catalog.onlyInCloud.joinToString(", "),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (catalog.differentCategory.isNotEmpty()) {
                            Text("Category mismatch: " + catalog.differentCategory.joinToString(", "),
                                color = ProColors.red, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider()
                    Text("Financial Record Reconciliation",
                        fontWeight = FontWeight.Bold)
                    val financial = result.financialAudit
                    Text(
                        if (financial.fullyMatched)
                            "MATCHED: Every transaction fingerprint matches."
                        else "REVIEW REQUIRED: Differences detected in financial records.",
                        color = if (financial.fullyMatched) ProColors.green else ProColors.red,
                        fontWeight = FontWeight.Bold
                    )
                    Text("Checks business date, kitchen, item identity, purchased/required " +
                        "quantity, price per kg and cash amount — not just row counts.",
                        style = MaterialTheme.typography.bodySmall)
                    for (part in financial.sections) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(part.type.replace('_', ' '), modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall)
                            Text("${part.matchedRows}/${part.phoneCount}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (part.matched) ProColors.green else ProColors.red,
                                fontWeight = FontWeight.Bold)
                        }
                        if (!part.matched) {
                            Text("Phone-only: ${part.phoneOnly} | Cloud-only: ${part.cloudOnly}",
                                color = ProColors.red,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (financial.unmappedPhoneItems > 0 ||
                        financial.unmappedCloudItems > 0) {
                        Text("Unmapped item links: Phone ${financial.unmappedPhoneItems}, " +
                            "Cloud ${financial.unmappedCloudItems}",
                            color = ProColors.red,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (financial.fullyMatched) {
                        Text("This confirms content parity, NOT cloud UUID identity. " +
                            "Cloud upload stays locked until the migration and " +
                            "single-writer cutover are separately approved.",
                            color = ProColors.muted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                    Text("Cloud UUID Mapping Verification", fontWeight = FontWeight.Bold)
                    val identity = result.uuidAudit
                    Text(
                        "This checks the actual UUID of each matching record. " +
                            "An identical Item name or purchase amount does NOT mean " +
                            "the Cloud UUID is the same.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Identical UUIDs: ${identity.equalIds} | Different UUIDs: ${identity.differentIds}",
                        color = if (identity.differentIds == 0) ProColors.green else ProColors.red,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "Pending local uploads: ${identity.pendingUploadOperations} — not sent",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (identity.pendingUploadOperations > 0) ProColors.red else ProColors.muted,
                        fontWeight = FontWeight.Bold
                    )
                    for (section in identity.sections) {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(section.type.replace('_', ' ').replaceFirstChar { it.uppercase() },
                                color = ProColors.navy, fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Phone ${section.phoneRecords} / Cloud ${section.cloudRecords}   " +
                                    "UUID same ${section.matchingUuid}, different ${section.differentUuid}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (section.absentLocalUuid > 0 ||
                                section.ambiguous > 0 || section.phoneOnly > 0 ||
                                section.cloudOnly > 0) {
                                Text(
                                    "Missing phone UUID ${section.absentLocalUuid}; " +
                                        "Ambiguous ${section.ambiguous}; " +
                                        "Phone-only ${section.phoneOnly}; Cloud-only ${section.cloudOnly}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ProColors.red
                                )
                            }
                        }
                    }
                    if (identity.archivedRemoteRows > 0) {
                        Text("Cloud archived financial rows: ${identity.archivedRemoteRows}. " +
                            "Historical tombstones must remain protected.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        "NO WRITER APPROVAL. This report does not save UUID mappings, " +
                            "clear pending changes, enroll a device, or upload records.",
                        color = ProColors.red,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall
                    )
                    HorizontalDivider()
                    Text("Safe Migration Dry Run", fontWeight = FontWeight.Bold)
                    val dryRun = result.migrationDryRun
                    Text(
                        "Unique UUID candidates: ${dryRun.uniqueCandidates}/${dryRun.phoneRecords}" +
                            "  |  Cloud records: ${dryRun.cloudRecords}",
                        fontWeight = FontWeight.Bold,
                        color = if (dryRun.contentMatches && dryRun.oneToOneCandidateCoverage)
                            ProColors.green else ProColors.red
                    )
                    Text(
                        "Unresolved/ambiguous: ${dryRun.unsafeOrUnmapped}" +
                            "  |  Preserved archived items: ${dryRun.protectedCloudArchivedItems}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Unsent local operations: ${dryRun.pendingLocalOperations}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (dryRun.pendingLocalOperations > 0)
                            ProColors.red else ProColors.muted
                    )
                    Text(
                        "CUTOVER BLOCKED — read-only planning only. No UUID rebind, " +
                            "outbox reset, cloud upload or writer enrollment.",
                        fontWeight = FontWeight.Bold,
                        color = ProColors.red,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    TextButton(
                        onClick = { showMigrationBlockers = !showMigrationBlockers },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (showMigrationBlockers) "Hide Migration Safeguards"
                            else "Show Migration Safeguards (${dryRun.blockers.size})")
                    }
                    if (showMigrationBlockers) {
                        for (blocker in dryRun.blockers) {
                            Text("• $blocker",
                                style = MaterialTheme.typography.bodySmall,
                                color = ProColors.muted)
                        }
                    }
                    HorizontalDivider()
                    Text("Record Comparison (counts)", fontWeight = FontWeight.Bold)
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
                        "Historical purchases, requisitions, cash and archived " +
                            "items remain protected in the existing Cloud workspace. " +
                            "Catalog-name matching is NOT enough to establish " +
                            "financial record UUID mappings. No upload is enabled.",
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
