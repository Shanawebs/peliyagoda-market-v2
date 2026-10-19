package lk.peliyagoda.marketpro.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import lk.peliyagoda.marketpro.data.ItemEntity
import lk.peliyagoda.marketpro.data.MarketRepository
import lk.peliyagoda.marketpro.data.PurchaseEntity
import lk.peliyagoda.marketpro.data.RequisitionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class MarketViewModel(private val repository: MarketRepository) : ViewModel() {
    val date = MutableStateFlow(LocalDate.now())
    val items = repository.items.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val requisitions = date.flatMapLatest { repository.requirements(it.toString()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val purchases = date.flatMapLatest { repository.purchases(it.toString()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) { repository.seedCatalogOnce() }
    }

    fun setDate(day: LocalDate) { date.value = day }
    fun shiftDate(days: Long) { date.value = date.value.plusDays(days) }

    private fun doWork(work: suspend () -> Unit, result: (Result<Unit>) -> Unit = {}) {
        viewModelScope.launch {
            val outcome = runCatching { withContext(Dispatchers.IO) { work() } }
            result(outcome)
        }
    }

    fun addItem(name: String, category: String, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val success = runCatching { withContext(Dispatchers.IO) {
                repository.addItem(name, category)
            } }.getOrDefault(false)
            done(success)
        }
    }

    fun addRequirements(kitchen: String, itemIds: Set<Long>, done: (Result<Unit>) -> Unit = {}) {
        val dateSnapshot = date.value.toString()
        doWork({ repository.addRequirements(dateSnapshot, kitchen, itemIds) }, done)
    }

    fun saveRequirement(kitchen: String, itemId: Long, grams: Long, done: (Result<Unit>) -> Unit) {
        val dateSnapshot = date.value.toString()
        doWork({ repository.saveRequirement(dateSnapshot, kitchen, itemId, grams) }, done)
    }

    fun removeRequirement(kitchen: String, itemId: Long, done: (Result<Unit>) -> Unit) {
        val dateSnapshot = date.value.toString()
        doWork({ repository.deleteRequirement(dateSnapshot, kitchen, itemId) }, done)
    }

    fun savePurchase(itemId: Long, grams: Long, cents: Long, done: (Result<Unit>) -> Unit) {
        val dateSnapshot = date.value.toString()
        doWork({ repository.savePurchase(dateSnapshot, itemId, grams, cents) }, done)
    }

    fun prepareBackup(done: (Result<String>) -> Unit) {
        viewModelScope.launch {
            done(runCatching { withContext(Dispatchers.IO) { repository.exportBackup() } })
        }
    }

    fun restoreBackup(json: String, done: (Result<Unit>) -> Unit) =
        doWork({ repository.restoreBackup(json) }, done)

    companion object {
        fun factory(repository: MarketRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    MarketViewModel(repository) as T
            }
    }
}
