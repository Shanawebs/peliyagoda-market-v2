package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.BuildConfig
import lk.peliyagoda.marketpro.cloud.OwnerPublicKeyApprovalPolicy
import lk.peliyagoda.marketpro.cloud.OwnerPublicKeyCandidate
import lk.peliyagoda.marketpro.cloud.TeamSessionPairingPolicy
import lk.peliyagoda.marketpro.cloud.TeamSessionPairingRpcClient
import kotlinx.coroutines.launch

/** Owner-only UI for physically comparing an exact 64-character Staff
 * AndroidKeyStore fingerprint. This is NOT an authentication method;
 * the server also requires an active Owner AAL2 session + fresh TOTP AMR.
 *
 * No sensitive operations in offline preview. No fake local approvals.
 */
@Composable
internal fun OwnerStaffPublicKeyApprovalPanel(staffUserId:String) {
    val context=LocalContext.current.applicationContext
    val client=remember(context) { TeamSessionPairingRpcClient(context) }
    val scope=rememberCoroutineScope()
    val enabled=!BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
        BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED &&
        BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED &&
        BuildConfig.MARKETPRO_STAFF_PAIRING_RPC_DEPLOYED &&
        BuildConfig.MARKETPRO_STAFF_KEY_ENROLLMENT_DEPLOYED

    // Changing Staff UUID must invalidate every staged approval.
    var pending by remember(staffUserId) {
        mutableStateOf<OwnerPublicKeyCandidate?>(null)
    }
    var typed by remember(staffUserId) { mutableStateOf("") }
    var phrase by remember(staffUserId) { mutableStateOf("") }
    var checked by remember(staffUserId) { mutableStateOf(false) }
    var busy by remember(staffUserId) { mutableStateOf(false) }
    var status by remember(staffUserId) { mutableStateOf("") }

    Column(verticalArrangement=Arrangement.spacedBy(9.dp)) {
        Text("Owner — Approve Staff Phone Security Key",
            style=MaterialTheme.typography.titleMedium,
            fontWeight=FontWeight.Bold)
        Text("Staff signs a challenge on their own Phone. Review the exact " +
            "64-character fingerprint before allowing its public key.",
            style=MaterialTheme.typography.bodySmall)
        Text("Key approval does NOT allow financial writing. " +
            "The Owner must approve the separate Staff Writer permission.",
            style=MaterialTheme.typography.bodySmall)
        Button(
            onClick={
                pending=null
                typed=""
                phrase=""
                checked=false
                busy=true
                status=""
                val target=staffUserId
                scope.launch {
                    try{
                        val candidate=client.ownerReviewStaffPublicKey(target)
                        if(target==staffUserId) {
                            pending=candidate
                            status="Candidate loaded from Server. " +
                                "Compare the complete fingerprint with the Staff Phone."
                        }
                    }catch(_:Exception){
                        status="Could not verify a current Staff Phone key candidate."
                    }finally{busy=false}
                }
            },
            enabled=enabled && !busy &&
                TeamSessionPairingPolicy.validUuid(staffUserId),
            modifier=Modifier.fillMaxWidth()
        ){Text("Review Staff Phone Public Key")}

        val candidate=pending
        if(candidate!=null && candidate.staffUserId==staffUserId && enabled) {
            Text("Device ID: " + candidate.staffDeviceId,
                style=MaterialTheme.typography.bodySmall)
            Text("Pending Key Fingerprint (SHA-256):",
                fontWeight=FontWeight.Bold)
            Text(candidate.fingerprintSha256.chunked(8).joinToString(" "),
                style=MaterialTheme.typography.bodyMedium)
            Text("WARNING: Read the fingerprint from the ACTUAL Staff Phone. " +
                "Do not simply copy the fingerprint shown above. " +
                "The Owner must personally compare both displays.",
                style=MaterialTheme.typography.bodySmall)

            OutlinedTextField(
                value=typed,
                onValueChange={typed=it.trim().take(64)},
                label={Text("Type 64 Characters From Staff Phone")},
                modifier=Modifier.fillMaxWidth(),
                enabled=!busy,
                singleLine=false,
                maxLines=3
            )
            Row(horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                Checkbox(
                    checked=checked,
                    onCheckedChange={checked=it},
                    enabled=!busy
                )
                Text("I physically verified the Staff Phone and compared " +
                    "its full fingerprint to this exact pending device.",
                    style=MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value=phrase,
                onValueChange={phrase=it.take(11)},
                label={Text("Type APPROVE KEY")},
                modifier=Modifier.fillMaxWidth(),
                enabled=!busy,
                singleLine=true
            )
            Text("Security requirement: Complete recent Owner Authenticator " +
                "(TOTP) verification to reach AAL2 before confirmation. " +
                "Only the Server decides if the MFA factor is fresh.",
                style=MaterialTheme.typography.bodySmall)

            val canConfirm=OwnerPublicKeyApprovalPolicy.confirm(
                candidate,candidate,typed,phrase,checked)
            Button(
                onClick={
                    val staged=candidate
                    if(!OwnerPublicKeyApprovalPolicy.confirm(
                        staged,staged,typed,phrase,checked))return@Button
                    // Release local target immediately; no double submission
                    // with a still-clickable candidate while request is in flight.
                    busy=true
                    pending=null
                    status=""
                    val typedFingerprint=typed
                    val submittedPhrase=phrase
                    val witnessed=checked
                    typed=""
                    phrase=""
                    checked=false
                    scope.launch {
                        try{
                            val confirmed=client.ownerApproveStaffPublicKey(
                                staged,typedFingerprint,witnessed,submittedPhrase)
                            status=if(confirmed)
                                "Server approved Staff public key ONLY. " +
                                    "Financial writing remains disabled until " +
                                    "separate Owner writer approval."
                            else "Server did not confirm public key approval."
                        }catch(_:Exception){
                            status="Server denied approval. Verify recent Owner " +
                                "Authenticator MFA, exact Staff Phone fingerprint " +
                                "and unchanged pending challenge."
                        }finally{busy=false}
                    }
                },
                enabled=enabled && !busy && canConfirm,
                modifier=Modifier.fillMaxWidth()
            ){Text("Confirm Public Key — Owner MFA Required")}
            TextButton(
                onClick={
                    pending=null
                    typed=""
                    phrase=""
                    checked=false
                    status="Key approval cancelled. No changes made."
                },
                enabled=!busy
            ){Text("Cancel Key Approval")}
        }
        if(!enabled) {
            Text("Offline Security Preview: Public Key Approval is disabled " +
                "until secure Server activation. Current Owner App unchanged.",
                style=MaterialTheme.typography.bodySmall)
        }
        if(status.isNotBlank()) Text(status,
            style=MaterialTheme.typography.bodySmall)
    }
}

/** Staff can propose a candidate fingerprint after Owner READ-ONLY
 * activation, then show it on THIS phone for physical Owner comparison.
 * The Owner must authorize using their separate phone.
 */
@Composable
internal fun StaffPublicKeyEnrollmentPanel() {
    val context=LocalContext.current.applicationContext
    val client=remember(context) {
        lk.peliyagoda.marketpro.cloud.StaffPublicKeyEnrollmentClient(context)
    }
    val scope=rememberCoroutineScope()
    val enabled=!BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
        BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED &&
        BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED &&
        BuildConfig.MARKETPRO_STAFF_PAIRING_RPC_DEPLOYED &&
        BuildConfig.MARKETPRO_STAFF_KEY_ENROLLMENT_DEPLOYED
    var fingerprint by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Staff — This Phone's Security Key",
            style=MaterialTheme.typography.titleMedium)
        Text("After Owner Read-Only Activation, register THIS Phone's " +
            "public key and show its complete fingerprint to the Owner.",
            style=MaterialTheme.typography.bodySmall)
        Button(
            onClick={
                fingerprint=""
                status=""
                busy=true
                scope.launch{
                    try{
                        val evidence=client.stageForOwnerReview()
                        if(evidence.ownerApprovalRequired){
                            fingerprint=evidence.fingerprintSha256
                            status="Staff Phone key proven. Owner fingerprint " +
                                "approval is still required. Staff writing disabled."
                        }
                    }catch(_:Exception){
                        status="Key proof unavailable. Verify Staff Login, " +
                            "Owner Read-Only activation and secure Server rollout."
                    }finally{busy=false}
                }
            },
            enabled=enabled && !busy,
            modifier=Modifier.fillMaxWidth()
        ){Text("Prove This Staff Phone Key")}
        if(fingerprint.isNotEmpty() && enabled) {
            Text("SHA-256 Fingerprint — Show On This Physical Phone",
                fontWeight=FontWeight.Bold)
            Text(fingerprint.chunked(8).joinToString(" "))
            Text("Do not share your Auth password or one-time login codes. " +
                "The private signing key never leaves Android Keystore.",
                style=MaterialTheme.typography.bodySmall)
        }
        if(!enabled) Text("Security Preview: Staff enrollment is disabled.",
            style=MaterialTheme.typography.bodySmall)
        if(status.isNotBlank()) Text(status,
            style=MaterialTheme.typography.bodySmall)
    }
}
