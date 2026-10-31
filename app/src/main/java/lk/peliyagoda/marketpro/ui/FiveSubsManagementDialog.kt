package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import lk.peliyagoda.marketpro.cloud.MarketProActiveRole
import lk.peliyagoda.marketpro.cloud.MarketProSubsAdminClient
import lk.peliyagoda.marketpro.cloud.TeamRole

/**
 * Owner-only member approval. No passwords or OTP codes are collected here.
 * Backend independently requires fresh Owner MFA, active Owner device and
 * an email-confirmed staff identity. Phone key approval remains separate.
 */
@Composable
internal fun FiveSubsManagementDialog(onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val owner = remember { MarketProActiveRole.current(app) == TeamRole.OWNER }
    val client = remember(app) { MarketProSubsAdminClient(app) }
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Manage Subs • 5 Accounts") },
        text = {
            Column {
                Text(
                    "Each Subs member needs a different verified Cloud login. " +
                    "One approved writer phone per account; up to five Subs " +
                    "writers can be active. Admin remains separate."
                )
                Text(
                    "Before approval, complete fresh Owner Authenticator " +
                    "verification in Security & Team Sync. Then enter the " +
                    "new member's verified email.",
                    modifier = Modifier.padding(top = 12.dp)
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it.trim() },
                    label = { Text("Subs verified email") },
                    singleLine = true,
                    enabled = owner && !busy,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                )
                if (notice.isNotEmpty()) {
                    Text(notice, modifier = Modifier.padding(top = 12.dp))
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Close") }
        },
        confirmButton = {
            Button(
                enabled = owner && !busy && email.contains('@'),
                onClick = {
                    busy = true
                    notice = ""
                    scope.launch {
                        try {
                            notice = client.approveVerifiedMember(email)
                        } catch (error: Exception) {
                            notice = error.message?.take(160) ?: "Approval failed safely."
                        } finally {
                            busy = false
                        }
                    }
                }
            ) { Text(if (busy) "Approving..." else "Approve Subs") }
        }
    )
}
