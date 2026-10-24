package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import lk.peliyagoda.marketpro.cloud.CatalogReviewReader
import lk.peliyagoda.marketpro.cloud.CatalogReviewRow
import lk.peliyagoda.marketpro.cloud.CatalogReviewStatus
import lk.peliyagoda.marketpro.data.MarketDatabase
import kotlinx.coroutines.launch

/**
 * V3.6.6 explicitly selected Staff Phone local record -> Owner review.
 * Network controls appear ONLY after the fully reviewed server/API cutover.
 * Live v3.3.7 cannot call this code, and current offline preview does not
 * display a working upload control or collect any Staff password.
 */
@Composable
internal fun StaffCatalogCandidatePanel() {
    val enabled=!BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
        BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED &&
        BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED &&
        BuildConfig.MARKETPRO_CATALOG_REVIEW_RPC_DEPLOYED

    if(!enabled) {
        Text("Staff Catalog Upload — Not Yet Connected",
            style=MaterialTheme.typography.titleSmall,
            fontWeight=FontWeight.Bold,color=ProColors.teal)
        Text("When secure Staff Login and Server APIs are activated, " +
            "this phone will prepare verified Category and Item candidates " +
            "from its REAL Room database for Owner review. " +
            "No upload is possible in this offline preview.",
            style=MaterialTheme.typography.bodySmall)
        return
    }

    val context=LocalContext.current.applicationContext
    val reader=remember(context){CatalogReviewReader(MarketDatabase.get(context))}
    val controller=remember(context){CatalogReviewNetworkCoordinator(context)}
    val scope=rememberCoroutineScope()
    var rows by remember{ mutableStateOf<List<CatalogReviewRow>>(emptyList()) }
    var selected by remember{ mutableStateOf<CatalogReviewRow?>(null) }
    var busy by remember{ mutableStateOf(false) }
    var notice by remember{ mutableStateOf("") }

    Text("Submit Selected Staff Phone Item For Owner Review",
        style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.Bold)
    Text("Categories must be mapped first. A name match is only a review " +
        "candidate — NOT Owner approval.",style=MaterialTheme.typography.bodySmall)
    Button(enabled=!busy,onClick={
        busy=true;notice="";rows=emptyList();selected=null
        scope.launch {
            try {
                rows=reader.snapshot().filter {
                    it.status==CatalogReviewStatus.NEEDS_OWNER_REVIEW &&
                    it.localKey!=null
                }
                notice=if(rows.isEmpty())
                    "No safe pending candidates. Verify Cloud Inbox and category mapping."
                else "${rows.size} possible matches. Select the intended record."
            } catch (_:Exception) {
                notice="Could not verify current Staff Phone catalog."
            } finally {busy=false}
        }
    }) { Text("Review This Phone's Candidates") }

    if(rows.isNotEmpty()) {
        LazyColumn(
            modifier=Modifier.fillMaxWidth().height(220.dp),
            verticalArrangement=Arrangement.spacedBy(6.dp)
        ) {
            items(rows,key={"${it.type}|${it.cloudUuid}"}) { row ->
                Card(modifier=Modifier.fillMaxWidth().clickable {
                    selected=row
                }) {
                    Column(modifier=Modifier.padding(10.dp)) {
                        Text((if(selected?.cloudUuid==row.cloudUuid)"● " else "")+
                            row.cloudName,fontWeight=FontWeight.Bold)
                        Text("Cloud ID: ${row.cloudUuid}",
                            style=MaterialTheme.typography.bodySmall)
                        Text("Local Room ID: ${row.localKey}",
                            style=MaterialTheme.typography.bodySmall)
                        Text("Local Category: ${row.localCategoryName ?: "—"}",
                            style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    val choice=selected
    if(choice!=null) {
        Text("You selected Local Key ${choice.localKey} → " +
            "Cloud UUID ${choice.cloudUuid}. Owner review is REQUIRED.",
            style=MaterialTheme.typography.bodySmall)
        Button(enabled=!busy && choice.localKey!=null,onClick={
            busy=true;notice=""
            scope.launch {
                try {
                    // Coordinator re-reads both CURRENT Room records and
                    // pending outbox inside a transaction before POSTing.
                    controller.staffSubmitLocalCatalog(
                        choice.type,requireNotNull(choice.localKey),choice.cloudUuid)
                    notice="Candidate delivered for Owner review. " +
                        "No catalog link or purchase was changed."
                    rows=emptyList();selected=null
                } catch (_:Exception) {
                    notice="Candidate NOT verified as current. Recheck Staff Phone " +
                        "catalog, pending changes, login and server readiness."
                    selected=null
                } finally {busy=false}
            }
        }){Text("Submit Selected Candidate — Owner Review Required")}
    }
    if(notice.isNotBlank())
        Text(notice,color=ProColors.teal,style=MaterialTheme.typography.bodySmall)
}
