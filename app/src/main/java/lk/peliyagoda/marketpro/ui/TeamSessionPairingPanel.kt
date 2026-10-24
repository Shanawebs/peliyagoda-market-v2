package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.cloud.TeamSessionPairingRpcClient
import lk.peliyagoda.marketpro.cloud.OneTimeOwnerInvitation
import lk.peliyagoda.marketpro.cloud.TeamSessionPairingPolicy
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import kotlinx.coroutines.launch

/**
 * V3.7.3 future pairing UI. In deployed preview builds it offers steps
 * only and NEVER collects password or invitation codes. All RPCs are behind
 * independent, currently FALSE BuildConfig server-activation switches.
 */
private val pairingEnabled: Boolean get() =
    TeamSessionPairingPolicy.deploymentReady(
        BuildConfig.MARKETPRO_LOCAL_PREVIEW,
        BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED,
        BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED,
        BuildConfig.MARKETPRO_STAFF_PAIRING_RPC_DEPLOYED
    )

@Composable
internal fun StaffSessionPairingPanel() {
    val context = LocalContext.current.applicationContext
    val enabled = pairingEnabled
    val scope = rememberCoroutineScope()
    val client = remember(context) { TeamSessionPairingRpcClient(context) }
    var oneTimeCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Secure Team Phone Pairing",
            style = MaterialTheme.typography.titleMedium)
        Text("Step 1: Sign In With Your Separate Verified Staff Account.")
        Text("Step 2: Tell The Owner This Phone's Device ID. " +
            "Never Share The Owner's Password.")
        Text("Step 3: Enter The Owner's One-Time Invitation On This Phone.")
        Text("Step 4: Owner Activates Read-Only Access First. " +
            "Writing Requires Separate Owner Approval.")
        if (enabled) {
            Text("This Phone ID: ${CloudDeviceIdentity.get(context)}")
            OutlinedTextField(
                value = oneTimeCode,
                onValueChange = { input ->
                    oneTimeCode = input.trim().take(64)
                },
                label = { Text("One-Time Pairing Code (64 Characters)") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                enabled = !busy
            )
            Button(
                enabled = !busy && oneTimeCode.matches(Regex("[0-9a-f]{64}")),
                onClick = {
                    val submittedCode = oneTimeCode
                    oneTimeCode = ""
                    busy = true
                    status = ""
                    scope.launch {
                        try {
                            client.staffClaimInvitation(submittedCode)
                            status = "Invitation Received By Server. " +
                                "Awaiting Owner Read-Only Activation. " +
                                "No Cloud Write Access Granted."
                        } catch (_: Exception) {
                            status = "Pairing Not Accepted. Verify Your Staff Login, " +
                                "Owner Code And Current Server Activation."
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Submit One-Time Code") }
        } else {
            Text("Offline Security Preview: Pairing code entry and Staff " +
                "Cloud access are disabled. No code or password is collected.")
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text("Pair Team Phone — Server Activation Required")
            }
        }
        if (status.isNotBlank()) Text(status,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun OwnerSessionPairingPanel() {
    val context = LocalContext.current.applicationContext
    val enabled = pairingEnabled
    val client = remember(context) { TeamSessionPairingRpcClient(context) }
    val scope = rememberCoroutineScope()
    var staffUserId by remember { mutableStateOf("") }
    var staffDeviceId by remember { mutableStateOf("") }
    var phoneLabel by remember { mutableStateOf("") }
    var issued by remember { mutableStateOf<OneTimeOwnerInvitation?>(null) }
    var confirmActivate by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Owner — Secure Staff Invitation",
            style = MaterialTheme.typography.titleMedium)
        Text("Invite A Separate, Email-Verified Staff User And Its Own Phone. " +
            "One-Time Codes Expire After 30 Minutes.")
        if (enabled) {
            OutlinedTextField(
                value = staffUserId,
                onValueChange = { staffUserId = it.trim().take(36) },
                label = { Text("Verified Staff Account UUID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,enabled = !busy
            )
            OutlinedTextField(
                value = staffDeviceId,
                onValueChange = { staffDeviceId = it.trim().take(36) },
                label = { Text("Staff Phone Device UUID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,enabled = !busy
            )
            OutlinedTextField(
                value = phoneLabel,
                onValueChange = { phoneLabel = it.take(100) },
                label = { Text("Phone Label") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,enabled = !busy
            )
            Button(
                enabled = !busy &&
                    TeamSessionPairingPolicy.validUuid(staffUserId) &&
                    TeamSessionPairingPolicy.validUuid(staffDeviceId) &&
                    phoneLabel.trim().isNotEmpty(),
                onClick = {
                    issued = null
                    confirmActivate = false
                    busy = true
                    status = ""
                    val user = staffUserId
                    val device = staffDeviceId
                    val label = phoneLabel.trim()
                    scope.launch {
                        try {
                            issued = client.ownerIssueInvitation(user,device,label)
                            status = "Invitation Issued Only. " +
                                "No Staff Read Or Write Access Has Been Activated."
                        } catch (_: Exception) {
                            status = "Owner Invitation Not Accepted. " +
                                "Check Verified Staff And Device IDs."
                        } finally { busy = false }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Issue One-Time Staff Invitation") }

            if (issued != null) {
                Text("Owner Only: Share This Code Privately With " +
                    "The Intended Staff Phone. Do Not Save Or Screenshot.")
                Text("One-Time Code: ${issued!!.code}")
                Text("Expires At: ${issued!!.expiresAt}")
                TextButton(onClick = { issued = null }) {
                    Text("Hide And Clear Code")
                }
            }
            if (confirmActivate) {
                Text("Confirm: Has Staff Accepted The Invitation From " +
                    "Its Own Phone? This Enables READ-ONLY Cloud Access.")
                Button(
                    enabled = !busy && TeamSessionPairingPolicy.validUuid(staffUserId),
                    onClick = {
                        busy = true
                        confirmActivate = false
                        issued = null
                        val user = staffUserId
                        scope.launch {
                            try {
                                client.ownerActivateStaffReadOnly(user)
                                status = "Server Confirmed READ-ONLY Staff Access. " +
                                    "Financial Writing Is Still Disabled."
                            } catch (_: Exception) {
                                status = "Server Did Not Activate Read-Only Access. " +
                                    "Confirm Pairing And RLS Readiness."
                            } finally { busy = false }
                        }
                    }
                ) { Text("Confirm Staff Read-Only Activation") }
                TextButton(onClick = { confirmActivate = false }) {
                    Text("Cancel")
                }
            } else {
                Button(
                    enabled = !busy && TeamSessionPairingPolicy.validUuid(staffUserId),
                    onClick = { confirmActivate = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Review Read-Only Activation") }
            }
            Text("Owner Approval For Financial Writing Is A Separate " +
                "Future Confirmed Action, Not Granted By Pairing.")
        } else {
            Text("Security Preview: Staff Invites, Code Sharing And Owner " +
                "Activation Are Disabled Until Audited Live Server Rollout.")
            Button(onClick = {},enabled = false,modifier=Modifier.fillMaxWidth()) {
                Text("Issue Staff Invitation — Server Activation Required")
            }
        }
        if (status.isNotBlank()) Text(status,
            style = MaterialTheme.typography.bodySmall)
    }
}
