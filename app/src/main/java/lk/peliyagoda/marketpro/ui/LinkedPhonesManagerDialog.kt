package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.cloud.CloudPhoneRegistryClient
import kotlinx.coroutines.launch

/**
 * Phone model and label are DISPLAY ONLY. They cannot uniquely identify a
 * physical Android phone, so never automatically merge records by label.
 * This screen lets the owner revoke exactly one confirmed device UUID.
 */
@Composable
internal fun LinkedPhonesManagerDialog(
    client: CloudPhoneRegistryClient,
    onClose: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<CloudPhoneRegistryClient.Snapshot?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var showOldOwners by remember { mutableStateOf(false) }
    var toRemove by remember { mutableStateOf<CloudPhoneRegistryClient.Phone?>(null) }
    var confirmOwnerRecovery by remember { mutableStateOf(false) }

    fun execute(operation: suspend () -> Unit) {
        if (busy) return
        busy = true
        message = ""
        scope.launch {
            try {
                operation()
                snapshot = client.listPhones()
            } catch (e: Exception) {
                message = e.message?.take(150) ?: "Phone management request failed"
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(client) {
        execute { snapshot = client.listPhones() }
    }

    val data = snapshot
    AlertDialog(
        onDismissRequest = { if (!busy) onClose() },
        containerColor = Color.White,
        title = { Text("Manage Linked Phones") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 500.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Each phone is identified by its own Device ID. " +
                        "Repeated names are not automatically merged.",
                    style = MaterialTheme.typography.bodySmall
                )
                TextButton(enabled = !busy, onClick = {
                    execute { snapshot = client.listPhones() }
                }) { Text("Refresh Phone List") }

                if (data == null) {
                    Text(if (busy) "Loading phones..." else "No phone list available")
                } else {
                    val approved = data.phones.filter { it.enabled }
                    val pending = data.phones.filter {
                        !it.enabled && it.role == "editor"
                    }
                    val oldOwners = data.phones.filter {
                        !it.enabled && it.role == "owner"
                    }
                    val duplicateGroups = approved.filter { it.role == "editor" }
                        .groupBy { it.userId to it.label }
                        .filterValues { it.size > 1 }
                    Text(
                        approved.size.toString() + " Approved  /  " +
                            pending.size + " Pending  /  " +
                            oldOwners.size + " Previous Owner",
                        fontWeight = FontWeight.Bold
                    )
                    if (duplicateGroups.isNotEmpty()) {
                        Text(
                            "Same Team account and phone label have multiple approvals. " +
                                "Check which Device ID is in use before removing an extra one.",
                            color = Color(0xFF9B5D13)
                        )
                    }

                    if (approved.isNotEmpty()) {
                        Text("Approved Phones", fontWeight = FontWeight.Bold)
                        approved.forEach { phone ->
                            LinkedDeviceRow(
                                phone = phone,
                                ownerCanManage = data.role == "owner",
                                busy = busy,
                                onRemove = { toRemove = phone }
                            )
                        }
                    }
                    if (pending.isNotEmpty()) {
                        Text("Pending / Unlinked Team Phones", fontWeight = FontWeight.Bold)
                        pending.forEach { phone ->
                            LinkedDeviceRow(
                                phone = phone,
                                ownerCanManage = data.role == "owner",
                                busy = busy,
                                onRemove = { toRemove = phone },
                                onApprove = if (data.role == "owner") {
                                    {
                                        execute {
                                            client.decideTeamPhone(phone.id, true)
                                            message = "Team approved. Activate / Sync on that phone."
                                        }
                                    }
                                } else null
                            )
                        }
                    }
                    if (oldOwners.isNotEmpty() && data.role == "owner") {
                        TextButton(onClick = { showOldOwners = !showOldOwners }) {
                            Text(
                                if (showOldOwners) "Hide Previous Owner Phones"
                                else "Show Previous Owner Phones (" +
                                    oldOwners.size.toString() + ")"
                            )
                        }
                        if (showOldOwners) oldOwners.forEach { phone ->
                            LinkedDeviceRow(
                                phone = phone,
                                ownerCanManage = true,
                                busy = busy,
                                onRemove = { toRemove = phone }
                            )
                        }
                    }
                    if (data.role == "owner") {
                        Button(enabled = !busy, onClick = { confirmOwnerRecovery = true }) {
                            Text("Secure Re-Link Owner Phone")
                        }
                    }
                    if (data.phones.isEmpty()) Text("No linked phone records")
                }
                if (message.isNotBlank()) Text(message, color = ProColors.teal)
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onClose) { Text("Close") }
        }
    )

    val selected = toRemove
    if (selected != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) toRemove = null },
            containerColor = Color.White,
            title = { Text("Remove This Phone?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(selected.label, fontWeight = FontWeight.Bold)
                    Text("Device ID ..." + selected.id.takeLast(8))
                    Text(
                        if (selected.enabled)
                            "This approved phone will immediately lose Cloud access. " +
                                "Ensure its unsynced changes are uploaded and it is " +
                                "not the phone still in use."
                        else
                            "This inactive record will be hidden from the list."
                    )
                    Text("Cloud Purchase, Kitchen and finance records are not deleted.")
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { toRemove = null }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    val id = selected.id
                    toRemove = null
                    execute {
                        message = client.removePhoneFromList(id)
                    }
                }) { Text("Remove Phone") }
            }
        )
    }
    if (confirmOwnerRecovery) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmOwnerRecovery = false },
            containerColor = Color.White,
            title = { Text("Recover This Owner Phone?") },
            text = {
                Text(
                    "Only use this on your real Owner phone. Verify a fresh Owner " +
                        "Authenticator code first. The previously approved Owner UUID " +
                        "will be revoked. No local or unsynced data is transferred."
                )
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = {
                    confirmOwnerRecovery = false
                }) { Text("Cancel") }
            },
            confirmButton = {
                Button(enabled = !busy, onClick = {
                    confirmOwnerRecovery = false
                    execute { message = client.secureRelinkThisOwnerPhone() }
                }) { Text("Confirm Secure Re-Link") }
            }
        )
    }
}

@Composable
private fun LinkedDeviceRow(
    phone: CloudPhoneRegistryClient.Phone,
    ownerCanManage: Boolean,
    busy: Boolean,
    onRemove: () -> Unit,
    onApprove: (() -> Unit)? = null
) {
    Surface(
        color = Color(0xFFF5FAFB),
        border = BorderStroke(1.dp, Color(0xFFD7E6E8)),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(phone.label, fontWeight = FontWeight.Bold)
            Text(
                (if (phone.role == "owner") "Owner" else "Team") +
                    "  /  " + (if (phone.enabled) "APPROVED" else "PENDING / UNLINKED"),
                color = if (phone.enabled) ProColors.teal else Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "ID ..." + phone.id.takeLast(8),
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
            if (ownerCanManage && (phone.role == "editor" || !phone.enabled)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (onApprove != null) {
                        OutlinedButton(enabled = !busy, onClick = onApprove) {
                            Text("Approve")
                        }
                    }
                    OutlinedButton(enabled = !busy, onClick = onRemove) {
                        Text("Remove Phone")
                    }
                }
            }
        }
    }
}
