package lk.peliyagoda.marketpro.ui

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.max
import lk.peliyagoda.marketpro.data.ItemEntity
import lk.peliyagoda.marketpro.data.PurchaseHistoryLot
import lk.peliyagoda.marketpro.util.DailyPriceTrend
import lk.peliyagoda.marketpro.util.MarketMath
import lk.peliyagoda.marketpro.util.dailyPriceTrends

/**
 * Historical item purchase trends: all records stay on device, selected category
 * controls the item dropdown, and both charts pan horizontally together.
 */
@Composable
fun ItemPriceChartDialog(
    categories: List<String>,
    items: List<ItemEntity>,
    history: List<PurchaseHistoryLot>,
    onPickItem: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    var category by remember(categories) {
        mutableStateOf(categories.firstOrNull().orEmpty())
    }
    var selectedItemId by remember { mutableStateOf<Long?>(null) }
    var dropdownOpen by remember { mutableStateOf(false) }
    val categoryItems = remember(category, items) {
        items.filter { it.category == category }.sortedBy { it.name.lowercase() }
    }
    val selectedItem = items.firstOrNull { it.id == selectedItemId }
    val trend = remember(history, selectedItemId) {
        if (selectedItemId == null) emptyList() else dailyPriceTrends(history)
    }
    // Render at most 180 dates per chart to avoid very large Android Canvas textures.
    val visibleTrend = trend.takeLast(180)
    var scrollOffset by remember(selectedItemId) { mutableFloatStateOf(0f) }
    LaunchedEffect(selectedItemId, visibleTrend.size) {
        // Start from the most recent recorded day. The user can drag left for history.
        scrollOffset = Float.MAX_VALUE
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.91f),
            shape = RoundedCornerShape(22.dp),
            color = ProColors.page
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 13.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Items price chart", color = ProColors.navy,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold)

                    }
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Text("Item category", color = ProColors.navy,
                    fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    lazyItems(categories) { c ->
                        CategoryChipForChart(
                            name = c,
                            selected = category == c,
                            onClick = {
                                category = c
                                selectedItemId = null
                                onPickItem(null)
                                dropdownOpen = false
                            }
                        )
                    }
                }

                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { dropdownOpen = true },
                        enabled = categoryItems.isNotEmpty()
                    ) {
                        Text(
                            selectedItem?.name ?: if (categoryItems.isEmpty()) "No items in category"
                                else "Choose an item (${categoryItems.size})",
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        androidx.compose.material3.Icon(
                            Icons.Filled.ArrowDropDown, contentDescription = "Open item list"
                        )
                    }
                    DropdownMenu(
                        expanded = dropdownOpen,
                        onDismissRequest = { dropdownOpen = false }
                    ) {
                        categoryItems.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(item.name, maxLines = 2) },
                                onClick = {
                                    dropdownOpen = false
                                    selectedItemId = item.id
                                    onPickItem(item.id)
                                }
                            )
                        }
                    }
                }
                when {
                    selectedItemId == null -> {
                        Text("Select a category and an item to view its historical charts.",
                            modifier = Modifier.padding(vertical = 32.dp),
                            color = ProColors.muted)
                    }
                    visibleTrend.isEmpty() -> {
                        Text("No recorded purchases for this item yet.",
                            modifier = Modifier.padding(vertical = 32.dp),
                            color = ProColors.muted)
                    }
                    else -> {
                        Text(
                            "${trend.size} recorded day(s) • ${history.size} purchase lot(s)",
                            color = ProColors.teal,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.bodyMedium
                        )

                        if (trend.size > 180) {
                            Text("Chart shows the most recent 180 recorded dates.",
                                color = ProColors.muted,
                                style = MaterialTheme.typography.labelSmall)
                        }
                        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                            // Prevent the lower quantity graph from being squeezed out.
                            val gap = 9.dp
                            val panelHeight = (maxHeight - gap) / 2
                            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                                TrendPanel(
                                    title = "Price trend • Rs./kg",
                                    points = visibleTrend,
                                    metric = { it.weightedPriceCentsPerKg / 100f },
                                    color = ProColors.teal,
                                    height = panelHeight,
                                    money = true,
                                    offset = scrollOffset,
                                    onDrag = { delta, maximum ->
                                        scrollOffset = (if (scrollOffset == Float.MAX_VALUE) maximum - delta
                                            else scrollOffset - delta).coerceIn(0f, maximum)
                                    }
                                )
                                TrendPanel(
                                    title = "Purchased quantity • kg",
                                    points = visibleTrend,
                                    metric = { it.quantityGrams / 1000f },
                                    color = ProColors.purple,
                                    height = panelHeight,
                                    money = false,
                                    offset = scrollOffset,
                                    onDrag = { delta, maximum ->
                                        scrollOffset = (if (scrollOffset == Float.MAX_VALUE) maximum - delta
                                            else scrollOffset - delta).coerceIn(0f, maximum)
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Composable
private fun CategoryChipForChart(
    name: String, selected: Boolean, onClick: () -> Unit
) {
    val background = if (selected) ProColors.teal else Color.White
    Surface(shape = RoundedCornerShape(10.dp), color = background,
        onClick = onClick) {
        Text(
            name, modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
            color = if (selected) Color.White else ProColors.navy,
            fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium
        )
    }
}

@Composable
private fun TrendPanel(
    title: String,
    points: List<DailyPriceTrend>,
    metric: (DailyPriceTrend) -> Float,
    color: Color,
    height: Dp,
    money: Boolean,
    offset: Float,
    onDrag: (Float, Float) -> Unit
) {
    val values = points.map(metric)
    val minimum = values.minOrNull() ?: 0f
    val maximum = values.maxOrNull() ?: 0f
    val margin = max(1f, maximum - minimum) * 0.3f
    val low = (minimum - margin).coerceAtLeast(0f)
    val high = maximum + margin
    val range = max(0.001f, high - low)
    Card(
        modifier = Modifier.fillMaxWidth().height(height),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 7.dp)) {
            Text(title, fontWeight = FontWeight.ExtraBold, color = color,
                style = MaterialTheme.typography.titleSmall, maxLines = 1)
            Row(Modifier.fillMaxWidth().weight(1f)) {
                // This axis never moves when the user swipes a chart.
                Canvas(Modifier.width(33.dp).fillMaxHeight()) {
                    val top = 24.dp.toPx()
                    val bottom = size.height - 24.dp.toPx()
                    val span = (bottom - top).coerceAtLeast(1f)
                    val font = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = android.graphics.Color.rgb(91, 108, 118)
                        textSize = 10.5f * density
                        textAlign = Paint.Align.RIGHT
                    }
                    for (tick in 0..3) {
                        val v = low + range * tick / 3f
                        val y = bottom - span * tick / 3f
                        val label = if (money) "%.0f".format(java.util.Locale.US, v)
                            else "%.1f".format(java.util.Locale.US, v)
                        drawContext.canvas.nativeCanvas.drawText(
                            label, size.width - 1.dp.toPx(), y + 4.dp.toPx(), font
                        )
                    }
                }
                Canvas(
                    Modifier.weight(1f).fillMaxHeight().clipToBounds()
                        .pointerInput(points) {
                            detectHorizontalDragGestures { change, delta ->
                                change.consume()
                                val extent = (points.size - 1).coerceAtLeast(0) *
                                    86.dp.toPx() + 39.dp.toPx()
                                onDrag(delta, (extent - size.width).coerceAtLeast(0f))
                            }
                        }
                ) {
                    val step = 86.dp.toPx()
                    val extent = (points.size - 1).coerceAtLeast(0) * step + 39.dp.toPx()
                    val maximumScroll = (extent - size.width).coerceAtLeast(0f)
                    val shift = if (offset == Float.MAX_VALUE) maximumScroll
                        else offset.coerceIn(0f, maximumScroll)
                    val top = 24.dp.toPx()
                    val bottom = size.height - 24.dp.toPx()
                    val span = (bottom - top).coerceAtLeast(1f)
                    fun y(v: Float) = bottom - (v - low) / range * span
                    val font = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 10.5f * density
                        textAlign = Paint.Align.CENTER
                    }
                    for (tick in 0..3) {
                        val yy = bottom - span * tick / 3f
                        drawLine(Color(0xFFDAE6ED), Offset(0f, yy),
                            Offset(size.width, yy), strokeWidth = 1.dp.toPx())
                    }
                    val path = Path()
                    values.forEachIndexed { i, v ->
                        val x = 17.dp.toPx() + i * step - shift
                        if (i == 0) path.moveTo(x, y(v)) else path.lineTo(x, y(v))
                    }
                    if (values.size > 1) drawPath(
                        path, color, style = Stroke(width = 2.5.dp.toPx())
                    )
                    values.forEachIndexed { i, v ->
                        val x = 17.dp.toPx() + i * step - shift
                        if (x < -15.dp.toPx() || x > size.width + 15.dp.toPx())
                            return@forEachIndexed
                        val yy = y(v)
                        drawCircle(color, radius = 3.5.dp.toPx(),
                            center = Offset(x, yy))
                        font.color = android.graphics.Color.rgb(16, 42, 55)
                        drawContext.canvas.nativeCanvas.drawText(
                            "%.2f".format(java.util.Locale.US, v), x,
                            (yy - 9.dp.toPx()).coerceAtLeast(11.dp.toPx()), font
                        )
                        font.color = android.graphics.Color.rgb(91, 108, 118)
                        drawContext.canvas.nativeCanvas.drawText(
                            points[i].date.takeLast(5), x,
                            size.height - 4.dp.toPx(), font
                        )
                    }
                }
            }
        }
    }
}
