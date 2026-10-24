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
import androidx.compose.runtime.LaunchedEffect
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
 * Actual Staff-phone Room + Cloud-Inbox candidate preview.
 * The user selects a real Room local ID / Cloud UUID. No free-form IDs.
 * In the existing offline preview, a network submission is IMPOSSIBLE.
 *
 * Server submission creates pending Owner evidence only; it does not map
 * item IDs or edit financial records. Actual Room state is re-read by the
 * coordinator after selection and again around network submission.
 */
@Composable
internal fun StaffCatalogCandidateUploadPanel() {
    val context=LocalContext.current.applicationContext
    val enabled=!BuildConfig.MARKETPRO_LOCAL_PREVIEW &&
        BuildConfig.MARKETPRO_TEAM_PULL_RPC_DEPLOYED &&
        BuildConfig.MARKETPRO_STAFF_LOGIN_ENABLED &&
        BuildConfig.MARKETPRO_CATALOG_REVIEW_RPC_DEPLOYED
    val reader=remember(context) { CatalogReviewReader(MarketDatabase.get(context)) }
    var records by remember { mutableStateOf<List<CatalogReviewRow>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope=rememberCoroutineScope()
    val coordinator=if(enabled) remember(context) {
        CatalogReviewNetworkCoordinator(context)
    } else null

    LaunchedEffect(reader) {
        try {
            records=reader.snapshot()
            status=if (records.isEmpty()) "No verified Cloud Inbox catalog on this phone."
                else ""
        } catch (_: Exception) {
            records=emptyList()
            status="Local catalog review could not be verified."
        }
    }
    val eligible=remember(records) {
        records.filter {
            it.localKey != null &&
                it.status in setOf(
                    CatalogReviewStatus.NEEDS_OWNER_REVIEW,
                    CatalogReviewStatus.CATEGORY_MAPPING_REQUIRED
                )
        }.take(60)
    }

    Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Staff Phone — Review Catalog Matches",
            style=MaterialTheme.typography.titleMedium,
            fontWeight=FontWeight.Bold)
        Text(
            "These are existing Room Items compared with staged Cloud records. " +
                "They are only candidates; matching names are not proof of identity.",
            style=MaterialTheme.typography.bodySmall
        )
        if (!enabled) {
            Text(
                "Development Preview — Candidate Upload Not Connected. " +
                    "Nothing will be sent to Cloud.",
                style=MaterialTheme.typography.bodySmall,color=ProColors.teal
            )
        }
        if (eligible.isNotEmpty()) {
            LazyColumn(modifier=Modifier.fillMaxWidth().heightIn(max=225.dp),
                verticalArrangement=Arrangement.spacedBy(6.dp)) {
                items(eligible,key={ "${it.type}|${it.cloudUuid}" }) { row ->
                    Card(modifier=Modifier.fillMaxWidth().clickable {
                        selected=row.cloudUuid
                        confirmed=false
                    }) {
                        Column(modifier=Modifier.padding(10.dp)) {
                            Text((if(selected==row.cloudUuid) "● " else "")+
                                row.cloudName,fontWeight=FontWeight.Bold)
                            Text("Local ID: ${row.localKey} · ${row.type}",
                                style=MaterialTheme.typography.bodySmall)
                            Text("Category: ${row.localCategoryName ?: row.cloudCategoryName ?: "—"}",
                                style=MaterialTheme.typography.bodySmall)
                            Text("Cloud UUID: ${row.cloudUuid}",
                                style=MaterialTheme.typography.bodySmall)
                            Text("Revision: ${row.cloudRevision}",
                                style=MaterialTheme.typography.bodySmall)
                            Text("Owner Verification Required",
                                style=MaterialTheme.typography.bodySmall,color=ProColors.teal)
                        }
                    }
                }
            }
        } else {
            Text(
                "No safe matching catalog candidates are available yet. " +
                    "Cloud Inbox download and catalog mapping may be required.",
                style=MaterialTheme.typography.bodySmall
            )
        }
        val chosen=eligible.firstOrNull { it.cloudUuid==selected }
        if(chosen!=null) {
            if (!confirmed) {
                Button(enabled=enabled && !busy,onClick={confirmed=true}) {
                    Text("Review Selected Item Before Submitting")
                }
            } else {
                Text(
                    "Submit only this Local ID ${chosen.localKey} and Cloud UUID " +
                        "${chosen.cloudUuid} for Owner Review?",
                    style=MaterialTheme.typography.bodySmall
                )
                Button(enabled=enabled && !busy,onClick={
                    busy=true
                    confirmed=false
                    status=""
                    scope.launch {
                        try {
                            val remoteId=requireNotNull(chosen.localKey)
                            val request=requireNotNull(coordinator)
                            request.staffSubmitFromRoom(chosen.type,remoteId,
                                chosen.cloudUuid)
                            status="Candidate sent for Owner review only. " +
                                "No financial or catalog mapping was changed."
                            selected=null
                            records=reader.snapshot()
                        } catch (_: Exception) {
                            status="Candidate was NOT verified as current. " +
                                "If network was interrupted, Owner may still have " +
                                "a pending record; reload before retrying."
                        } finally { busy=false }
                    }
                }) { Text("Submit Candidate For Owner Review") }
                TextButton(enabled=!busy,onClick={confirmed=false}) { Text("Cancel") }
            }
        }
        if(status.isNotBlank()) Text(status,color=ProColors.teal,
            style=MaterialTheme.typography.bodySmall)
    }
}
