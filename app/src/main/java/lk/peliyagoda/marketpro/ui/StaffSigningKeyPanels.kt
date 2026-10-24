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
import lk.peliyagoda.marketpro.cloud.CloudSyncConfig
import lk.peliyagoda.marketpro.cloud.OwnerWriterTarget
import lk.peliyagoda.marketpro.cloud.StaffKeyEnrollmentPolicy
import lk.peliyagoda.marketpro.cloud.StaffSigningKeyEnrollmentClient
import lk.peliyagoda.marketpro.cloud.TeamSessionPairingRpcClient
import lk.peliyagoda.marketpro.cloud.TeamSessionPairingPolicy
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import kotlinx.coroutines.launch

private val enrollmentEnabled: Boolean get() =
    StaffKeyEnrollmentPolicy.allowClient(
        BuildConfig.MARKETPRO_LOCAL_PREVIEW,
        BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED,
        BuildConfig.MARKETPRO_STAFF_PAIRING_RPC_DEPLOYED,
        BuildConfig.MARKETPRO_STAFF_KEY_ENROLLMENT_RPC_DEPLOYED
    )

/** Staff device shows its actual EC public-key fingerprint for Owner review. */
@Composable
internal fun StaffPhoneSigningKeyPanel() {
    val context=LocalContext.current.applicationContext
    val client=remember(context){ StaffSigningKeyEnrollmentClient(context) }
    val scope=rememberCoroutineScope()
    var fingerprint by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text("Staff Phone — Signing Key",
            style=MaterialTheme.typography.titleSmall,
            fontWeight=FontWeight.Bold)
        Text("After The Owner Activates Read-Only Access, Prove This Phone's " +
            "Signing Key. Give The Fingerprint To The Owner For Separate " +
            "Approval. This Never Enables Financial Writing By Itself.",
            style=MaterialTheme.typography.bodySmall)
        Button(
            onClick={
                fingerprint=""
                status=""
                busy=true
                scope.launch {
                    try {
                        val result=client.provePendingKey()
                        fingerprint=result.fingerprintSha256
                        status=if(result.hardwareBacked)
                            "Signing Key Proof Accepted. Hardware-Backed " +
                            "Keystore Reported; Server Attestation Not Verified. " +
                            "Waiting For Owner Approval."
                        else
                            "Signing Key Proof Accepted. Hardware Backing " +
                            "Not Confirmed. Waiting For Owner Approval."
                    } catch (_:Exception) {
                        status="Phone Key Proof Was Not Accepted. Check Staff " +
                            "Login, Owner Read-Only Activation And Network."
                    } finally { busy=false }
                }
            },
            enabled=enrollmentEnabled && !busy,
            modifier=Modifier.fillMaxWidth()
        ) { Text("Prove Phone Signing Key") }
        if(fingerprint.isNotEmpty()) {
            Text("Show This Public Fingerprint To The Owner:",
                style=MaterialTheme.typography.bodySmall)
            Text(fingerprint,style=MaterialTheme.typography.bodyMedium)
        }
        if(!enrollmentEnabled)
            Text("Offline Preview: Server Signing-Key Enrollment Is Disabled.",
                style=MaterialTheme.typography.bodySmall)
        if(status.isNotEmpty())Text(status,style=MaterialTheme.typography.bodySmall)
    }
}

/**
 * Owner cannot approve until a separate Staff proof has been accepted.
 * Any target change drops the staged fingerprint and typed confirmation.
 */
@Composable
internal fun OwnerStaffSigningKeyReviewPanel(
    staffUserId:String,
    staffDeviceId:String
) {
    val context=LocalContext.current.applicationContext
    val scope=rememberCoroutineScope()
    val rpc=remember(context){TeamSessionPairingRpcClient(context)}
    val ownerDeviceId=remember(context){CloudDeviceIdentity.get(context)}
    val target=OwnerWriterTarget(
        CloudSyncConfig.WORKSPACE_ID,CloudSyncConfig.OWNER_USER_ID,
        ownerDeviceId,staffUserId,staffDeviceId
    )
    var pendingFingerprint by remember(staffUserId,staffDeviceId) {
        mutableStateOf("")
    }
    var enteredPhoneFingerprint by remember(staffUserId,staffDeviceId) {
        mutableStateOf("")
    }
    var phrase by remember(staffUserId,staffDeviceId) {
        mutableStateOf("")
    }
    var ownerChecked by remember(staffUserId,staffDeviceId) {
        mutableStateOf(false)
    }
    var busy by remember { mutableStateOf(false) }
    var message by remember(staffUserId,staffDeviceId) {
        mutableStateOf("")
    }

    val enabled=enrollmentEnabled &&
        TeamSessionPairingPolicy.validUuid(staffUserId) &&
        TeamSessionPairingPolicy.validUuid(staffDeviceId) &&
        staffUserId!=CloudSyncConfig.OWNER_USER_ID &&
        staffDeviceId!=ownerDeviceId
    val confirmed=enabled && pendingFingerprint.isNotEmpty() &&
        StaffKeyEnrollmentPolicy.ownerMayApprove(
            target,pendingFingerprint,pendingFingerprint,
            enteredPhoneFingerprint,phrase,ownerChecked
        )
    Column(verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Text("Owner — Verify Staff Phone Signing Key",
            style=MaterialTheme.typography.titleSmall,
            fontWeight=FontWeight.Bold)
        Text("Ask Purchasing Staff To Display Its Phone's Public Fingerprint. " +
            "Review The Server Pending Key And Compare Every Character. " +
            "The Staff Cannot Approve Its Own Key.",
            style=MaterialTheme.typography.bodySmall)
        Button(
            onClick={
                pendingFingerprint=""
                enteredPhoneFingerprint=""
                phrase=""
                ownerChecked=false
                busy=true
                message=""
                val user=staffUserId
                val device=staffDeviceId
                scope.launch {
                    try {
                        pendingFingerprint=rpc.ownerPendingStaffKey(user,device)
                        message="Staff Key Possession Verified. " +
                            "Owner Fingerprint Comparison Still Required."
                    }catch (_:Exception) {
                        message="No Verified Pending Staff Phone Key Found. " +
                            "The Staff Must Complete Phone Key Proof First."
                    }finally { busy=false }
                }
            },
            enabled=enabled&&!busy,
            modifier=Modifier.fillMaxWidth()
        ) {Text("Review Server Pending Fingerprint")}
        if(pendingFingerprint.isNotEmpty()&&enabled) {
            Text("Server Pending Fingerprint (SHA-256):")
            Text(pendingFingerprint,style=MaterialTheme.typography.bodyMedium)
            OutlinedTextField(
                value=enteredPhoneFingerprint,
                onValueChange={
                    enteredPhoneFingerprint=it.trim().lowercase().take(64)
                },
                label={Text("Fingerprint Shown On Staff Phone")},
                singleLine=false,
                enabled=!busy,modifier=Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                Checkbox(checked=ownerChecked,onCheckedChange={ownerChecked=it},
                    enabled=!busy)
                Text("I Compared This Staff Phone Fingerprint Character By " +
                    "Character. This Is Only Key Approval, Not Write Permission.",
                    style=MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value=phrase,onValueChange={phrase=it.take(16)},
                label={Text("Type APPROVE KEY")},
                singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth()
            )
            Button(
                onClick={
                    if(!confirmed)return@Button
                    val serverFingerprint=pendingFingerprint
                    val phoneFingerprint=enteredPhoneFingerprint
                    val approval=phrase
                    val checked=ownerChecked
                    val exact=target
                    busy=true
                    pendingFingerprint=""
                    enteredPhoneFingerprint=""
                    phrase=""
                    ownerChecked=false
                    scope.launch {
                        try {
                            rpc.ownerApproveStaffSigningKey(
                                exact,serverFingerprint,phoneFingerprint,
                                approval,checked
                            )
                            message="Server Confirmed Staff Signing Key " +
                                "Approval. Writing Is Still Disabled Until " +
                                "Separate Owner Staff Writer Approval."
                        } catch (_:Exception) {
                            message="Owner Key Approval Was Not Confirmed. " +
                                "Review Current Staff Phone And Server Key Again."
                        } finally {busy=false}
                    }
                },
                enabled=confirmed&&!busy,
                modifier=Modifier.fillMaxWidth()
            ) {Text("Confirm Owner Approval — This Exact Key")}
            TextButton(
                onClick={
                    pendingFingerprint=""
                    enteredPhoneFingerprint=""
                    phrase=""
                    ownerChecked=false
                },enabled=!busy
            ) {Text("Cancel Without Approving")}
        }
        if(!enrollmentEnabled)
            Text("Security Preview: Key Review And Approval Are Disabled.",
                style=MaterialTheme.typography.bodySmall)
        if(message.isNotBlank())Text(message,style=MaterialTheme.typography.bodySmall)
    }
}
