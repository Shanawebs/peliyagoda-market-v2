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
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import lk.peliyagoda.marketpro.data.PurchaseAdditionEntity
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
        row.purchasedGrams == 0L -> "PENDING"
        row.varianceGrams > 0 -> "COMPLETED +${MarketMath.kg(row.varianceGrams)}"
        row.varianceGrams < 0 -> "SHORTAGE (-${MarketMath.kg(-row.varianceGrams)} kg)"
        else -> "COMPLETED"
    }
    val color = when {
        row.purchasedGrams == 0L -> Color(0xFF475569) // dark gray
        row.varianceGrams > 0 -> Color(0xFF166534)    // completed with surplus
        row.varianceGrams < 0 -> Color(0xFF991B1B)    // dark red
        else -> Color(0xFF166534)                      // dark green
    }
    Surface(shape = RoundedCornerShape(12.dp), color = color) {
        Text(
            status,
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
        )
    }
}

@Composable
private fun CategoryChip(category: String, active: Boolean, onClick: () -> Unit) {
    // A distinct dark selected state with white lettering also for custom categories.
    val selectedBg = when (category) {
        MarketConstants.VEGETABLES -> Color(0xFF166534)
        MarketConstants.FRUITS -> Color(0xFF5B21B6)
        "All" -> ProColors.navy
        else -> ProColors.teal
    }
    FilterChip(
        selected = active,
        onClick = onClick,
        label = {
            Text(category, color = if (active) Color.White else ProColors.navy,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = selectedBg,
            selectedLabelColor = Color.White
        )
    )
}

@Composable
fun DashboardScreen(date: LocalDate, rows: List<ConsolidatedRow>, categories: List<String>, onExport: () -> Unit) {
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
                        Text("PDF")
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
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { CategoryChip(category = "All", active = filter == "All", onClick = { filter = "All" }) }
                lazyItems(categories) { c ->
                    CategoryChip(category = c, active = filter == c, onClick = { filter = c })
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
                                Text(row.item.name, fontWeight = FontWeight.ExtraBold,
                                    style = MaterialTheme.typography.titleLarge,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                "Main" to row.mainGrams,
                                "Indian" to row.indianGrams,
                                "Bar/Pastry" to row.pastryGrams
                            ).forEach { (label, grams) ->
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    color = ProColors.pale
                                ) {
                                    Column(
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 9.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(label, color = ProColors.teal,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold, maxLines = 1)
                                        Text("${MarketMath.kg(grams)} kg",
                                            color = ProColors.navy,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.ExtraBold, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun KitchensScreen(
    items: List<ItemEntity>, categories: List<String>, requirements: List<RequisitionEntity>,
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
            val displayNames = listOf("Main", "Indian", "Bar/Pastry")
            MarketConstants.kitchens.forEachIndexed { index, _ ->
                Tab(
                    selected = kitchenIndex == index,
                    onClick = { kitchenIndex = index },
                    text = {
                        Text(
                            displayNames[index],
                            maxLines = 1,
                            fontWeight = FontWeight.ExtraBold,
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center
                        )
                    }
                )
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
        categories = categories,
        addCategory = { name, done -> vm.addCategory(name, done) },
        addMasterItem = { name, cat, done -> vm.addItem(name, cat, done) },
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

@Composable
private fun CategoryStrip(
    categories: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onCreate: (String, (Boolean) -> Unit) -> Unit
) {
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    if (adding) {
        AlertDialog(
            onDismissRequest = { if (!busy) adding = false },
            title = { Text("New item category") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Create a new category for vegetables, fruits, or other market items.")
                    OutlinedTextField(
                        value = name, onValueChange = { name = it; error = false },
                        label = { Text("Category name") },
                        singleLine = true, isError = error
                    )
                    if (error) Text("Category already exists or is invalid", color = ProColors.red)
                }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { adding = false }) { Text("Cancel") }
            },
            confirmButton = {
                Button(enabled = !busy && name.trim().isNotEmpty() && name.trim().length <= 40,
                    onClick = {
                        busy = true
                        val proposed = name.trim()
                        onCreate(proposed) { created ->
                            busy = false
                            if (created) {
                                onSelect(proposed)
                                adding = false
                                name = ""
                            } else error = true
                        }
                    }) { Text(if (busy) "Saving…" else "Add category") }
            }
        )
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        lazyItems(categories) { c ->
            CategoryChip(category = c, active = selected == c, onClick = { onSelect(c) })
        }
        item {
            OutlinedButton(onClick = { name = ""; error = false; adding = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Category")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemPickerSheet(
    kitchen: String, allItems: List<ItemEntity>, existingIds: Set<Long>,
    categories: List<String>,
    addCategory: (String, (Boolean) -> Unit) -> Unit,
    addMasterItem: (String, String, (Boolean) -> Unit) -> Unit,
    onDismiss: () -> Unit, onConfirm: (Set<Long>) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(MarketConstants.VEGETABLES) }
    var selected by remember { mutableStateOf(setOf<Long>()) }
    var newItemDialog by remember { mutableStateOf(false) }
    var newItemName by remember { mutableStateOf("") }
    var newItemError by remember { mutableStateOf(false) }
    var newItemBusy by remember { mutableStateOf(false) }
    var pendingAutoSelect by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(allItems, pendingAutoSelect) {
        val target = pendingAutoSelect
        if (target != null) {
            val created = allItems.firstOrNull {
                it.category == category && it.name.equals(target, ignoreCase = true)
            }
            if (created != null) {
                selected = selected + created.id
                pendingAutoSelect = null
            }
        }
    }
    if (newItemDialog) {
        AlertDialog(
            onDismissRequest = { if (!newItemBusy) newItemDialog = false },
            title = { Text("Add new master item") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Category: $category", color = ProColors.teal,
                        fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = newItemName,
                        onValueChange = { newItemName = it; newItemError = false },
                        label = { Text("New item name") },
                        isError = newItemError,
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    if (newItemError) {
                        Text("Item already exists or name is invalid", color = ProColors.red)
                    }
                }
            },
            confirmButton = {
                Button(
                    enabled = !newItemBusy && newItemName.trim().isNotBlank() &&
                        newItemName.trim().length <= 80,
                    onClick = {
                        val newName = newItemName.trim()
                        newItemBusy = true
                        addMasterItem(newName, category) { saved ->
                            newItemBusy = false
                            if (saved) {
                                pendingAutoSelect = newName
                                search = ""
                                newItemDialog = false
                                newItemName = ""
                            } else newItemError = true
                        }
                    }
                ) { Text(if (newItemBusy) "Adding…" else "Add item") }
            },
            dismissButton = {
                TextButton(enabled = !newItemBusy,
                    onClick = { newItemDialog = false }) { Text("Cancel") }
            }
        )
    }
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
            CategoryStrip(
                categories = categories,
                selected = category,
                onSelect = { category = it },
                onCreate = addCategory
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Selected: ${selected.size}", color = ProColors.teal,
                    style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        newItemName = ""
                        newItemError = false
                        newItemDialog = true
                    }
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(" Add Item")
                }
            }
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
                },
                onAdd = { grams, cents, done ->
                    vm.addPurchaseAddition(row.item.id, grams, cents, done)
                },
                onEdit = { lotId, grams, cents ->
                    vm.editPurchaseAddition(row.item.id, lotId, grams, cents) { result ->
                        if (result.isFailure) message("Could not save additional purchase change")
                    }
                },
                message = message
            )
        }
    }
}

@Composable
private fun PurchaseCard(
    row: ConsolidatedRow,
    date: String,
    onSave: (Long, Long) -> Unit,
    onAdd: (Long, Long, (Result<Unit>) -> Unit) -> Unit,
    onEdit: (Long, Long, Long) -> Unit,
    message: (String) -> Unit
) {
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
        MarketMath.billCents(qty!!, rate!!) + row.additionalCents
    } else row.lineCents
    var addOpen by remember(date, row.item.id) { mutableStateOf(false) }
    var additionalQty by remember(date, row.item.id) { mutableStateOf("") }
    var additionalPrice by remember(date, row.item.id) { mutableStateOf("") }
    var savingAddition by remember(date, row.item.id) { mutableStateOf(false) }
    val shortGrams = (row.requiredGrams - row.purchasedGrams).coerceAtLeast(0)

    if (addOpen) {
        val extraGrams = MarketMath.parseGrams(additionalQty)
        val extraPrice = MarketMath.parsePriceCents(additionalPrice)
        AlertDialog(
            onDismissRequest = { if (!savingAddition) addOpen = false },
            title = { Text("Additional purchase • " + row.item.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Still required: ${MarketMath.kg(shortGrams)} kg")
                    Text("This creates a separate purchase record at its own market price.",
                        style = MaterialTheme.typography.bodySmall, color = ProColors.muted)
                    OutlinedTextField(
                        value = additionalQty,
                        onValueChange = { additionalQty = it },
                        label = { Text("Additional quantity (kg)") },
                        suffix = { Text("kg") },
                        singleLine = true,
                        shape = RoundedCornerShape(11.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    OutlinedTextField(
                        value = additionalPrice,
                        onValueChange = { additionalPrice = it },
                        label = { Text("Price for this purchase") },
                        prefix = { Text("Rs.") },
                        suffix = { Text("/kg") },
                        singleLine = true,
                        shape = RoundedCornerShape(11.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                }
            },
            dismissButton = {
                TextButton(enabled = !savingAddition, onClick = { addOpen = false }) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                Button(
                    enabled = !savingAddition && extraGrams != null &&
                        extraGrams > 0 && extraGrams <= 1_000_000_000 &&
                        extraPrice != null && extraPrice <= 1_000_000_000,
                    onClick = {
                        val grams = extraGrams ?: return@Button
                        val cents = extraPrice ?: return@Button
                        savingAddition = true
                        onAdd(grams, cents) { result ->
                            savingAddition = false
                            if (result.isSuccess) {
                                addOpen = false
                                additionalQty = ""
                                additionalPrice = ""
                                message("Additional purchase saved")
                            } else {
                                message("Could not save additional purchase")
                            }
                        }
                    }
                ) { Text(if (savingAddition) "Saving…" else "Add purchase") }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF4FAFD)),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(11.dp),
                color = Color(0xFFE1F3F8)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
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
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            if (row.purchase != null) {
                // Cost of the ORIGINAL purchase, without any later priced additions.
                val originalCents = if (!qtyInvalid && !rateInvalid) {
                    MarketMath.billCents(qty!!, rate!!)
                } else {
                    MarketMath.billCents(
                        row.purchase.quantityGrams, row.purchase.priceCentsPerKg
                    )
                }
                Text(
                    "Initial cost: Rs. ${MarketMath.lkr(originalCents)}",
                    modifier = Modifier.fillMaxWidth(),
                    color = ProColors.purple,
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
            if (row.additions.isNotEmpty()) {
                HorizontalDivider(color = ProColors.pale)
                Text(
                    "Additional purchases (${row.additions.size})",
                    color = ProColors.teal,
                    fontWeight = FontWeight.ExtraBold,
                    style = MaterialTheme.typography.labelLarge
                )
                row.additions.forEachIndexed { index, lot ->
                    AdditionalPurchaseEditor(
                        lot = lot,
                        ordinal = index + 1,
                        date = date,
                        onEdit = { grams, cents -> onEdit(lot.id, grams, cents) }
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                if (shortGrams > 0L) {
                    IconButton(onClick = { addOpen = true }) {
                        Surface(shape = RoundedCornerShape(50), color = ProColors.teal) {
                            Icon(Icons.Default.Add,
                                contentDescription = "Add purchase for " + row.item.name,
                                tint = Color.White,
                                modifier = Modifier.padding(7.dp))
                        }
                    }
                }
                InvoicePhotoControl(
                    businessDate = date,
                    itemId = row.item.id,
                    itemName = row.item.name,
                    message = message
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Subtotal: Rs. ${MarketMath.lkr(preview)}",
                    modifier = Modifier.weight(3f, fill = false),
                    color = ProColors.purple,
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2
                )
            }
        }
    }
}

/**
 * A saved additional lot stays editable at all times.
 * Draft inputs use stable (day, lot ID) keys so Room Flow updates cannot move
 * the cursor or overwrite unfinished keyboard input.
 */
@Composable
private fun AdditionalPurchaseEditor(
    lot: PurchaseAdditionEntity,
    ordinal: Int,
    date: String,
    onEdit: (Long, Long) -> Unit
) {
    var quantity by remember(date, lot.id) {
        mutableStateOf(MarketMath.editKg(lot.quantityGrams))
    }
    var rate by remember(date, lot.id) {
        mutableStateOf(MarketMath.editPriceOrBlank(lot.priceCentsPerKg))
    }
    fun persistIfValid(kg: String, price: String) {
        val grams = MarketMath.parseGrams(kg)
        val cents = MarketMath.parseBlankPriceCents(price)
        if (grams != null && grams in 1..1_000_000_000 &&
            cents != null && cents in 0..1_000_000_000) {
            onEdit(grams, cents)
        }
    }
    val grams = MarketMath.parseGrams(quantity)
    val cents = MarketMath.parseBlankPriceCents(rate)
    val gramsInvalid = quantity.isNotBlank() && (grams == null || grams !in 1..1_000_000_000)
    val centsInvalid = cents == null || cents !in 0..1_000_000_000
    val amount = if (grams != null && grams in 1..1_000_000_000 &&
        cents != null && cents in 0..1_000_000_000
    ) MarketMath.billCents(grams, cents) else MarketMath.billCents(
        lot.quantityGrams, lot.priceCentsPerKg
    )
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Color(0xFFE9F4F9), RoundedCornerShape(12.dp))
            .padding(horizontal = 9.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            "Additional purchase $ordinal",
            color = ProColors.teal,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = quantity,
                onValueChange = { input ->
                    quantity = input
                    persistIfValid(input, rate)
                },
                modifier = Modifier.weight(1f),
                label = { Text("Extra Qty") },
                suffix = { Text("kg") },
                singleLine = true,
                isError = gramsInvalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(11.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold)
            )
            OutlinedTextField(
                value = rate,
                onValueChange = { input ->
                    rate = input
                    persistIfValid(quantity, input)
                },
                modifier = Modifier.weight(1f),
                label = { Text("Extra Price") },
                prefix = { Text("Rs.") },
                suffix = { Text("/kg") },
                singleLine = true,
                isError = centsInvalid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = RoundedCornerShape(11.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold)
            )
        }
        Text(
            "Additional cost: Rs. ${MarketMath.lkr(amount)}",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
            color = ProColors.purple,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogSheet(
    items: List<ItemEntity>, categories: List<String>, onClose: () -> Unit,
    add: (String, String) -> Unit,
    rename: (Long, String, (Boolean) -> Unit) -> Unit,
    addCategory: (String, (Boolean) -> Unit) -> Unit
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
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("New item name") },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
                Button(
                    onClick = { add(name, category); name = "" },
                    enabled = name.isNotBlank(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(" Add Item")
                }
            }
            CategoryStrip(
                categories = categories, selected = category,
                onSelect = { category = it }, onCreate = addCategory
            )
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
