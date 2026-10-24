package lk.peliyagoda.marketpro.ui

import android.content.Context
import lk.peliyagoda.marketpro.cloud.CloudSyncScheduler
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import lk.peliyagoda.marketpro.data.OtherExpenseField
import lk.peliyagoda.marketpro.data.ItemEntity
import lk.peliyagoda.marketpro.data.MarketRepository
import lk.peliyagoda.marketpro.data.PurchaseEntity
import lk.peliyagoda.marketpro.data.PurchaseHistoryLot
import lk.peliyagoda.marketpro.data.RequisitionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class MarketViewModel(private val repository: MarketRepository, private val appContext: Context?) : ViewModel() {
    // Serialize rapid edits and deletions so the newest quantity always wins.
    private val requirementWriteMutex = Mutex()
    private val purchaseWriteMutex = Mutex()
    private val cashWriteMutex = Mutex()
    val chartItemId = MutableStateFlow<Long?>(null)
    val chartHistory = chartItemId.flatMapLatest { id ->
        if (id == null) kotlinx.coroutines.flow.flowOf(emptyList<PurchaseHistoryLot>())
        else repository.purchaseHistory(id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun selectChartItem(id: Long?) { chartItemId.value = id }

    val date = MutableStateFlow(LocalDate.now())
    val items = repository.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val catalogItems = repository.catalogItems.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val categories = repository.categories.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val recordedDates = repository.recordedDates.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )
    val requisitions = date.flatMapLatest { repository.requirements(it.toString()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val purchases = date.flatMapLatest { repository.purchases(it.toString()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val additions = date.flatMapLatest { repository.additions(it.toString()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val dailyOtherExpenses = date.flatMapLatest { repository.otherExpenses(it.toString()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val dailyCashReceived = date.flatMapLatest { repository.cashReceived(it.toString()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val cloudPendingCount = repository.cloudPendingCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private suspend fun enqueueIfEnabled() {
        val ctx = appContext ?: return
        if (repository.cloudSyncEnabled()) {
            runCatching {
                CloudSyncScheduler.schedulePeriodic(ctx)
                CloudSyncScheduler.kick(ctx)
            }
        }
    }

    init {
        viewModelScope.launch(Dispatchers.IO) {
            repository.seedCatalogOnce()
            enqueueIfEnabled()
        }
    }

    fun setDate(day: LocalDate) { date.value = day }
    fun shiftDate(days: Long) { date.value = date.value.plusDays(days) }

    private fun doWork(work: suspend () -> Unit, result: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val outcome = runCatching { withContext(Dispatchers.IO) { work(); enqueueIfEnabled() } }
            result(outcome)
        }
    }

    fun addCategory(name: String, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching {
                withContext(Dispatchers.IO) { repository.addCategory(name).also { if (it) enqueueIfEnabled() } }
            }.getOrDefault(false)
            done(ok)
        }
    }

    fun addItem(name: String, category: String, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = runCatching { withContext(Dispatchers.IO) {
                repository.addItem(name, category).also { if (it) enqueueIfEnabled() }
            } }.getOrDefault(false)
            done(success)
        }
    }

    fun renameItem(id: Long, name: String, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = runCatching {
                withContext(Dispatchers.IO) { repository.renameItem(id, name).also { if (it) enqueueIfEnabled() } }
            }.getOrDefault(false)
            done(success)
        }
    }

    fun archiveItem(id: Long, approvalCode: String, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = runCatching {
                withContext(Dispatchers.IO) {
                    repository.archiveItem(id, approvalCode).also {
                        if (it) enqueueIfEnabled()
                    }
                }
            }.getOrDefault(false)
            done(success)
        }
    }

    fun addRequirements(kitchen: String, itemIds: Set<Long>, done: (Result<Unit>) -> Unit = {}) {
        val dateSnapshot = date.value.toString()
        doWork({ repository.addRequirements(dateSnapshot, kitchen, itemIds) }, done)
    }

    fun saveRequirement(kitchen: String, itemId: Long, grams: Long, done: (Result<Unit>) -> Unit) {
        val dateSnapshot = date.value.toString()
        doWork({ requirementWriteMutex.withLock {
            repository.saveRequirement(dateSnapshot, kitchen, itemId, grams)
        } }, done)
    }

    fun removeRequirement(kitchen: String, itemId: Long, done: (Result<Unit>) -> Unit) {
        val dateSnapshot = date.value.toString()
        doWork({ requirementWriteMutex.withLock {
            repository.deleteRequirement(dateSnapshot, kitchen, itemId)
        } }, done)
    }

    fun savePurchase(itemId: Long, grams: Long, cents: Long, done: (Result<Unit>) -> Unit) {
        val dateSnapshot = date.value.toString()
        doWork({ purchaseWriteMutex.withLock {
            repository.savePurchase(dateSnapshot, itemId, grams, cents)
        } }, done)
    }

    fun addPurchaseAddition(itemId: Long, grams: Long, priceCentsPerKg: Long,
                            done: (Result<Unit>) -> Unit) {
        val day = date.value.toString()
        doWork({ purchaseWriteMutex.withLock {
            repository.addPurchaseAddition(day, itemId, grams, priceCentsPerKg)
        } }, done)
    }

    fun editPurchaseAddition(itemId: Long, lotId: Long, grams: Long, priceCentsPerKg: Long,
                             done: (Result<Unit>) -> Unit = {}) {
        val day = date.value.toString()
        doWork({
            purchaseWriteMutex.withLock {
                repository.editPurchaseAddition(day, itemId, lotId, grams, priceCentsPerKg)
            }
        }, done)
    }

    fun saveCashReceived(amountCents: Long, done: (Result<Unit>) -> Unit = {}) {
        val day = date.value.toString()
        doWork({
            cashWriteMutex.withLock { repository.saveCashReceived(day, amountCents) }
        }, done)
    }

    fun saveOtherExpenseAmount(
        field: OtherExpenseField,
        cents: Long,
        done: (Result<Unit>) -> Unit = {}
    ) {
        val day = date.value.toString()
        doWork({
            cashWriteMutex.withLock {
                repository.updateOtherExpenseAmount(day, field, cents)
            }
        }, done)
    }

    fun saveOtherExpenseRemarks(
        remarks: String,
        done: (Result<Unit>) -> Unit = {}
    ) {
        val day = date.value.toString()
        doWork({
            cashWriteMutex.withLock {
                repository.updateOtherExpenseRemarks(day, remarks)
            }
        }, done)
    }

    fun prepareBackup(done: (Result<String>) -> Unit) {
        viewModelScope.launch {
            done(runCatching { withContext(Dispatchers.IO) { repository.exportBackup() } })
        }
    }

    fun restoreBackup(json: String, done: (Result<Unit>) -> Unit) =
        doWork({ repository.restoreBackup(json) }, done)

    companion object {
        fun factory(repository: MarketRepository, context: Context? = null): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MarketViewModel(repository, context) as T
            }
    }
}
