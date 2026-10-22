package lk.peliyagoda.marketpro.ui

import android.app.ActivityManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
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
import androidx.compose.material.icons.filled.HelpOutline
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
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import lk.peliyagoda.marketpro.util.consolidate
import lk.peliyagoda.marketpro.util.MarketXlsx
import lk.peliyagoda.marketpro.util.MarketPdf
import lk.peliyagoda.marketpro.util.ConsolidatedRow
import lk.peliyagoda.marketpro.util.DevicePerformancePolicy
import lk.peliyagoda.marketpro.data.CloudDeviceIdentity
import lk.peliyagoda.marketpro.security.RestorePinGuard
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
        // With adjustResize, the IME occupies the bottom of the screen.
        // Scaffold's bottom NavigationBar still reserves its height even when
        // physically covered by the keyboard. Hide it from the layout while
        // typing so the empty ~80dp strip above the IME disappears.
        val density = LocalDensity.current
        val keyboardVisible = WindowInsets.ime.getBottom(density) >
            with(density) { 90.dp.roundToPx() }
        // 4 GB devices often struggle to render two complex lists at once
        // during a slide transition. Keep lightweight fades for those devices.
        val constrainedDevice = remember(context) {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE)
                as? ActivityManager
            if (activityManager == null) false else {
                val memory = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memory)
                DevicePerformancePolicy.preferLightweightAnimations(memory.totalMem, activityManager.isLowRamDevice)
            }
        }
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
        val catalogItems by vm.catalogItems.collectAsState()
        val categoriesDb by vm.categories.collectAsState()
        val categories = categoriesDb.map { it.name }.ifEmpty { listOf("Vegetables", "Fruits") }
        val reqs by vm.requisitions.collectAsState()
        val purchases by vm.purchases.collectAsState()
        val additions by vm.additions.collectAsState()
        val dailyCash by vm.dailyCashReceived.collectAsState()
        val cloudPendingCount by vm.cloudPendingCount.collectAsState()
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
        // The old when(tab) disposed Dashboard/Kitchen state on every switch.
        // Retain saveable per-tab state (including each list's scroll position).
        val tabStateHolder = rememberSaveableStateHolder()
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
        var cloudStatusOpen by remember { mutableStateOf(false) }
        var ownerLoginOpen by remember { mutableStateOf(false) }
        var helpOpen by remember { mutableStateOf(false) }
        var restorePinOpen by remember { mutableStateOf(false) }
        var restoreAuthorized by remember { mutableStateOf(false) }
        val restorePinGuard = remember(context) { RestorePinGuard(context.applicationContext) }
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
                message(if (saved) "Unicode Excel Report Saved (.xlsx)" else "Excel Export Failed")
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
                }.onFailure { message("Could Not Create Excel Report") }
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
                    }.onSuccess { message("Completed Purchase PDF Saved") }
                        .onFailure { message("Could Not Save PDF: " +
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
                message(if (saved) "Backup Saved. Keep The File Safe." else "Backup Save Failed")
            }
        }
        val backupPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            if (uri == null) {
                restoreAuthorized = false
                restoreText = null
            } else if (!restoreAuthorized) {
                message("Restore Permission Expired. Enter The PIN Again.")
            } else {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: error("Cannot read file")
                }.onSuccess { restoreText = it }
                    .onFailure {
                        restoreAuthorized = false
                        message("Could Not Read Backup File")
                    }
            }
        }

        if (restorePinOpen) {
            RestorePinDialog(
                guard = restorePinGuard,
                onDismiss = { restorePinOpen = false },
                onAuthorized = {
                    restorePinOpen = false
                    restoreAuthorized = true
                    backupPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
            )
        }

        if (restoreText != null && restoreAuthorized) {
            AlertDialog(
                onDismissRequest = {
                    restoreText = null
                    restoreAuthorized = false
                },
                title = { Text("Restore Backup?") },
                text = {
                    Text(
                        "⚠️ මෙය දැනට ඇති Catalog, Kitchen සහ Purchase Records " +
                            "Backup File එකේ දත්ත මඟින් Replace කරනු ඇත. " +
                            "Restore කිරීමට පෙර අලුත් JSON Backup එකක් Save කරගන්න. " +
                            "Invoice ඡායාරූප JSON එකට ඇතුළත් නොවේ."
                    )
                },
                dismissButton = {
                    TextButton(onClick = {
                        restoreText = null
                        restoreAuthorized = false
                    }) { Text("Cancel") }
                },
                confirmButton = {
                    Button(onClick = {
                        if (!restoreAuthorized) return@Button
                        val json = restoreText ?: return@Button
                        // One-time permission is consumed before any database mutation.
                        restoreAuthorized = false
                        restoreText = null
                        // Repository performs full schema checks before an atomic replace.
                        // Cloud-enrolled restores remain blocked at repository level.
                        vm.restoreBackup(json) { result ->
                            message(if (result.isSuccess) "Backup Restored Successfully" else
                                "Restore Failed: ${result.exceptionOrNull()?.message ?: "Invalid File"}")
                        }
                    }) { Text("Replace & Restore") }
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

        if (helpOpen) MarketHelpDialog(onDismiss = { helpOpen = false })

        if (ownerLoginOpen) CloudOwnerLoginDialog(onDismiss = { ownerLoginOpen = false })

        if (cloudStatusOpen) CloudSyncDialog(onDismiss = { cloudStatusOpen = false })

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
                message(if (it) "Item Added" else "Item Already Exists Or Name Is Invalid")
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
                            Icon(Icons.Filled.Inventory2, contentDescription = "Item Catalog", tint = ProColors.teal)
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = "More Options")
                            }
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                                shape = RoundedCornerShape(20.dp),
                                containerColor = Color(0xFFF9F7FD)
                            ) {
                                DropdownMenuItem(text = { Text("Items Price Chart", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge) }, onClick = {
                                    menuOpen = false
                                    vm.selectChartItem(null)
                                    chartOpen = true
                                })
                                DropdownMenuItem(text = { Text("Export Excel (.xlsx)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge) }, onClick = {
                                    menuOpen = false
                                    exportExcel()
                                })
                                DropdownMenuItem(text = { Text("Backup All Records (JSON)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge) }, onClick = {
                                    menuOpen = false
                                    vm.prepareBackup { result ->
                                        result.onSuccess {
                                            pendingBackup = it
                                            backupSaver.launch("MarketPro-Backup-${LocalDate.now()}.json")
                                        }.onFailure { message("Could Not Prepare Backup") }
                                    }
                                })
                                DropdownMenuItem(text = { Text("Restore Backup (JSON)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge) }, onClick = {
                                    menuOpen = false
                                    restoreAuthorized = false
                                    restoreText = null
                                    restorePinOpen = true
                                })
                                DropdownMenuItem(text = { Text("Cloud Owner Login / Sign Up", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge) }, onClick = {
                                    menuOpen = false
                                    ownerLoginOpen = true
                                })
                                DropdownMenuItem(text = { Text("Cloud Sync & Backup (DEV)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge) }, onClick = {
                                    menuOpen = false
                                    cloudStatusOpen = true
                                })
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            "Help & User Guide",
                                            fontWeight = FontWeight.Bold,
                                            style = MaterialTheme.typography.bodyLarge
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Filled.HelpOutline, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        helpOpen = true
                                    }
                                )

                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { vm.shiftDate(-1) }) {
                            Icon(Icons.Filled.ArrowBackIosNew, contentDescription = "Previous Day")
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
                            Icon(Icons.Filled.ArrowForwardIos, contentDescription = "Next Day")
                        }
                    }
                }
            },
            bottomBar = {
                if (!keyboardVisible) NavigationBar(containerColor = Color.White) {
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
            // Animate only content; date header and bottom navigation stay fixed.
            // A short fade plus 6% horizontal travel avoids a hard screen cut,
            // while old/new screens never share a saveable-state key.
            AnimatedContent(
                targetState = tab,
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                transitionSpec = {
                    if (constrainedDevice) {
                        // No position translation on low-memory/entry-level devices:
                        // short alpha-only transitions avoid expensive relayout.
                        fadeIn(animationSpec = tween(durationMillis = 90)) togetherWith
                            fadeOut(animationSpec = tween(durationMillis = 60))
                    } else {
                        (fadeIn(animationSpec = tween(durationMillis = 170)) +
                            slideInHorizontally(
                                animationSpec = tween(durationMillis = 170),
                                initialOffsetX = { width ->
                                    val travel = (width * 0.06f).toInt()
                                    if (targetState > initialState) travel else -travel
                                }
                            )) togetherWith fadeOut(animationSpec = tween(durationMillis = 100))
                    }
                },
                label = "Market Pro tab navigation"
            ) { activeTab ->
                tabStateHolder.SaveableStateProvider("market-pro-tab-$activeTab") {
                    when (activeTab) {
                        0 -> DashboardScreen(date, rows, categories, onExport = { pdfDialogOpen = true })
                        1 -> KitchensScreen(items, catalogItems, categories, reqs, vm, ::message)
                        else -> MarketScreen(
                            rows = rows,
                            categories = categories,
                            vm = vm,
                            cashReceivedCents = cashReceivedCents,
                            onSaveCash = { cents ->
                                vm.saveCashReceived(cents) { result ->
                                    if (result.isFailure) message("Could Not Save Cash Received")
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
}

private fun saveText(context: Context, uri: android.net.Uri, text: String) {
    context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use {
        it.write(text)
    } ?: error("Unable to open destination")
}
