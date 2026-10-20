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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.util.ConsolidatedRow
import lk.peliyagoda.marketpro.util.MarketMath
import lk.peliyagoda.marketpro.util.completedCategoryGroups
import lk.peliyagoda.marketpro.util.totalMarketSpentCents
import java.time.LocalDate

/** Preview matches the completed-only PDF category totals and cash reconciliation. */
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
                Text("Completed purchases by category", fontWeight = FontWeight.ExtraBold)
                Text(date.toString(), style = MaterialTheme.typography.bodySmall,
                    color = ProColors.muted)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                if (completed.isEmpty()) {
                    Text(
                        "No completed purchases on this date. Items must be fully bought first.",
                        color = ProColors.muted
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(groups, key = { it.name }) { group ->
                            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(
                                    group.name,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = ProColors.teal,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                group.rows.forEach { row ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            row.item.name,
                                            modifier = Modifier.weight(1f),
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "Rs. " + MarketMath.lkr(row.lineCents),
                                            color = ProColors.teal,
                                            fontWeight = FontWeight.Bold
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
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Category subtotal", fontWeight = FontWeight.ExtraBold)
                                    Text(
                                        "Rs. " + MarketMath.lkr(group.subtotalCents),
                                        fontWeight = FontWeight.ExtraBold,
                                        color = ProColors.teal
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                    HorizontalDivider()
                    Text(
                        "Completed items: " + completed.size,
                        color = ProColors.muted
                    )
                    Text(
                        "Completed final total: Rs. " + MarketMath.lkr(finalCents),
                        color = ProColors.teal,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text("Cash received: Rs. " + MarketMath.lkr(cashReceivedCents),
                        fontWeight = FontWeight.SemiBold)
                    Text("Total spent (all purchases): Rs. " + MarketMath.lkr(totalSpent),
                        fontWeight = FontWeight.SemiBold)
                    Text(
                        "Cash balance: Rs. " + MarketMath.lkr(balance),
                        color = if (balance >= 0) ProColors.green else ProColors.red,
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Cash balance includes all purchased items, including shortages; " +
                            "completed category subtotals only include completed items.",
                        color = ProColors.muted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
        confirmButton = {
            Button(enabled = completed.isNotEmpty(), onClick = {
                onSavePdf(completed.toList())
            }) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                Spacer(Modifier.width(5.dp))
                Text("Save PDF")
            }
        }
    )
}
