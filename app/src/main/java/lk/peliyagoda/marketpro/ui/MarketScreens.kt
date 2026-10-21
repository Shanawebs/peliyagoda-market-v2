package lk.peliyagoda.marketpro.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
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
import lk.peliyagoda.marketpro.util.groupKitchenRequirements
import lk.peliyagoda.marketpro.util.CashAmountFormatter
import lk.peliyagoda.marketpro.util.PurchaseEntryRules
import lk.peliyagoda.marketpro.util.sortedMarketPurchases
import lk.peliyagoda.marketpro.util.nextMarketSortRefreshMs
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ProVisual.card),
        border = BorderStroke(1.dp, ProVisual.cardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier, accent: Color = ProColors.teal) {
    val fill = when (accent) {
        ProColors.purple -> ProVisual.metricPurple
        ProColors.navy -> ProVisual.metricNeutral
        else -> ProVisual.metricTeal
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = fill,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashboardScreen(date: LocalDate, rows: List<ConsolidatedRow>, categories: List<String>, onExport: () -> Unit) {
    var filter by rememberSaveable(date.toString()) { mutableStateOf("All") }
    // Dashboard uses a separate list position for each chosen business date.
    val listState = remember(date) { LazyListState() }
    val scope = rememberCoroutineScope()
    fun chooseCategory(category: String) {
        if (filter != category) {
            filter = category
            scope.launch { listState.scrollToItem(1) }
        }
    }
    val shown = rows.filter { filter == "All" || it.item.category == filter }
    val totalReq = rows.sumOf { it.requiredGrams }
    val totalPurch = rows.sumOf { it.purchasedGrams }
    val totalCents = rows.sumOf { it.lineCents }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "dashboard-summary") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Consolidated requirements", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold, color = ProVisual.itemInk)
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
        // Summary scrolls away; this filter strip sticks directly below the
        // global date header as cards continue scrolling underneath it.
        stickyHeader(key = "dashboard-category-header") {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = ProVisual.page
            ) {
                LazyRow(
                    modifier = Modifier.padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item(key = "All") {
                        CategoryChip("All", active = filter == "All") { chooseCategory("All") }
                    }
                    lazyItems(categories, key = { it }) { c ->
                        CategoryChip(c, active = filter == c) { chooseCategory(c) }
                    }
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
                                    color = ProVisual.itemInk,
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
                                Text("${MarketMath.kg(row.requiredGrams)} kg",
                                    color = ProVisual.amountInk,
                                    fontWeight = FontWeight.SemiBold)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Purchased", color = ProColors.muted,
                                    style = MaterialTheme.typography.labelSmall)
                                Text("${MarketMath.kg(row.purchasedGrams)} kg",
                                    color = ProVisual.amountInk,
                                    fontWeight = FontWeight.SemiBold)
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
    // Group each kitchen's own requirements by live master-item categories.
    // This is display-only; autosave and Room IDs stay unchanged.
    val grouped = groupKitchenRequirements(items, current, kitchen)
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
                    Surface(shape = RoundedCornerShape(18.dp), color = ProVisual.kitchenBanner) {
                        Column(Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Text(kitchen, style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold, color = ProVisual.itemInk)
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
                grouped.forEach { group ->
                    item(key = "category:${kitchen}:${group.category}") {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = ProColors.pale,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(
                                    horizontal = 16.dp, vertical = 12.dp
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        group.category,
                                        color = ProColors.navy,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold
                                    )
                                    Text(
                                        "${group.itemCount} item(s)",
                                        color = ProColors.muted,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                                Text(
                                    "${MarketMath.kg(group.subtotalGrams)} kg",
                                    color = ProColors.teal,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                    }
                    lazyItems(
                        group.rows,
                        key = { row ->
                            val entry = row.requirement
                            "${entry.businessDate}:${entry.kitchen}:${entry.itemId}"
                        }
                    ) { row ->
                        val item = row.item
                        val entry = row.requirement
                        RequirementCard(
                            item = item,
                            entry = entry,
                            onSave = { grams ->
                                vm.saveRequirement(kitchen, item.id, grams) { result ->
                                    if (result.isFailure) message("Could not auto-save quantity")
                                }
                            },
                            onDelete = {
                                vm.removeRequirement(kitchen, item.id) { result ->
                                    message(
                                        if (result.isSuccess) "Item removed"
                                        else "Could not remove item"
                                    )
                                }
                            }
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
                    color = ProVisual.itemInk,
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
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.SemiBold,
                    color = ProVisual.amountInk
                ),
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
    // Open at full height immediately rather than the default half-expanded state.
    val pickerSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = pickerSheetState,
        modifier = Modifier.imePadding()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).fillMaxHeight(0.97f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarketScreen(
    rows: List<ConsolidatedRow>,
    categories: List<String>,
    vm: MarketViewModel,
    cashReceivedCents: Long,
    onSaveCash: (Long) -> Unit,
    message: (String) -> Unit,
    listState: LazyListState,
    filter: String,
    restoreItemId: Long?,
    restoreItemOffset: Int,
    onFilterChange: (String) -> Unit
) {
    val date by vm.date.collectAsState()
    val context = LocalContext.current
    val businessDate = date.toString()
    var cashDialogOpen by remember(businessDate) { mutableStateOf(false) }
    // Day-specific popup, editable after close/reopen; blank is saved as zero.
    if (cashDialogOpen) {
        var cashDraft by remember(businessDate) {
            mutableStateOf(MarketMath.editPriceOrBlank(cashReceivedCents))
        }
        val parsed = MarketMath.parseBlankPriceCents(cashDraft)
        val valid = parsed != null && parsed in 0L..9_999_999_999L
        AlertDialog(
            onDismissRequest = { cashDialogOpen = false },
            title = { Text("Cash Received", fontWeight = FontWeight.ExtraBold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Cash amount received for " + businessDate,
                        color = ProColors.muted)
                    OutlinedTextField(
                        value = cashDraft,
                        onValueChange = { displayed ->
                            // A pasted "1,000.50" is normalized to a comma-free
                            // editing value; the visual transform adds separators
                            // without altering what is saved to Room.
                            val value = CashAmountFormatter.cleanInput(displayed)
                            if (value.length <= 14) {
                                cashDraft = value
                                MarketMath.parseBlankPriceCents(value)
                                    ?.takeIf { it in 0L..9_999_999_999L }
                                    ?.let(onSaveCash)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Cash Received (Rs.)") },
                        prefix = { Text("Rs. ") },
                        visualTransformation = CashThousandsVisualTransformation,
                        isError = !valid,
                        supportingText = {
                            Text(if (valid) "Saves automatically. Clear to reset to zero."
                            else "Enter a valid amount with up to 2 decimals.")
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                    )
                    Text("Total spent: Rs. " + MarketMath.lkr(rows.sumOf { it.lineCents }),
                        color = ProColors.muted)
                    Text(
                        "Cash balance: Rs. " +
                            MarketMath.lkr((parsed?.takeIf { valid } ?: cashReceivedCents) -
                                rows.sumOf { it.lineCents }),
                        color = if ((parsed?.takeIf { valid } ?: cashReceivedCents) >=
                            rows.sumOf { it.lineCents }) ProColors.green else ProColors.red,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { cashDialogOpen = false }) {
                    Text("Close")
                }
            }
        )
    }
    var photoVersion by remember(businessDate) { mutableIntStateOf(0) }
    var nowMs by remember(businessDate) {
        mutableLongStateOf(System.currentTimeMillis())
    }
    // Photos are private on this device. Read only the selected business date,
    // and recompute after saving a photo or after purchase records change.
    val latestPhotoByItem = remember(businessDate, rows, photoVersion) {
        rows.mapNotNull { row ->
            InvoicePhotoStore.photos(context, businessDate, row.item.id)
                .maxOfOrNull { it.lastModified() }
                ?.let { row.item.id to it }
        }.toMap()
    }
    LaunchedEffect(businessDate, rows, latestPhotoByItem) {
        // No background service/polling: schedule only the next 60-second
        // threshold, then update the order while the screen is active.
        while (true) {
            val current = System.currentTimeMillis()
            nowMs = current
            val due = nextMarketSortRefreshMs(rows, latestPhotoByItem, current)
                ?: break
            delay((due - current).coerceAtLeast(1L))
        }
    }
    // Only reorder visible cards, not totals, source records, or other tabs.
    // Unfinished/awaiting-invoice rows stay on top; fully verified rows move
    // to the bottom after the one-minute delay. Both sections stay A–Z.
    val shown = sortedMarketPurchases(
        rows.filter { filter == "All" || it.item.category == filter },
        latestPhotoByItem,
        nowMs
    )
    // Runs only on the Market tab's next entry, not during continuous scrolling.
    // Rows have stable item-ID keys, so live reorder also preserves visible cards.
    LaunchedEffect(listState) {
        if (restoreItemId != null) {
            val position = shown.indexOfFirst { it.item.id == restoreItemId }
            if (position >= 0) {
                // +2 accounts for the summary and the pinned filter header.
                listState.scrollToItem(position + 2, restoreItemOffset.coerceAtLeast(0))
            }
        }
    }
    val totalRequired = rows.sumOf { it.requiredGrams }
    val totalPurchased = rows.sumOf { it.purchasedGrams }
    val totalBill = rows.sumOf { it.lineCents }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "market-summary") {
            Surface(color = ProVisual.marketBanner, shape = RoundedCornerShape(19.dp)) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Market purchase & billing", style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold, color = ProVisual.itemInk)
                    Text("Enter quantities and prices • Saves automatically",
                        color = ProColors.muted, style = MaterialTheme.typography.bodySmall)
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Rs. ${MarketMath.lkr(totalBill)}",
                            modifier = Modifier.weight(1f),
                            color = ProVisual.purpleAmount,
                            style = MaterialTheme.typography.headlineSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Surface(
                            onClick = { cashDialogOpen = true },
                            color = Color(0xFF8B1E23),
                            shape = RoundedCornerShape(22.dp)
                        ) {
                            Text(
                                "Cash Received",
                                modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp),
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.ExtraBold,
                                maxLines = 1
                            )
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Required", color = ProColors.muted,
                                style = MaterialTheme.typography.labelMedium)
                            Text("${MarketMath.kg(totalRequired)} kg",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = ProVisual.amountInk,
                                fontWeight = FontWeight.ExtraBold,
                                style = MaterialTheme.typography.titleSmall)
                        }
                        Column(Modifier.weight(1f),
                            horizontalAlignment = Alignment.End) {
                            Text("Bought", color = ProColors.muted,
                                style = MaterialTheme.typography.labelMedium)
                            Text("${MarketMath.kg(totalPurchased)} kg",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = ProVisual.amountInk,
                                fontWeight = FontWeight.ExtraBold,
                                style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        }
        stickyHeader(key = "market-category-header") {
            Surface(modifier = Modifier.fillMaxWidth(), color = ProVisual.page) {
                LazyRow(
                    modifier = Modifier.padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 2.dp)
                ) {
                    item(key = "All") {
                        CategoryChip("All", active = filter == "All") {
                            onFilterChange("All")
                        }
                    }
                    lazyItems(categories, key = { it }) { category ->
                        CategoryChip(category, active = filter == category) {
                            onFilterChange(category)
                        }
                    }
                }
            }
        }
        if (shown.isEmpty()) item {
            EmptyState(
                if (rows.isEmpty()) "No purchasing items" else "No items in $filter",
                if (rows.isEmpty()) "Enter requirements in Kitchens first."
                else "Select another category to view purchasing items."
            )
        }
        lazyItems(shown, key = { it.item.id }) { row ->
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
                onPhotoSaved = { photoVersion++ },
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
    onPhotoSaved: () -> Unit,
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
    var showInitialPurchaseHelp by remember(date, row.item.id) { mutableStateOf(false) }
    var additionalQty by remember(date, row.item.id) { mutableStateOf("") }
    var additionalPrice by remember(date, row.item.id) { mutableStateOf("") }
    var savingAddition by remember(date, row.item.id) { mutableStateOf(false) }
    val shortGrams = (row.requiredGrams - row.purchasedGrams).coerceAtLeast(0)
    // Confirm initial purchase AND market price are entered before allowing '+'.
    val mayAdd = PurchaseEntryRules.canAddAnotherPurchase(
        MarketMath.parseBlankGrams(purchased),
        MarketMath.parseBlankPriceCents(price),
        row.requiredGrams, row.purchasedGrams
    )
    if (showInitialPurchaseHelp) {
        AlertDialog(
            onDismissRequest = { showInitialPurchaseHelp = false },
            title = { Text("Enter the initial purchase first") },
            text = {
                Text(
                    "Please enter the original Purchased quantity and Market Price first. " +
                        "Use + Add Purchase only if there is still a shortage.\n\n" +
                        "මුලින් Purchased Qty සහ Market Price ඇතුළත් කරන්න. " +
                        "හිඟ ප්‍රමාණයක් තිබේ නම් පමණක් + භාවිත කරන්න."
                )
            },
            confirmButton = {
                TextButton(onClick = { showInitialPurchaseHelp = false }) {
                    Text("OK")
                }
            }
        )
    }

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
                        extraPrice != null && extraPrice > 0 && extraPrice <= 1_000_000_000,
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
        colors = CardDefaults.cardColors(containerColor = ProVisual.purchaseCard),
        border = BorderStroke(1.dp, ProVisual.purchaseCardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(11.dp),
                color = ProVisual.purchaseTitlePanel
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(row.item.name, style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold, color = ProVisual.itemInk,
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
                        fontWeight = FontWeight.SemiBold,
                        color = ProVisual.amountInk),
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
                        fontWeight = FontWeight.SemiBold,
                        color = ProVisual.amountInk),
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
                    color = ProVisual.purpleAmount,
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
                    IconButton(onClick = {
                        if (mayAdd) addOpen = true else showInitialPurchaseHelp = true
                    }) {
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
                    onPhotoSaved = onPhotoSaved,
                    message = message
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Subtotal: Rs. ${MarketMath.lkr(preview)}",
                    modifier = Modifier.weight(3f, fill = false),
                    color = ProVisual.purpleAmount,
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
        mutableStateOf(MarketMath.editKgOrBlank(lot.quantityGrams))
    }
    var rate by remember(date, lot.id) {
        mutableStateOf(MarketMath.editPriceOrBlank(lot.priceCentsPerKg))
    }
    fun persistIfValid(kg: String, price: String) {
        // Blank quantity/price means zero, saved immediately with the lot ID retained.
        val grams = MarketMath.parseBlankGrams(kg)
        val cents = MarketMath.parseBlankPriceCents(price)
        if (grams != null && grams in 0..1_000_000_000 &&
            cents != null && cents in 0..1_000_000_000) {
            onEdit(grams, cents)
        }
    }
    val grams = MarketMath.parseBlankGrams(quantity)
    val cents = MarketMath.parseBlankPriceCents(rate)
    val gramsInvalid = grams == null || grams !in 0..1_000_000_000
    val centsInvalid = cents == null || cents !in 0..1_000_000_000
    val amount = if (!gramsInvalid && !centsInvalid) {
        MarketMath.billCents(grams!!, cents!!)
    } else MarketMath.billCents(lot.quantityGrams, lot.priceCentsPerKg)
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(ProVisual.extraPurchasePanel, RoundedCornerShape(12.dp))
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
                    fontWeight = FontWeight.Bold,
                    color = ProVisual.amountInk)
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
                    fontWeight = FontWeight.Bold,
                    color = ProVisual.amountInk)
            )
        }
        Text(
            "Additional cost: Rs. ${MarketMath.lkr(amount)}",
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.End,
            color = ProVisual.purpleAmount,
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
    // Full height while focused, and inset above the IME so the results list
    // remains visible and scrollable instead of collapsing behind the keyboard.
    val catalogSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = catalogSheetState,
        modifier = Modifier.imePadding()
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.97f).padding(horizontal = 18.dp),
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
            val filteredItems = items.filter { item ->
                item.category == category && item.name.contains(search, ignoreCase = true)
            }
            Text("${filteredItems.size} ${category} item(s)", color = ProColors.muted,
                style = MaterialTheme.typography.labelMedium)
            LazyColumn(modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 24.dp)) {
                if (filteredItems.isEmpty()) {
                    item {
                        Text(
                            "No items in this category. Add an item above.",
                            color = ProColors.muted,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    }
                }
                lazyItems(filteredItems, key = { it.id }) { item ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name, style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold, color = ProVisual.itemInk)
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
