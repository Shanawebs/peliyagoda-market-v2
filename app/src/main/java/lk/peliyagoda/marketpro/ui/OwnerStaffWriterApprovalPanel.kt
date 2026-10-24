package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Row
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
import lk.peliyagoda.marketpro.cloud.OwnerStaffDeviceAction
import lk.peliyagoda.marketpro.cloud.OwnerStaffWriterReviewPolicy
import lk.peliyagoda.marketpro.cloud.OwnerTeamPhoneTransferClient
import lk.peliyagoda.marketpro.cloud.OwnerWriterTarget
import lk.peliyagoda.marketpro.cloud.TeamSessionPairingRpcClient
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import kotlinx.coroutines.launch

/**
 * V3.7.4 separate Owner intent confirmation, NOT a grant or local role toggle.
 * Every action requires audited live server RPC plus verified Owner JWT/device.
 * All controls are disabled in the intentionally offline security preview.
 */
@Composable
internal fun OwnerStaffWriterApprovalPanel(
    staffUserId: String,
    staffDeviceId: String
) {
    val context=LocalContext.current.applicationContext
    val scope=rememberCoroutineScope()
    val rpc=remember(context) { TeamSessionPairingRpcClient(context) }
    val transfer=remember(context) { OwnerTeamPhoneTransferClient(context) }
    val target=OwnerWriterTarget(
        workspaceId=CloudSyncConfig.WORKSPACE_ID,
        expectedOwnerUserId=CloudSyncConfig.OWNER_USER_ID,
        ownerDeviceId=CloudDeviceIdentity.get(context),
        staffUserId=staffUserId,
        staffDeviceId=staffDeviceId
    )
    var staged by remember { mutableStateOf<OwnerWriterTarget?>(null) }
    var action by remember { mutableStateOf<OwnerStaffDeviceAction?>(null) }
    var phrase by remember { mutableStateOf("") }
    var ownerChecked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    fun available(which:OwnerStaffDeviceAction):Boolean =
        OwnerStaffWriterReviewPolicy.networkReady(
            BuildConfig.MARKETPRO_LOCAL_PREVIEW,
            BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED,
            BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED,
            BuildConfig.MARKETPRO_STAFF_PAIRING_RPC_DEPLOYED,
            BuildConfig.MARKETPRO_STAFF_WRITE_APPROVAL_RPC_DEPLOYED,
            BuildConfig.MARKETPRO_TEAM_PHONE_TRANSFER_RPC_DEPLOYED,
            which
        )

    val pending=staged
    val chosen=action
    val validCurrent=OwnerStaffWriterReviewPolicy.validTarget(target)
    val stillMatches=pending!=null && pending==target
    val readyToSubmit=chosen!=null && pending!=null && stillMatches &&
        available(chosen) &&
        OwnerStaffWriterReviewPolicy.confirmed(
            pending,target,chosen,phrase,ownerChecked
        )
    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Owner — Staff Write Permission",
            style=MaterialTheme.typography.titleMedium,
            fontWeight=FontWeight.Bold)
        Text("Pairing Or Read-Only Activation Never Enables Financial Writing. " +
            "Only The Owner Can Approve A Server-Controlled Write Lease.",
            style=MaterialTheme.typography.bodySmall)
        Text("Target Staff Account: ${staffUserId.ifBlank { "Not Selected" }}",
            style=MaterialTheme.typography.bodySmall)
        Text("Target Phone: ${staffDeviceId.ifBlank { "Not Selected" }}",
            style=MaterialTheme.typography.bodySmall)
        if (BuildConfig.MARKETPRO_LOCAL_PREVIEW) {
            Text("SECURITY PREVIEW: All Write Approval And Phone Revocation " +
                "Actions Are Disabled. No Live Workspace Access Changes.")
        }
        for (candidate in OwnerStaffDeviceAction.entries) {
            val label=when(candidate) {
                OwnerStaffDeviceAction.APPROVE_WRITING ->
                    "Review: Enable Staff Writing"
                OwnerStaffDeviceAction.PAUSE_WRITING ->
                    "Review: Pause Staff Writing"
                OwnerStaffDeviceAction.REVOKE_LOST_PHONE ->
                    "Review: Revoke Old/Lost Staff Phone"
            }
            Button(
                onClick={
                    staged=target
                    action=candidate
                    phrase=""
                    ownerChecked=false
                    notice=""
                },
                enabled=!busy && validCurrent && available(candidate),
                modifier=Modifier.fillMaxWidth()
            ) { Text(label) }
        }

        if(chosen!=null && pending!=null && stillMatches &&
            available(chosen)) {
            val explanation=when(chosen) {
                OwnerStaffDeviceAction.APPROVE_WRITING ->
                    "ENABLE: Staff May Edit Today's Records Only. " +
                    "Past Days Stay Owner-Only And Server Must Recheck Every Edit."
                OwnerStaffDeviceAction.PAUSE_WRITING ->
                    "PAUSE: Staff Writing Stops, But Read-Only Access Remains. " +
                    "If Phone Is Missing, Revoke The Entire Device Instead."
                OwnerStaffDeviceAction.REVOKE_LOST_PHONE ->
                    "REVOKE: Old Staff Phone Stops Cloud Read And Write Access. " +
                    "Already Downloaded Offline Files Cannot Be Remotely Erased."
            }
            Text(explanation,style=MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                Checkbox(checked=ownerChecked,
                    onCheckedChange={ ownerChecked=it },enabled=!busy)
                Text("I Have Verified The Exact Staff Account And Phone. " +
                    "I Understand Unsynced Purchases And Photos May Be Lost " +
                    "If This Phone Is Retired.",
                    style=MaterialTheme.typography.bodySmall)
            }
            OutlinedTextField(
                value=phrase,
                onValueChange={ phrase=it.take(12) },
                label={ Text("Type ${chosen.phrase} To Confirm") },
                singleLine=true,enabled=!busy,modifier=Modifier.fillMaxWidth()
            )
            Button(
                onClick={
                    val reviewTarget=pending
                    val reviewAction=chosen
                    if (!OwnerStaffWriterReviewPolicy.confirmed(
                        reviewTarget,target,reviewAction,phrase,ownerChecked
                    )) return@Button
                    busy=true
                    notice=""
                    staged=null
                    action=null
                    phrase=""
                    ownerChecked=false
                    scope.launch {
                        try {
                            val acknowledged=when(reviewAction) {
                                OwnerStaffDeviceAction.APPROVE_WRITING ->
                                    rpc.ownerApproveStaffWriter(
                                        reviewTarget.staffUserId,
                                        reviewTarget.staffDeviceId
                                    )
                                OwnerStaffDeviceAction.PAUSE_WRITING ->
                                    rpc.ownerDisableStaffWriter(
                                        reviewTarget.staffUserId,
                                        reviewTarget.staffDeviceId
                                    )
                                OwnerStaffDeviceAction.REVOKE_LOST_PHONE ->
                                    transfer.disableOldPhone(
                                        reviewTarget.staffUserId,
                                        reviewTarget.staffDeviceId
                                    )
                            }
                            notice=if(acknowledged) when(reviewAction) {
                                OwnerStaffDeviceAction.APPROVE_WRITING ->
                                    "Server Confirmed Owner Staff Write Approval."
                                OwnerStaffDeviceAction.PAUSE_WRITING ->
                                    "Server Confirmed Staff Writing Disabled. " +
                                    "Read Access May Still Exist."
                                OwnerStaffDeviceAction.REVOKE_LOST_PHONE ->
                                    "Server Confirmed Old Phone Disabled. " +
                                    "Local Unsynced Records Were Not Erased."
                            } else "Server Did Not Confirm The Requested Change."
                        } catch (_:Exception) {
                            notice="Server Did Not Confirm The Action. " +
                                "Check Live Owner Login And Device Authorization."
                        } finally { busy=false }
                    }
                },
                enabled=!busy && readyToSubmit,
                modifier=Modifier.fillMaxWidth()
            ) { Text("Confirm ${chosen.phrase} For This Exact Phone") }
            TextButton(onClick={
                staged=null
                action=null
                phrase=""
                ownerChecked=false
            },enabled=!busy) { Text("Cancel — Make No Changes") }
        } else if(chosen!=null && !stillMatches) {
            Text("Selected Account/Phone Has Changed. " +
                "Review And Confirm Again.")
        }
        if(notice.isNotBlank())
            Text(notice,style=MaterialTheme.typography.bodySmall)
    }
}
