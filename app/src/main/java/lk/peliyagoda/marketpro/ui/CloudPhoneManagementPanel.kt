package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
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
import lk.peliyagoda.marketpro.cloud.CloudPhoneRegistryClient
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
        val data=snapshot
        if(data!=null) {
            Text(if(data.role=="owner") "Owner — Manage Team Phones"
                else "Team — This Phone's Approval Status",fontWeight=FontWeight.Bold)
            if(data.phones.isEmpty()) Text("No Phone Requests Yet")
            data.phones.forEach { device ->
                val state=if(device.enabled) "APPROVED" else "PENDING / UNLINKED"
                Text("${device.label} • ${device.role} • $state")
                Text("ID ...${device.id.takeLast(8)}")
                if(data.role=="owner" && device.role=="editor") {
                    Button(enabled=!busy,onClick={runRequest {
                        client.decideTeamPhone(device.id,!device.enabled)
                        snapshot=client.listPhones()
                        notice=if(device.enabled) "Team phone unlinked" else
                            "Team phone approved. Open that phone and tap Activate / Sync Two-Phone."
                    }}) {
                        Text(if(device.enabled) "Unlink Team Phone" else "Approve Team Phone")
                    }
                }
            }
        }
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
}
