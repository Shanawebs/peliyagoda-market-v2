package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.util.ConsolidatedRow
import lk.peliyagoda.marketpro.util.MarketMath
import lk.peliyagoda.marketpro.util.completedCategoryGroups
import lk.peliyagoda.marketpro.util.totalMarketSpentCents
import java.time.LocalDate

/** Consistent right-aligned money columns prevent long amounts from wrapping labels. */
@Composable
private fun ReportMoneyRow(
    label: String,
    cents: Long,
    color: androidx.compose.ui.graphics.Color = ProColors.navy,
    prominent: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = if (prominent) color else ProColors.muted,
            fontWeight = if (prominent) FontWeight.ExtraBold else FontWeight.Medium,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2
        )
        Text(
            text = "Rs. " + MarketMath.lkr(cents),
            textAlign = TextAlign.End,
            color = color,
            fontWeight = FontWeight.ExtraBold,
            style = if (prominent) MaterialTheme.typography.titleMedium
                else MaterialTheme.typography.bodyMedium,
            maxLines = 2
        )
    }
}

/**
 * Category sections + financial footer share ONE scrolling column. This keeps
 * the bottom actions fully reachable on short phones and removes the obsolete
 * helper note without leaving an empty block.
 */
@Composable
fun CompletedPdfDialog(
    date: LocalDate,
    rows: List<ConsolidatedRow>,
    cashReceivedCents: Long,
    onClose: () -> Unit,
    onSavePdf: (List<ConsolidatedRow>) -> Unit
) {
    val groups = remember(date, rows) { completedCategoryGroups(rows) }
    val completed = remember(groups) { groups.flatMap { it.rows } }
    val finalCents = completed.sumOf { it.lineCents }
    val totalSpent = totalMarketSpentCents(rows)
    val balance = cashReceivedCents - totalSpent

    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Completed Purchases By Category", fontWeight = FontWeight.ExtraBold)
                Text(
                    date.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = ProColors.muted
                )
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 490.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                if (completed.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            "No Completed Purchases On This Date. Items Must Be Fully Bought First.",
                            color = ProColors.muted
                        )
                    }
                } else {
                    items(groups, key = { it.name }) { group ->
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                group.name,
                                fontWeight = FontWeight.ExtraBold,
                                color = ProColors.teal,
                                style = MaterialTheme.typography.titleMedium
                            )
                            group.numberedRows().forEach { (number, row) ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        number.toString() + ". " + row.item.name,
                                        modifier = Modifier.weight(1f),
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Rs. " + MarketMath.lkr(row.lineCents),
                                        color = ProColors.teal,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.End,
                                        maxLines = 2
                                    )
                                }
                                Text(
                                    "Required: " + MarketMath.kg(row.requiredGrams) +
                                        " kg  •  Purchased: " +
                                        MarketMath.kg(row.purchasedGrams) + " kg",
                                    color = ProColors.muted,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                HorizontalDivider()
                            }
                            ReportMoneyRow(
                                label = group.name + " Subtotal",
                                cents = group.subtotalCents,
                                color = ProColors.teal,
                                prominent = true
                            )
                            HorizontalDivider()
                        }
                    }
                    item(key = "report-summary") {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "Completed Items: " + completed.size,
                                color = ProColors.muted
                            )
                            ReportMoneyRow(
                                "Completed Final Total", finalCents,
                                ProColors.teal, prominent = true
                            )
                            ReportMoneyRow(
                                "Cash Received", cashReceivedCents
                            )
                            ReportMoneyRow(
                                "Total Spent (All Purchases)", totalSpent
                            )
                            HorizontalDivider()
                            ReportMoneyRow(
                                "Cash Balance", balance,
                                if (balance >= 0L) ProColors.green else ProColors.red,
                                prominent = true
                            )
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Close") }
        },
        confirmButton = {
            Button(
                enabled = completed.isNotEmpty(),
                onClick = { onSavePdf(completed.toList()) }
            ) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text("Save PDF")
            }
        }
    )
}
