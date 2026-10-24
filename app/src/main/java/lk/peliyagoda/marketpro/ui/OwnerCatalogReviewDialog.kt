package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.cloud.CatalogReviewReader
import lk.peliyagoda.marketpro.cloud.CatalogReviewRow
import lk.peliyagoda.marketpro.cloud.CatalogReviewStatus
import lk.peliyagoda.marketpro.data.MarketDatabase

private fun reviewLabel(status: CatalogReviewStatus): String = when (status) {
    CatalogReviewStatus.NEEDS_OWNER_REVIEW -> "Possible Match — Owner Review Required"
    CatalogReviewStatus.CATEGORY_MAPPING_REQUIRED -> "Map Category First"
    CatalogReviewStatus.LOCAL_CATEGORY_MISMATCH -> "Item Category/Status Mismatch"
    CatalogReviewStatus.LOCAL_ITEM_NOT_FOUND -> "No Matching Local Item"
    CatalogReviewStatus.AMBIGUOUS_LOCAL_ITEM -> "Multiple Local Matches"
    CatalogReviewStatus.ALREADY_MAPPED -> "Existing UUID Link — Verify Details"
    CatalogReviewStatus.IDENTITY_CONFLICT -> "UUID Identity Conflict — Stop"
    CatalogReviewStatus.PENDING_OFFLINE_EDIT -> "Pending Offline Edit — Stop"
    CatalogReviewStatus.CLOUD_DELETED -> "Deleted Cloud Record — Review"
    CatalogReviewStatus.UNTRUSTED_SNAPSHOT -> "Unverified Snapshot — Do Not Map"
}

/**
 * V3.6.3 REAL local catalog comparison, PREVIEW ONLY.
 * No write method, no owner token request, no approval RPC invocation.
 * A matching name is a suggestion for human inspection, NEVER a linked ID.
 */
@Composable
internal fun OwnerCatalogReviewDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val reader = remember(context) {
        CatalogReviewReader(MarketDatabase.get(context))
    }
    var records by remember { mutableStateOf<List<CatalogReviewRow>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var selectedType by remember { mutableStateOf("item") }
    var search by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(reader) {
        loading=true
        try {
            records=reader.snapshot()
            error=false
        } catch (_: Exception) {
            error=true
            records=emptyList()
        } finally {
            loading=false
        }
    }
    val filtered = remember(records,selectedType,search) {
        val query=search.trim()
        records.filter { row ->
            row.type==selectedType && (query.isEmpty() ||
                row.cloudName.contains(query,ignoreCase=true) ||
                row.localName.orEmpty().contains(query,ignoreCase=true) ||
                row.cloudCategoryName.orEmpty().contains(query,ignoreCase=true))
        }
    }
    AlertDialog(
        onDismissRequest=onDismiss,
        containerColor=Color.White,
        title={ Text("Owner Catalog Mapping Review",
            style=MaterialTheme.typography.titleLarge) },
        text={
            Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("Read-Only Preview — No Links Are Approved Or Saved.",
                    style=MaterialTheme.typography.bodyMedium,
                    color=ProColors.teal,fontWeight=FontWeight.Bold)
                Text("Compare Cloud Items With Existing Phone Items. Matching " +
                    "Names Alone Do Not Verify Identity. Owner Server Approval " +
                    "Is Required Before Any Mapping.",
                    style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick={ selectedType="category";expanded=null }) {
                        Text(if(selectedType=="category") "● Categories" else "Categories")
                    }
                    TextButton(onClick={ selectedType="item";expanded=null }) {
                        Text(if(selectedType=="item") "● Items" else "Items")
                    }
                }
                OutlinedTextField(
                    value=search,
                    onValueChange={search=it},
                    label={ Text("Search Item Or Category") },
                    singleLine=true,
                    modifier=Modifier.fillMaxWidth()
                )
                Text(
                    when {
                        loading -> "Reading Safe Local Cloud Inbox…"
                        error -> "Catalog Preview Unavailable. No Data Has Changed."
                        records.isEmpty() -> "No Downloaded Cloud Catalog Available On This Phone."
                        else -> "${filtered.size} Records To Examine · ${records.size} Total Staged Records"
                    },
                    style=MaterialTheme.typography.bodySmall
                )
                if (!loading && !error && filtered.isNotEmpty()) {
                    LazyColumn(
                        modifier=Modifier.fillMaxWidth().heightIn(max=345.dp),
                        verticalArrangement=Arrangement.spacedBy(8.dp)
                    ) {
                        items(filtered,key={ "${it.type}|${it.cloudUuid}" }) { row ->
                            Card(
                                modifier=Modifier.fillMaxWidth().clickable {
                                    expanded=if(expanded==row.cloudUuid)null else row.cloudUuid
                                }
                            ) {
                                Column(
                                    modifier=Modifier.fillMaxWidth().padding(12.dp),
                                    verticalArrangement=Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(row.cloudName,
                                        style=MaterialTheme.typography.titleSmall,
                                        fontWeight=FontWeight.Bold)
                                    Text(reviewLabel(row.status),
                                        style=MaterialTheme.typography.bodySmall,
                                        color=if(row.status==CatalogReviewStatus.ALREADY_MAPPED)
                                            ProColors.teal else Color(0xFF7D4132))
                                    Text("Cloud Category: ${row.cloudCategoryName ?: "—"}",
                                        style=MaterialTheme.typography.bodySmall)
                                    Text("Local Item: ${row.localName ?: "Not Found"}",
                                        style=MaterialTheme.typography.bodySmall)
                                    if (expanded==row.cloudUuid) {
                                        HorizontalDivider()
                                        Text("Cloud UUID: ${row.cloudUuid}",
                                            style=MaterialTheme.typography.bodySmall)
                                        Text("Cloud Revision: ${row.cloudRevision}",
                                            style=MaterialTheme.typography.bodySmall)
                                        Text("Local ID: ${row.localKey ?: "Not Matched"}",
                                            style=MaterialTheme.typography.bodySmall)
                                        Text("Local Category: ${row.localCategoryName ?: "—"}",
                                            style=MaterialTheme.typography.bodySmall)
                                        Text("Review Only — Server Owner Approval Not Active.",
                                            color=ProColors.teal,
                                            style=MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                Button(onClick={},enabled=false,modifier=Modifier.fillMaxWidth()) {
                    Text("Approve Mapping — Requires Secure Server")
                }
            }
        },
        confirmButton={ TextButton(onClick=onDismiss){ Text("Close") } }
    )
}
