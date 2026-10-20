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
import lk.peliyagoda.marketpro.util.completedPurchases
import java.time.LocalDate

/** Purchase completion here means bought > 0 and bought >= required. */
@Composable
fun CompletedPdfDialog(
    date: LocalDate,
    rows: List<ConsolidatedRow>,
    onClose: () -> Unit,
    onSavePdf: (List<ConsolidatedRow>) -> Unit
) {
    val completed = remember(date, rows) { completedPurchases(rows) }
    val finalCents = completed.sumOf { it.lineCents }
    AlertDialog(
        onDismissRequest = onClose,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Completed purchases", fontWeight = FontWeight.ExtraBold)
                Text(date.toString(), style = MaterialTheme.typography.bodySmall,
                    color = ProColors.muted)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (completed.isEmpty()) {
                    Text(
                        "No completed purchases on this date. A completed item must have a " +
                            "purchased quantity equal to or above the required quantity.",
                        color = ProColors.muted
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        items(completed, key = { it.item.id }) { row ->
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        row.item.name,
                                        modifier = Modifier.weight(1f),
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Rs. " + MarketMath.lkr(row.lineCents),
                                        color = ProColors.teal,
                                        fontWeight = FontWeight.ExtraBold,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                Text(
                                    "Required: " + MarketMath.kg(row.requiredGrams) +
                                        " kg   Purchased: " + MarketMath.kg(row.purchasedGrams) + " kg",
                                    color = ProColors.muted,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                    HorizontalDivider()
                    Text("Completed items: " + completed.size,
                        color = ProColors.muted,
                        style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Final total: Rs. " + MarketMath.lkr(finalCents),
                        color = ProColors.teal,
                        fontWeight = FontWeight.ExtraBold,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
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
