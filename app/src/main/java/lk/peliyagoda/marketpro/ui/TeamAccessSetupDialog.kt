package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.cloud.CloudStaffAuth
import kotlinx.coroutines.launch

/**
 * Offline builds NEVER collect passwords and do not contact Cloud.
 * The Staff Login action is only visible when independently audited server
 * RPC+enrollment and build gates are enabled in a later coordinated release.
 * Even successful Auth login is identity only: it grants ZERO workspace rights.
 */
@Composable
fun TeamAccessSetupDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val staffAuth = remember(context) { CloudStaffAuth(context) }
    var showStaff by remember { mutableStateOf(true) }
    var showCatalogReview by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    // Password stays only in ephemeral Compose state, never SavedState or logs.
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    val liveEnrollment = !BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
        BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED &&
        BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED

    if (!showCatalogReview) AlertDialog(
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
                        "Use your OWN verified Staff email account. " +
                            "Never share or enter the Owner password on this phone."
                    )
                    if (liveEnrollment) {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Staff Email") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !busy
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Staff Password") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !busy
                        )
                        Button(
                            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
                            onClick = {
                                val attempt = password
                                password = ""
                                busy = true
                                notice = ""
                                scope.launch {
                                    try {
                                        staffAuth.signInStaff(email,attempt)
                                        notice = "Staff identity verified. Cloud data access " +
                                            "still requires Owner-approved device enrollment."
                                    } catch (_: Exception) {
                                        notice = "Staff login not accepted. Check verified " +
                                            "email, credentials and server readiness."
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        ) { Text("Sign In As Staff") }
                        if (busy) CircularProgressIndicator()
                    } else {
                        OutlinedTextField(
                            value = "",
                            onValueChange = {},
                            enabled = false,
                            label = { Text("Staff Email — Coming With Cloud Activation") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Text(
                            "Offline Preview: Staff password entry, sign-in, invitations " +
                                "and network sync are disabled. No credentials are collected."
                        )
                    }
                    Text(
                        "After Owner and server activation: today’s entries may be " +
                            "corrected any number of times; previous days are " +
                            "read-only on Staff Phone, based on Sri Lanka server date."
                    )
                } else {
                    Text("Owner — Team Access Control",
                        style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { showCatalogReview = true }) {
                        Text("Review Cloud / Phone Item Mapping")
                    }
                    Text("Catalog Review Is Read-Only. Owner Approval Is Not Yet Active.")
                    Text("1. Invite a separately verified Staff Account.")
                    Text("2. Share a one-time, 30-minute invitation securely.")
                    Text("3. Staff accepts invitation on their designated phone.")
                    Text("4. Server-verified Owner activates read-only access first.")
                    Text("5. Staff write access requires a separate fully tested cutover.")
                    Text(
                        "Owner may revoke the Staff phone. Existing V3.3.7 Cloud " +
                            "writer and Dashboard remain unchanged."
                    )
                }
                if (notice.isNotBlank()) {
                    Text(notice,color=ProColors.teal,
                        style=MaterialTheme.typography.bodyMedium)
                }
                Text(
                    if (liveEnrollment)
                        "Identity sign-in alone NEVER enables Cloud writes. " +
                            "Device enrollment and server authorization are mandatory."
                    else
                        "Development Preview — Sign-In and Team Activation are " +
                            "NOT connected to live Supabase.",
                    color = ProColors.teal,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
    if (showCatalogReview) {
        OwnerCatalogReviewDialog(onDismiss = { showCatalogReview = false })
    }
}
