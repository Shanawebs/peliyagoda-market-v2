package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.data.ItemEntity
import lk.peliyagoda.marketpro.data.MarketConstants
import lk.peliyagoda.marketpro.data.RequisitionEntity
import lk.peliyagoda.marketpro.util.ConsolidatedRow
import lk.peliyagoda.marketpro.util.MarketMath
import java.time.LocalDate

@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier, accent: Color = ProColors.teal) {
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = Color.White, tonalElevation = 1.dp) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = ProColors.muted)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold,
                color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun EmptyState(title: String, detail: String) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 85.dp, horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("◇", style = MaterialTheme.typography.displayMedium, color = ProColors.teal)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
            color = ProColors.navy)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = ProColors.muted)
    }
}

@Composable
private fun StatusTag(row: ConsolidatedRow) {
    val status = when {
        row.purchase == null || row.purchasedGrams == 0L -> "PENDING"
        row.varianceGrams > 0 -> "EXTRA +${MarketMath.kg(row.varianceGrams)} kg"
        row.varianceGrams < 0 -> "SHORT ${MarketMath.kg(-row.varianceGrams)} kg"
        else -> "MATCHED"
    }
    val color = when {
        row.purchase == null || row.purchasedGrams == 0L -> ProColors.muted
        row.varianceGrams > 0 -> ProColors.teal
        row.varianceGrams < 0 -> ProColors.red
        else -> ProColors.green
    }
    val bg = when {
        row.purchase == null || row.purchasedGrams == 0L -> Color(0xFFE8EDF0)
        row.varianceGrams > 0 -> Color(0xFFE2F3FB)
        row.varianceGrams < 0 -> Color(0xFFFFE8EA)
        else -> Color(0xFFE4F5EA)
    }
    Text(status, color = color, fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.background(bg, RoundedCornerShape(12.dp))
            .padding(horizontal = 9.dp, vertical = 6.dp))
}

@Composable
fun DashboardScreen(date: LocalDate, rows: List<ConsolidatedRow>, onExport: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf("All") }
    val shown = rows.filter { filter == "All" || it.item.category == filter }
    val totalReq = rows.sumOf { it.requiredGrams }
    val totalPurch = rows.sumOf { it.purchasedGrams }
    val totalCents = rows.sumOf { it.lineCents }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Consolidated requirements", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = ProColors.navy)
                        Text("All kitchens • Sorted A–Z", color = ProColors.muted,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = onExport) {
                        Icon(Icons.Default.FileDownload, contentDescription = null)
                        Spacer(Modifier.width(5.dp))
                        Text("CSV")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Metric("Total required", "${MarketMath.kg(totalReq)} kg", Modifier.weight(1f))
                    Metric("Purchased", "${MarketMath.kg(totalPurch)} kg", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Metric("Estimated bill", "Rs. ${MarketMath.lkr(totalCents)}", Modifier.weight(1f), ProColors.purple)
                    Metric("Item count", "${rows.size}", Modifier.weight(1f), ProColors.navy)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("All", MarketConstants.VEGETABLES, MarketConstants.FRUITS).forEach { c ->
                    FilterChip(selected = filter == c, onClick = { filter = c },
                        label = { Text(if (c == "All") "All" else c) })
                }
            }
        }
        if (shown.isEmpty()) {
            item { EmptyState("No requirements yet", "Open Kitchens to select items and enter the required quantities.") }
        } else {
            lazyItems(shown, key = { it.item.id }) { row ->
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(row.item.name, fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium)
                                Text(row.item.category, color = ProColors.muted,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            StatusTag(row)
                        }
                        HorizontalDivider(color = ProColors.pale)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("Required", color = ProColors.muted,
                                    style = MaterialTheme.typography.labelSmall)
                                Text("${MarketMath.kg(row.requiredGrams)} kg", fontWeight = FontWeight.SemiBold)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Purchased", color = ProColors.muted,
                                    style = MaterialTheme.typography.labelSmall)
                                Text("${MarketMath.kg(row.purchasedGrams)} kg", fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Text("Main ${MarketMath.kg(row.mainGrams)}  •  Indian ${MarketMath.kg(row.indianGrams)}  •  Pastry ${MarketMath.kg(row.pastryGrams)} kg",
                            style = MaterialTheme.typography.bodySmall, color = ProColors.muted)
                    }
                }
            }
        }
    }
}

@Composable
fun KitchensScreen(
    items: List<ItemEntity>, requirements: List<RequisitionEntity>,
    vm: MarketViewModel, message: (String) -> Unit
) {
    var kitchenIndex by rememberSaveable { mutableIntStateOf(0) }
    val kitchen = MarketConstants.kitchens[kitchenIndex]
    var pickerOpen by remember { mutableStateOf(false) }
    val current = requirements.filter { it.kitchen == kitchen }
        .sortedBy { entry -> items.firstOrNull { it.id == entry.itemId }?.name?.lowercase() ?: "" }
    val itemMap = items.associateBy { it.id }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = kitchenIndex, containerColor = Color.White) {
            MarketConstants.kitchens.forEachIndexed { index, name ->
                Tab(selected = kitchenIndex == index, onClick = { kitchenIndex = index },
                    text = { Text(name, maxLines = 2, minLines = 2, style = MaterialTheme.typography.labelMedium) })
            }
        }
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 116.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = ProColors.pale) {
                        Column(Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(kitchen, style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold, color = ProColors.navy)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${current.size} selected items", color = ProColors.muted)
                                Text("${MarketMath.kg(current.sumOf { it.quantityGrams })} kg",
                                    color = ProColors.teal, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                if (current.isEmpty()) item {
                    EmptyState("No items selected", "Use the Add Items button to create this kitchen's requisition.")
                }
                lazyItems(current, key = { "${it.businessDate}:${it.kitchen}:${it.itemId}" }) { entry ->
                    itemMap[entry.itemId]?.let { item ->
                        RequirementCard(
                            item = item, entry = entry,
                            onSave = { grams -> vm.saveRequirement(kitchen, item.id, grams) { result ->
                                if (result.isFailure) message("Could not auto-save quantity")
                            } },
                            onDelete = { vm.removeRequirement(kitchen, item.id) { result ->
                                message(if (result.isSuccess) "Item removed" else "Could not remove item")
                            } }
                        )
                    }
                }
            }
            ExtendedFloatingActionButton(
                onClick = { pickerOpen = true },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add items") },
                containerColor = ProColors.teal,
                contentColor = Color.White
            )
        }
    }
    if (pickerOpen) ItemPickerSheet(
        kitchen = kitchen,
        allItems = items,
        existingIds = current.map { it.itemId }.toSet(),
        onDismiss = { pickerOpen = false },
        onConfirm = { ids ->
            vm.addRequirements(kitchen, ids) { result ->
                message(if (result.isSuccess) "${ids.size} item(s) added" else "Could not add items")
            }
            pickerOpen = false
        }
    )
}

@Composable
private fun RequirementCard(
    item: ItemEntity, entry: RequisitionEntity,
    onSave: (Long) -> Unit, onDelete: () -> Unit
) {
    // Keep the typing draft stable across database Flow updates.
    var gramsText by remember(entry.businessDate, entry.kitchen, entry.itemId) {
        mutableStateOf(MarketMath.editKgOrBlank(entry.quantityGrams))
    }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Remove " + item.name + "?") },
        text = { Text("This kitchen's requirement for this date will be removed. Other kitchens are unaffected.") },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            onDelete()
        }) { Text("Remove", color = ProColors.red) } }
    )
    val gramsValue = MarketMath.parseBlankGrams(gramsText)
    val invalid = gramsValue == null || gramsValue > 1_000_000_000
    SectionCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Column(Modifier.weight(1.15f)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
                Text(item.category, style = MaterialTheme.typography.labelSmall,
                    color = ProColors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OutlinedTextField(
                value = gramsText,
                onValueChange = { input ->
                    gramsText = input
                    val parsed = MarketMath.parseBlankGrams(input)
                    if (parsed != null && parsed <= 1_000_000_000) onSave(parsed)
                },
                label = { Text("Require") },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                suffix = { Text("kg") },
                shape = RoundedCornerShape(12.dp),
                singleLine = true,
                isError = invalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.DeleteOutline,
                    contentDescription = "Remove " + item.name, tint = ProColors.red)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemPickerSheet(
    kitchen: String, allItems: List<ItemEntity>, existingIds: Set<Long>,
    onDismiss: () -> Unit, onConfirm: (Set<Long>) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MarketConstants.VEGETABLES) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    val filtered = allItems.filter {
        it.category == category && it.name.contains(search, ignoreCase = true)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).fillMaxHeight(0.86f),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Select items • $kitchen", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            OutlinedTextField(
                value = search, onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Search master catalog") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MarketConstants.categories.forEach { cat ->
                    FilterChip(selected = category == cat, onClick = { category = cat },
                        label = { Text(cat) })
                }
            }
            Text("Selected: ${selected.size}", color = ProColors.teal,
                style = MaterialTheme.typography.labelLarge)
            LazyColumn(modifier = Modifier.weight(1f)) {
                lazyItems(filtered, key = { it.id }) { item ->
                    val alreadyAdded = item.id in existingIds
                    Row(
                        Modifier.fillMaxWidth()
                            .clickable(enabled = !alreadyAdded) {
                                selected = if (item.id in selected) selected - item.id else selected + item.id
                            }
                            .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = item.id in selected || alreadyAdded,
                            enabled = !alreadyAdded, onCheckedChange = { yes ->
                                selected = if (yes) selected + item.id else selected - item.id
                            })
                        Column(Modifier.weight(1f)) {
                            Text(item.name, fontWeight = FontWeight.Medium)
                            if (alreadyAdded) Text("Already selected", color = ProColors.muted,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(onClick = { onConfirm(selected) }, enabled = selected.isNotEmpty(),
                    modifier = Modifier.weight(1.4f)) {
                    Text("Add (${selected.size})")
                }
            }
        }
    }
}

@Composable
fun MarketScreen(rows: List<ConsolidatedRow>, vm: MarketViewModel, message: (String) -> Unit) {
    val date by vm.date.collectAsState()
    val totalRequired = rows.sumOf { it.requiredGrams }
    val totalPurchased = rows.sumOf { it.purchasedGrams }
    val totalBill = rows.sumOf { it.lineCents }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Surface(color = Color(0xFFEAE9FB), shape = RoundedCornerShape(19.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Market purchase & billing", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold, color = ProColors.navy)
                    Text("Enter quantities and prices • Saves automatically",
                        color = ProColors.muted, style = MaterialTheme.typography.bodySmall)
                    Text("Rs. ${MarketMath.lkr(totalBill)}", color = ProColors.purple,
                        style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Required: ${MarketMath.kg(totalRequired)} kg",
                            fontWeight = FontWeight.SemiBold)
                        Text("Bought: ${MarketMath.kg(totalPurchased)} kg",
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        if (rows.isEmpty()) item {
            EmptyState("No purchasing items", "Enter requirements in Kitchens first.")
        }
        lazyItems(rows, key = { it.item.id }) { row ->
            PurchaseCard(
                row = row,
                date = date.toString(),
                onSave = { grams, cents ->
                    vm.savePurchase(row.item.id, grams, cents) { result ->
                        if (result.isFailure) message("Could not auto-save purchase")
                    }
                }
            )
        }
    }
}

@Composable
private fun PurchaseCard(
    row: ConsolidatedRow,
    date: String,
    onSave: (Long, Long) -> Unit
) {
    // Don't re-key these drafts on Room's incoming purchase totals: a database
    // emission while typing must never reset the keyboard field or caret.
    var purchased by remember(date, row.item.id) {
        mutableStateOf(MarketMath.editKgOrBlank(row.purchase?.quantityGrams ?: 0L))
    }
    var price by remember(date, row.item.id) {
        mutableStateOf(MarketMath.editPriceOrBlank(row.purchase?.priceCentsPerKg ?: 0L))
    }
    fun autoSaveIfValid(qtyText: String, priceText: String) {
        val grams = MarketMath.parseBlankGrams(qtyText)
        val cents = MarketMath.parseBlankPriceCents(priceText)
        if (grams != null && cents != null &&
            grams <= 1_000_000_000 && cents <= 1_000_000_000) {
            onSave(grams, cents)
        }
    }
    val qty = MarketMath.parseBlankGrams(purchased)
    val rate = MarketMath.parseBlankPriceCents(price)
    val qtyInvalid = qty == null || qty > 1_000_000_000
    val rateInvalid = rate == null || rate > 1_000_000_000
    val preview = if (!qtyInvalid && !rateInvalid) {
        MarketMath.billCents(qty!!, rate!!)
    } else row.lineCents
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF4FAFD)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text(row.item.name, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold, color = ProColors.navy,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Required: ${MarketMath.kg(row.requiredGrams)} kg",
                        color = ProColors.teal, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodySmall)
                }
                StatusTag(row)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedTextField(
                    value = purchased,
                    onValueChange = { input ->
                        purchased = input
                        autoSaveIfValid(input, price)
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("Purchased") },
                    suffix = { Text("kg") },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold),
                    shape = RoundedCornerShape(11.dp),
                    isError = qtyInvalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { input ->
                        price = input
                        autoSaveIfValid(purchased, input)
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text("Market Price") },
                    prefix = { Text("Rs.") },
                    suffix = { Text("/kg") },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = FontWeight.SemiBold),
                    shape = RoundedCornerShape(11.dp),
                    isError = rateInvalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
            Text(
                "Subtotal: Rs. ${MarketMath.lkr(preview)}",
                modifier = Modifier.fillMaxWidth(),
                color = ProColors.purple,
                textAlign = TextAlign.End,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogSheet(
    items: List<ItemEntity>, onClose: () -> Unit,
    add: (String, String) -> Unit,
    rename: (Long, String, (Boolean) -> Unit) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MarketConstants.VEGETABLES) }
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<ItemEntity?>(null) }
    var editedName by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf(false) }
    var renameSaving by remember { mutableStateOf(false) }
    editing?.let { item ->
        AlertDialog(
            onDismissRequest = { if (!renameSaving) editing = null },
            title = { Text("Rename " + item.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Past requisitions and purchases will remain linked to this item.")
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it; renameError = false },
                        label = { Text("Item name") },
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        isError = renameError,
                        supportingText = {
                            if (renameError) Text("This name exists already or could not be saved.")
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }, enabled = !renameSaving) { Text("Cancel") }
            },
            confirmButton = {
                Button(
                    onClick = {
                        renameSaving = true
                        rename(item.id, editedName) { ok ->
                            renameSaving = false
                            if (ok) { editing = null; renameError = false }
                            else renameError = true
                        }
                    },
                    enabled = !renameSaving && editedName.trim().isNotEmpty() &&
                        editedName.trim().length <= 80
                ) { Text(if (renameSaving) "Saving…" else "Save name") }
            }
        )
    }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Master item catalog", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Text("Add or rename vegetables and fruits.", color = ProColors.muted)
            OutlinedTextField(value = name, onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(), label = { Text("New item name") },
                shape = RoundedCornerShape(12.dp), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                MarketConstants.categories.forEach { cat ->
                    FilterChip(selected = category == cat, onClick = { category = cat },
                        label = { Text(cat) })
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { add(name, category); name = "" }, enabled = name.isNotBlank()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(" Add")
                }
            }
            HorizontalDivider()
            OutlinedTextField(value = search, onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Find an item") },
                shape = RoundedCornerShape(12.dp), singleLine = true)
            Text("${items.size} catalog items", color = ProColors.muted,
                style = MaterialTheme.typography.labelMedium)
            LazyColumn(modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                lazyItems(items.filter { it.name.contains(search, true) }, key = { it.id }) { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodyLarge)
                            Text(item.category, color = ProColors.muted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = {
                            editing = item
                            editedName = item.name
                            renameError = false
                            renameSaving = false
                        }) {
                            Icon(Icons.Default.Edit,
                                contentDescription = "Rename " + item.name,
                                tint = ProColors.teal)
                        }
                    }
                    HorizontalDivider(color = ProColors.pale)
                }
            }
        }
    }
}
