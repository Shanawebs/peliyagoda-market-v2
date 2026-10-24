package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.BuildConfig

/**
 * Preview only. Never collects or stores Owner/Staff password or invitation
 * secrets in the offline app. All actions must eventually be backed by real
 * authenticated server-side permissions before becoming interactive.
 */
@Composable
fun TeamAccessSetupDialog(onDismiss: () -> Unit) {
    var showStaff by remember { mutableStateOf(true) }
    val liveEnrollment = !BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
        BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED &&
        BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text("Team Account & Device Setup") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 510.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showStaff = true }) {
                        Text("Staff Phone",
                            fontWeight = if (showStaff) FontWeight.Bold else FontWeight.Normal)
                    }
                    TextButton(onClick = { showStaff = false }) {
                        Text("Owner Phone",
                            fontWeight = if (!showStaff) FontWeight.Bold else FontWeight.Normal)
                    }
                }
                if (showStaff) {
                    Text("Purchasing Team — Separate Login",
                        style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Team members must sign in with their OWN verified email. " +
                            "Never enter or share the Owner password on the staff phone."
                    )
                    // Not user-editable in the offline preview, so nobody can
                    // mistakenly submit credentials to a nonexistent backend.
                    OutlinedTextField(
                        value = "",
                        onValueChange = {},
                        enabled = false,
                        label = { Text("Staff Email — Coming With Cloud Activation") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text(
                        "Sign-in and one-time device invitation are disabled in this " +
                            "standalone offline preview. No credentials are collected."
                    )
                    Text(
                        "After server activation: enter today's purchases and correct " +
                            "them any number of times. Previous days are read-only. " +
                            "The server uses midnight Sri Lanka time."
                    )
                } else {
                    Text("Owner — Team Access Control",
                        style = MaterialTheme.typography.titleMedium)
                    Text("1. Invite a separately verified Staff Account.")
                    Text("2. Give a one-time, 30-minute invitation to the designated phone.")
                    Text("3. Staff accepts using their own login and device.")
                    Text(
                        "4. Owner activates READ-ONLY device access only after the " +
                            "server read policies and device-bound pull API pass checks."
                    )
                    Text(
                        "5. Staff write access remains unavailable until a separate, " +
                            "fully tested Owner-approved rollout."
                    )
                    Text(
                        "Owner can revoke a team phone independently; the current " +
                            "V3.3.7 Cloud writer and Dashboard stay unchanged."
                    )
                }
                Text(
                    if (liveEnrollment)
                        "Server enrollment integration must still be verified before use."
                    else
                        "Development Preview — Login, Invites and Owner Activation " +
                            "are NOT connected to live Supabase.",
                    color = ProColors.teal,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}
