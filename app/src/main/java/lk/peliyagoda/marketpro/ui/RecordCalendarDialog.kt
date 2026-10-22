package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Material DatePicker has no supported per-day custom color slot in this version.
 * This self-contained calendar highlights actual DB business dates in amber.
 * Selected day is teal; a small amber dot still marks a recorded selected day.
 */
@Composable
fun RecordCalendarDialog(
    selected: LocalDate,
    recordedDays: Set<LocalDate>,
    onSelect: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    var visibleMonth by remember { mutableStateOf(YearMonth.from(selected)) }
    var chosen by remember { mutableStateOf(selected) }
    val firstWeekday = visibleMonth.atDay(1).dayOfWeek.value - 1 // Monday=0
    val daysInMonth = visibleMonth.lengthOfMonth()
    val numberOfRows = (firstWeekday + daysInMonth + 6) / 7
    val amber = Color(0xFFF1B84A)
    val dark = ProColors.navy
    val teal = ProColors.teal

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Date", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(onClick = { visibleMonth = visibleMonth.minusYears(1) }) {
                        Text("«", style = MaterialTheme.typography.titleLarge)
                    }
                    TextButton(onClick = { visibleMonth = visibleMonth.minusMonths(1) }) {
                        Text("‹", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        visibleMonth.format(DateTimeFormatter.ofPattern("MMM yyyy", Locale.ENGLISH)),
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium,
                        color = dark
                    )
                    TextButton(onClick = { visibleMonth = visibleMonth.plusMonths(1) }) {
                        Text("›", style = MaterialTheme.typography.titleLarge)
                    }
                    TextButton(onClick = { visibleMonth = visibleMonth.plusYears(1) }) {
                        Text("»", style = MaterialTheme.typography.titleLarge)
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    listOf("M", "T", "W", "T", "F", "S", "S").forEach { label ->
                        Box(
                            modifier = Modifier.weight(1f).height(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = ProColors.muted,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                repeat(numberOfRows) { rowIndex ->
                    Row(Modifier.fillMaxWidth()) {
                        repeat(7) { col ->
                            val day = rowIndex * 7 + col - firstWeekday + 1
                            if (day in 1..daysInMonth) {
                                val date = visibleMonth.atDay(day)
                                val active = date == chosen
                                val hasRecords = date in recordedDays
                                val background = when {
                                    active -> teal
                                    hasRecords -> amber
                                    else -> Color.Transparent
                                }
                                val foreground = when {
                                    active -> Color.White
                                    else -> dark
                                }
                                Box(
                                    modifier = Modifier.weight(1f).height(42.dp).padding(2.dp)
                                        .clip(CircleShape)
                                        .background(background)
                                        .clickable { chosen = date },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            day.toString(), color = foreground,
                                            fontWeight = if (active || hasRecords) FontWeight.Bold
                                                else FontWeight.Normal,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        if (active && hasRecords) {
                                            Box(Modifier.size(4.dp).clip(CircleShape)
                                                .background(amber))
                                        }
                                    }
                                }
                            } else {
                                Spacer(Modifier.weight(1f).height(42.dp))
                            }
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(amber))
                    Text("Dates With Entered Data", color = ProColors.muted,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(chosen) }) { Text("Select") }
        }
    )
}
