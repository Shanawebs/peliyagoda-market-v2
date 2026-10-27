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
                            "Team phone approved for Cloud reads"
                    }}) {
                        Text(if(device.enabled) "Unlink Team Phone" else "Approve Team Phone")
                    }
                }
            }
        }
        if(notice.isNotBlank()) Text(notice)
        Text("Cloud Upload / 2-way financial sync තවම OFF. " +
            "මේ පියවර Phone Linking සහ Cloud Read authorization සඳහායි.")
        HorizontalDivider()
    }
}
