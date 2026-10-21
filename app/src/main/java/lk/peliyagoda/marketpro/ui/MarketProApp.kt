package lk.peliyagoda.marketpro.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBackIosNew
import androidx.compose.material.icons.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.util.consolidate
import lk.peliyagoda.marketpro.util.MarketXlsx
import lk.peliyagoda.marketpro.util.MarketPdf
import lk.peliyagoda.marketpro.util.ConsolidatedRow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

object ProColors {
    val navy = Color(0xFF102A37)
    val teal = Color(0xFF086F7D)
    val pale = Color(0xFFE1F3F8)
    val page = Color(0xFFF4F8FA)
    val muted = Color(0xFF5A7280)
    val purple = Color(0xFF605B88)
    val red = Color(0xFFB1303D)
    val green = Color(0xFF227B59)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketProApp(vm: MarketViewModel) {
    val palette = androidx.compose.material3.lightColorScheme(
        primary = ProColors.teal,
        onPrimary = Color.White,
        secondary = ProColors.purple,
        background = ProColors.page,
        surface = Color.White,
        onSurface = ProColors.navy
    )
    MaterialTheme(colorScheme = palette) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val snackbar = remember { SnackbarHostState() }
        val date by vm.date.collectAsState()
        val recordedDateStrings by vm.recordedDates.collectAsState()
        val recordedDateSet = remember(recordedDateStrings) {
            recordedDateStrings.mapNotNull {
                runCatching { LocalDate.parse(it) }.getOrNull()
            }.toSet()
        }
        val items by vm.items.collectAsState()
        val categoriesDb by vm.categories.collectAsState()
        val categories = categoriesDb.map { it.name }.ifEmpty { listOf("Vegetables", "Fruits") }
        val reqs by vm.requisitions.collectAsState()
        val purchases by vm.purchases.collectAsState()
        val additions by vm.additions.collectAsState()
        val dailyCash by vm.dailyCashReceived.collectAsState()
        val cashReceivedCents = dailyCash
            ?.takeIf { it.businessDate == date.toString() }?.amountCents ?: 0L
        val rows = remember(date, items, reqs, purchases, additions) {
            // Prevent mixing stale StateFlow emissions when the day is changed rapidly.
            consolidate(
                items,
                reqs.filter { it.businessDate == date.toString() },
                purchases.filter { it.businessDate == date.toString() },
                additions.filter { it.businessDate == date.toString() }
            )
        }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        // Hoist Market's scroll state out of the tab-specific composition:
        // switching Dashboard/Kitchens/Market must NOT reset item position.
        // A new date intentionally creates a fresh scroll/filter state.
        val marketListState = rememberSaveable(
            date.toString(), saver = LazyListState.Saver
        ) { LazyListState() }
        var marketFilter by rememberSaveable(date.toString()) {
            mutableStateOf("All")
        }
        // Capture a stable ITEM ID before leaving Market. If the 60-second
        // invoice sorting moves cards while this tab is hidden, restore the
        // same item instead of blindly restoring an outdated list index.
        var marketAnchorItemId by rememberSaveable(date.toString()) {
            mutableStateOf<Long?>(null)
        }
        var marketAnchorOffset by rememberSaveable(date.toString()) {
            mutableIntStateOf(0)
        }
        var catalogOpen by remember { mutableStateOf(false) }
        var chartOpen by remember { mutableStateOf(false) }
        val priceHistory by vm.chartHistory.collectAsState()
        var menuOpen by remember { mutableStateOf(false) }
        var calendarOpen by remember { mutableStateOf(false) }
        var pendingXlsx by remember { mutableStateOf<ByteArray?>(null) }
        var pdfDialogOpen by remember { mutableStateOf(false) }
        var pendingPdfDate by remember { mutableStateOf("") }
        var pendingPdfRows by remember { mutableStateOf<List<ConsolidatedRow>>(emptyList()) }
        var pendingPdfCashCents by remember { mutableStateOf(0L) }
        var pendingBackup by remember { mutableStateOf("") }
        var restoreText by remember { mutableStateOf<String?>(null) }

        fun message(value: String) { scope.launch { snackbar.showSnackbar(value) } }

        val excelSaver = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            )
        ) { uri ->
            val data = pendingXlsx
            pendingXlsx = null
            if (uri != null && data != null) {
                val saved = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(data) }
                        ?: error("Could not open destination")
                }.isSuccess
                message(if (saved) "Unicode Excel report saved (.xlsx)" else "Excel export failed")
            }
        }
        fun exportExcel() {
            // Generate the workbook off the UI thread for large daily reports.
            val day = date.toString()
            val snapshot = rows.toList()
            scope.launch {
                runCatching {
                    withContext(Dispatchers.Default) { MarketXlsx.create(day, snapshot) }
                }.onSuccess { bytes ->
                    pendingXlsx = bytes
                    excelSaver.launch("Market-" + day + ".xlsx")
                }.onFailure { message("Could not create Excel report") }
            }
        }
        val pdfSaver = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/pdf")
        ) { uri ->
            val day = pendingPdfDate
            val snapshot = pendingPdfRows
            val cashSnapshot = pendingPdfCashCents
            pendingPdfDate = ""
            pendingPdfRows = emptyList()
            pendingPdfCashCents = 0L
            if (uri != null && day.isNotBlank() && snapshot.isNotEmpty()) {
                scope.launch {
                    runCatching {
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { output ->
                                MarketPdf.write(day, snapshot, cashSnapshot, output)
                            } ?: error("Cannot open PDF destination")
                        }
                    }.onSuccess { message("Completed purchase PDF saved") }
                        .onFailure { message("Could not save PDF: " +
                            (it.message ?: "Unknown error")) }
                }
            }
        }

        if (pdfDialogOpen) {
            CompletedPdfDialog(
                date = date,
                rows = rows,
                cashReceivedCents = cashReceivedCents,
                onClose = { pdfDialogOpen = false },
                onSavePdf = { _ ->
                    pendingPdfDate = date.toString()
                    // Save ALL rows to reconcile actual cash spent, but PDF
                    // group rows only if COMPLETED.
                    pendingPdfRows = rows.toList()
                    pendingPdfCashCents = cashReceivedCents
                    pdfDialogOpen = false
                    pdfSaver.launch("Completed-Purchases-" + date + ".pdf")
                }
            )
        }

        val backupSaver = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) { uri ->
            if (uri != null) {
                val saved = runCatching { saveText(context, uri, pendingBackup) }.isSuccess
                message(if (saved) "Backup saved. Keep the file safe." else "Backup save failed")
            }
        }
        val backupPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri != null) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Cannot read file")
                }.onSuccess { restoreText = it }
                    .onFailure { message("Could not read backup file") }
            }
        }

        if (restoreText != null) {
            AlertDialog(
                onDismissRequest = { restoreText = null },
                title = { Text("Restore backup?") },
                text = { Text("All current catalog, kitchen and purchase records will be replaced. Export a backup of current records before continuing.") },
                dismissButton = { TextButton(onClick = { restoreText = null }) { Text("Cancel") } },
                confirmButton = {
                    Button(onClick = {
                        val text = restoreText ?: return@Button
                        restoreText = null
                        vm.restoreBackup(text) { result ->
                            message(if (result.isSuccess) "Backup restored successfully" else
                                "Restore failed: ${result.exceptionOrNull()?.message ?: "Invalid file"}")
                        }
                    }) { Text("Replace & restore") }
                }
            )
        }

        if (calendarOpen) {
            RecordCalendarDialog(
                selected = date,
                recordedDays = recordedDateSet,
                onSelect = { picked ->
                    vm.setDate(picked)
                    calendarOpen = false
                },
                onDismiss = { calendarOpen = false }
            )
        }

        if (chartOpen) ItemPriceChartDialog(
            categories = categories,
            items = items,
            history = priceHistory,
            onPickItem = { id -> vm.selectChartItem(id) },
            onDismiss = {
                chartOpen = false
                vm.selectChartItem(null)
            }
        )

        if (catalogOpen) CatalogSheet(
            items = items,
            categories = categories,
            onClose = { catalogOpen = false },
            add = { name, category -> vm.addItem(name, category) {
                message(if (it) "Item added" else "Item already exists or name is invalid")
            } },
            rename = { id, newName, done -> vm.renameItem(id, newName, done) },
            addCategory = { name, done -> vm.addCategory(name, done) }
        )

        Scaffold(
            containerColor = ProVisual.page,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                // Background-only update: existing action buttons and status colors stay put.
                Column(
                    Modifier.background(
                        Brush.verticalGradient(
                            listOf(ProVisual.headerTop, ProVisual.headerBottom)
                        )
                    ).statusBarsPadding()
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("PELIYAGODA", style = MaterialTheme.typography.labelSmall,
                                color = ProColors.teal, fontWeight = FontWeight.Bold)
                            Text("Market Pro", style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold, color = ProVisual.itemInk)
                        }
                        IconButton(onClick = { catalogOpen = true }) {
                            Icon(Icons.Filled.Inventory2, contentDescription = "Item catalog", tint = ProColors.teal)
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Items Price Chart") }, onClick = {
                                    menuOpen = false
                                    vm.selectChartItem(null)
                                    chartOpen = true
                                })
                                DropdownMenuItem(text = { Text("Export Excel (.xlsx)") }, onClick = {
                                    menuOpen = false
                                    exportExcel()
                                })
                                DropdownMenuItem(text = { Text("Backup all records (JSON)") }, onClick = {
                                    menuOpen = false
                                    vm.prepareBackup { result ->
                                        result.onSuccess {
                                            pendingBackup = it
                                            backupSaver.launch("MarketPro-Backup-${LocalDate.now()}.json")
                                        }.onFailure { message("Could not prepare backup") }
                                    }
                                })
                                DropdownMenuItem(text = { Text("Restore backup (JSON)") }, onClick = {
                                    menuOpen = false
                                    backupPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                                })
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { vm.shiftDate(-1) }) {
                            Icon(Icons.Filled.ArrowBackIosNew, contentDescription = "Previous day")
                        }
                        Row(
                            Modifier.background(Color.White, RoundedCornerShape(50))
                                .clickable { calendarOpen = true }.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.CalendarMonth, contentDescription = null, tint = ProColors.teal)
                            Text(date.format(DateTimeFormatter.ofPattern("EEE, dd MMM yyyy", Locale.ENGLISH)),
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        }
                        IconButton(onClick = { vm.shiftDate(1) }) {
                            Icon(Icons.Filled.ArrowForwardIos, contentDescription = "Next day")
                        }
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = Color.White) {
                    val labels = listOf("Dashboard", "Kitchens", "Market")
                    val icons = listOf(Icons.Filled.Dashboard, Icons.Filled.Restaurant, Icons.Filled.ShoppingCart)
                    labels.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = tab == index,
                            onClick = {
                                if (tab == 2 && index != 2) {
                                    val first = marketListState.firstVisibleItemIndex
                                    if (first >= 2) {
                                        val anchor = marketListState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.index == first && it.key is Long }
                                        marketAnchorItemId = anchor?.key as? Long
                                        marketAnchorOffset =
                                            marketListState.firstVisibleItemScrollOffset
                                    } else {
                                        marketAnchorItemId = null
                                        marketAnchorOffset = 0
                                    }
                                }
                                tab = index
                            },
                            icon = { Icon(icons[index], contentDescription = null) },
                            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            alwaysShowLabel = true
                        )
                    }
                }
            }
        ) { innerPadding ->
            Box(Modifier.fillMaxSize().padding(innerPadding)) {
                when (tab) {
                    0 -> DashboardScreen(date, rows, categories, onExport = { pdfDialogOpen = true })
                    1 -> KitchensScreen(items, categories, reqs, vm, ::message)
                    else -> MarketScreen(
                        rows = rows,
                        categories = categories,
                        vm = vm,
                        cashReceivedCents = cashReceivedCents,
                        onSaveCash = { cents ->
                            vm.saveCashReceived(cents) { result ->
                                if (result.isFailure) message("Could not save Cash Received")
                            }
                        },
                        message = ::message,
                        listState = marketListState,
                        filter = marketFilter,
                        restoreItemId = marketAnchorItemId,
                        restoreItemOffset = marketAnchorOffset,
                        onFilterChange = { category ->
                            if (marketFilter != category) {
                                marketFilter = category
                                marketAnchorItemId = null
                                marketAnchorOffset = 0
                                scope.launch { marketListState.scrollToItem(1) }
                            }
                        }
                    )
                }
            }
        }
    }
}

private fun saveText(context: Context, uri: android.net.Uri, text: String) {
    context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
        it.write(text)
    } ?: error("Unable to open destination")
}
