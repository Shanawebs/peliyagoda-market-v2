package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
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
import lk.peliyagoda.marketpro.cloud.CatalogReviewNetworkCoordinator
import lk.peliyagoda.marketpro.cloud.OwnerRemoteCandidate
import kotlinx.coroutines.launch

/**
 * The disabled preview remains honest: no network on unconfigured phones.
 * When compiled for a SEPARATELY AUDITED release, Owner must explicitly
 * fetch, select and confirm a server-issued staff candidate before approval.
 * Approval still only issues a server token; Staff phone MUST recheck its
 * current Room data before an actual UUID identity link may be committed.
 */
@Composable
internal fun RemoteStaffCatalogReviewPanel() {
    val context=LocalContext.current.applicationContext
    val enabled=!BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
        BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED &&
        BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED &&
        BuildConfig.MARKETPRO_CATALOG_REVIEW_RPC_DEPLOYED
    if (!enabled) {
        Text("Staff Phone Review Inbox — Not Yet Connected",
            style=MaterialTheme.typography.titleMedium,
            fontWeight=FontWeight.Bold,color=ProColors.teal)
        Text("A verified Staff Phone must be enrolled before its Item IDs can " +
            "reach this Owner-only review inbox. No Owner-phone records " +
            "are substituted for Staff-phone records.",
            style=MaterialTheme.typography.bodyMedium)
        Text("Approval is disabled until the audited Cloud Server is active.",
            style=MaterialTheme.typography.bodySmall)
        return
    }
    val controller=remember(context){ CatalogReviewNetworkCoordinator(context) }
    val scope=rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<OwnerRemoteCandidate>>(emptyList()) }
    var nextCursor by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    Text("Authenticated Staff Phone Catalog Candidates",
        style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.Bold)
    Text("Owner must verify Staff Device ID, Local Item ID, Category and " +
        "Cloud UUID before approving. Names are NOT proof of identity.",
        style=MaterialTheme.typography.bodySmall)
    Button(
        enabled=!busy,
        onClick={
            busy=true;notice="";loaded=false;selected=null;confirm=false
            rows=emptyList();nextCursor=null
            scope.launch {
                try {
                    val result=controller.ownerReviewPage(pageSize=30)
                    rows=result.candidates
                    nextCursor=result.nextId
                    loaded=true
                } catch (_: Exception) {
                    notice="Secure Owner candidate download could not be verified."
                } finally { busy=false }
            }
        }
    ) { Text(if(busy)"Verifying…" else "Load Staff Phone Candidates") }

    if(loaded && rows.isEmpty()) {
        Text("No pending Staff Phone candidates were returned.",
            style=MaterialTheme.typography.bodySmall)
    }
    if(rows.isNotEmpty()) {
        LazyColumn(modifier=Modifier.fillMaxWidth().heightIn(max=310.dp),
            verticalArrangement=Arrangement.spacedBy(6.dp)) {
            items(rows,key={it.candidateUuid}) { item ->
                Card(modifier=Modifier.fillMaxWidth().clickable {
                    selected=item.candidateUuid
                    confirm=false
                }) {
                    Column(modifier=Modifier.padding(10.dp),
                        verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text((if(selected==item.candidateUuid)"● " else "")+
                            item.localName,fontWeight=FontWeight.Bold)
                        Text("Staff Phone ID: ${item.staffDeviceId}",
                            style=MaterialTheme.typography.bodySmall)
                        Text("Staff User ID: ${item.staffAccountId}",
                            style=MaterialTheme.typography.bodySmall)
                        Text("Local ID: ${item.localItemKey} | ${item.localCategory ?: "Category"}",
                            style=MaterialTheme.typography.bodySmall)
                        Text("Cloud UUID: ${item.cloudUuid}",
                            style=MaterialTheme.typography.bodySmall)
                        Text("Cloud Revision: ${item.cloudRevision}",
                            style=MaterialTheme.typography.bodySmall)
                        Text("Content Fingerprint: ${item.localFingerprint.take(16)}…",
                            style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if(nextCursor!=null) {
            TextButton(enabled=!busy,onClick={
                val after=nextCursor
                busy=true;selected=null;confirm=false;rows=emptyList()
                scope.launch {
                    try {
                        val result=controller.ownerReviewPage(afterId=after,pageSize=30)
                        rows=result.candidates
                        nextCursor=result.nextId
                    } catch (_: Exception) {
                        notice="Secure Staff candidate page could not be loaded."
                    } finally { busy=false }
                }
            }){ Text("Next Candidate Page") }
        }
    }
    val selectedItem=rows.firstOrNull { it.candidateUuid==selected }
    if(selectedItem!=null) {
        Text("Selected ${selectedItem.localName} on Staff device " +
            "${selectedItem.staffDeviceId}. Approval is permanent for this " +
            "exact candidate and requires Staff device revalidation.",
            style=MaterialTheme.typography.bodySmall)
        if(!confirm) {
            Button(enabled=!busy,onClick={confirm=true}) {
                Text("Review This Candidate For Approval")
            }
        } else {
            Button(enabled=!busy,onClick={
                val id=selectedItem.candidateUuid
                busy=true;notice="";confirm=false
                scope.launch {
                    try {
                        controller.ownerApproveSelected(id)
                        notice="Owner approved the specific candidate on Server. " +
                            "Staff Phone still must verify its current Room record."
                        rows=rows.filterNot { it.candidateUuid==id }
                        selected=null
                    } catch (_: Exception) {
                        notice="Approval was rejected or not verified. Reload candidate."
                        selected=null
                        rows=emptyList()
                    } finally { busy=false }
                }
            }){ Text("Confirm Server Owner Approval") }
            TextButton(onClick={confirm=false},enabled=!busy){Text("Cancel")}
        }
    }
    if(notice.isNotBlank()) Text(notice,color=ProColors.teal,
        style=MaterialTheme.typography.bodySmall)
}
