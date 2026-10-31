package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
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
import lk.peliyagoda.marketpro.cloud.CloudPhoneRegistryClient
import lk.peliyagoda.marketpro.cloud.MarketProActiveRole
import lk.peliyagoda.marketpro.cloud.MarketProSubsAdminClient
import lk.peliyagoda.marketpro.cloud.TeamRole
import lk.peliyagoda.marketpro.cloud.OneMarketProPullSessionProvider
import lk.peliyagoda.marketpro.cloud.TeamInboxDownloadCoordinator
import lk.peliyagoda.marketpro.cloud.TeamPullRequestPolicy
import lk.peliyagoda.marketpro.cloud.StagingTwoPhoneSyncCoordinator
import kotlinx.coroutines.launch

/** ONE APK on Owner/Team phones. Does not activate financial sync or permissions. */
@Composable
internal fun CloudPhoneManagementPanel() {
    val enabled = !BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
        BuildConfig.MARKETPRO_PHONE_REGISTRY_RPC_DEPLOYED
    if (!enabled) {
        Text("Cloud Phone Management — Staging verification pending")
        return
    }
    val context=LocalContext.current.applicationContext
    val client=remember(context){CloudPhoneRegistryClient(context)}
    val scope=rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var snapshot by remember { mutableStateOf<CloudPhoneRegistryClient.Snapshot?>(null) }
    var showPhoneManager by remember { mutableStateOf(false) }

    fun runRequest(job:suspend () -> Unit) {
        if(busy)return
        busy=true
        notice=""
        scope.launch {
            try {
                job()
            } catch(e:Exception) {
                notice=e.message?.take(170) ?: "Phone request could not be completed"
            } finally {
                busy=false
            }
        }
    }
    Column(modifier=Modifier.fillMaxWidth(),
        verticalArrangement=Arrangement.spacedBy(8.dp)) {
        HorizontalDivider()
        Text("Cloud Phone Linking",fontWeight=FontWeight.Bold)
        if (MarketProActiveRole.current(context) == TeamRole.OWNER) {
            OwnerFiveSubsProvisioningPanel()
        }
        Text("එකම Market Pro APK එකෙන් Owner/Team Login වෙන්න. " +
            "Ownerට එක active phone එකයි. Team phones කිහිපයක් Approve කළ හැකියි.")
        Button(
            enabled=!busy,
            onClick={runRequest {
                notice=client.requestThisPhone()
                snapshot=client.listPhones()
            }}
        ) { Text("Request This Phone") }
        TextButton(
            enabled=!busy,
            onClick={runRequest { snapshot=client.listPhones();notice="Phone list refreshed" }}
        ) { Text("Refresh Linked Phones") }

        if (BuildConfig.MARKETPRO_STAGING_QA_FINANCE_RPC &&
            BuildConfig.MARKETPRO_TWO_PHONE_AUTO_SYNC
        ) {
            Button(
                enabled=!busy,
                onClick={runRequest {
                    val coordinator=StagingTwoPhoneSyncCoordinator(context)
                    if (coordinator.isReady()) {
                        val report=coordinator.syncNow()
                        notice="Two-Phone Sync active. Uploaded " + report.uploaded +
                            ", pending " + report.pending +
                            ", safe-merged " + report.pulledMerged + "."
                    } else {
                        val activation=coordinator.activateFreshPhone()
                        notice="Two-Phone Sync ACTIVATED: " +
                            activation.categories + " categories, " +
                            activation.items + " active items linked; " +
                            activation.archivedCloudItemsProtected +
                            " archived Cloud items protected. First sync uploaded " +
                            activation.firstSync.uploaded + "."
                    }
                }}
            ) {
                Text("Activate / Sync Two-Phone")
            }
        } else if (BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED) {
            Button(
                enabled=!busy,
                onClick={runRequest {
                    val coordinator=TeamInboxDownloadCoordinator(
                        context,OneMarketProPullSessionProvider(context)
                    )
                    var staged=0
                    var conflicts=0
                    for(type in TeamPullRequestPolicy.orderedTypes) {
                        val result=coordinator.stageCloudType(type,pageSize=35,maxPages=8)
                        check(!result.hasMore) {
                            "Cloud download is partial; complete the next page before merging"
                        }
                        staged+=result.recordsStaged
                        conflicts+=result.pendingLocalEditConflicts
                    }
                    notice="Cloud Inbox: $staged records staged; $conflicts local edit conflicts. " +
                        "Finance merge and uploads are still OFF."
                }}
            ) {
                Text("Download Cloud Updates (Safe Stage)")
            }
        }
        Button(
            enabled = !busy,
            onClick = { showPhoneManager = true }
        ) { Text("Manage Linked Phones") }
        Text(
            "Owner සහ Team Phone එකම APK එක භාවිත කරනවා. " +
                "එක් Device ID එක බැගින් Manage Linked Phones තුළ පාලනය කරන්න."
        )
        if(notice.isNotBlank()) Text(notice)
        Text(
            if (BuildConfig.MARKETPRO_STAGING_QA_FINANCE_RPC &&
                BuildConfig.MARKETPRO_TWO_PHONE_AUTO_SYNC
            ) {
                "Two-Phone Auto Sync: approval පසු එක් phone එකකට වරක් Activate / Sync Two-Phone ඔබන්න. " +
                    "ඊට පස්සේ local edits Outbox හරහා auto-upload වෙයි; අනෙක් phone එක safe-merge වෙයි."
            } else {
                "Cloud Upload / 2-way financial sync තවම OFF. " +
                    "මේ පියවර Phone Linking සහ Cloud Read authorization සඳහායි."
            }
        )
        HorizontalDivider()
    }
    if (showPhoneManager) {
        LinkedPhonesManagerDialog(
            client = client,
            onClose = { showPhoneManager = false }
        )
    }
}

/** Server validates the Owner, device, fresh MFA and five-account limit. */
@Composable
private fun OwnerFiveSubsProvisioningPanel() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val client = remember(context) { MarketProSubsAdminClient(context) }
    var email by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmed by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Owner — Manage Up To 5 Subscribers", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold)
        Text("First create and verify each separate Subs login. Owner approval requires a fresh Authenticator MFA verification. Phone and Photo Key approval are separate steps.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = email, onValueChange = { email = it.trim().take(254); confirmed = false },
            label = { Text("Verified Subs Email") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), enabled = !busy)
        androidx.compose.material3.Checkbox(checked = confirmed, onCheckedChange = { confirmed = it }, enabled = !busy)
        Text("I confirm that this verified account belongs to an authorized Subs user.",
            style = MaterialTheme.typography.bodySmall)
        Button(enabled = !busy && confirmed && email.contains('@') &&
            !BuildConfig.MARKETPRO_LOCAL_PREVIEW, onClick = {
            busy = true
            notice = ""
            val target = email
            scope.launch {
                try { notice = client.approveVerifiedMember(target); email = ""; confirmed = false }
                catch (e: Exception) { notice = e.message ?: "Subs approval failed" }
                finally { busy = false }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Checking..." else "Approve Subs Account") }
        if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
    }
}
