package com.smartinvest.ui.household

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.domain.engine.ExpenseReportEngine
import com.smartinvest.domain.model.CreditCard
import com.smartinvest.domain.model.ExpenseFilter
import com.smartinvest.domain.model.ExpenseMonthBucket
import com.smartinvest.domain.model.ExpensePaymentMethod
import com.smartinvest.domain.model.ExpensePeriod
import com.smartinvest.domain.model.ExpenseRecord
import com.smartinvest.domain.model.HouseholdCategory
import com.smartinvest.domain.model.HouseholdMember
import com.smartinvest.domain.repository.HouseholdRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class ExpenseSearchUiState(
    val isLoading: Boolean = true,
    val filter: ExpenseFilter = ExpenseFilter(),
    val members: List<HouseholdMember> = emptyList(),
    val categories: List<HouseholdCategory> = emptyList(),
    val cards: List<CreditCard> = emptyList(),
    val results: List<ExpenseRecord> = emptyList(),
    val totalCents: Long = 0L,
    val monthBuckets: List<ExpenseMonthBucket> = emptyList(),
) {
    val paymentMethods: List<ExpensePaymentMethod> get() = ExpensePaymentMethod.entries
}

/**
 * Free-text + structured search over every expense occurrence (installments included).
 * The database only re-queries when the period changes; the rest of the filters are
 * applied in memory so typing stays instant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExpenseSearchViewModel @Inject constructor(
    private val repository: HouseholdRepository,
) : ViewModel() {

    private val filter = MutableStateFlow(ExpenseFilter())

    private val meta = combine(
        repository.observeMembers(),
        repository.observeCategories(includeArchived = true),
        repository.observeCards(includeInactive = true),
    ) { members, categories, cards -> Triple(members, categories, cards) }

    private val records = filter
        .map { it.period }
        .distinctUntilChanged()
        .flatMapLatest { period -> repository.observeExpenseRecords(period.range()) }

    val uiState = combine(records, filter, meta) { rows, currentFilter, metaData ->
        val (members, categories, cards) = metaData
        val results = ExpenseReportEngine.filter(
            records = rows,
            filter = currentFilter,
            categories = categories,
            members = members,
            cards = cards,
        )
        ExpenseSearchUiState(
            isLoading = false,
            filter = currentFilter,
            members = members,
            categories = categories,
            cards = cards,
            results = results,
            totalCents = results.sumOf { it.impactCents },
            monthBuckets = ExpenseReportEngine.monthBuckets(results),
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ExpenseSearchUiState(),
    )

    fun setQuery(value: String) = filter.update { it.copy(query = value) }

    fun setPeriod(period: ExpensePeriod) = filter.update { it.copy(period = period) }

    fun setCategory(categoryId: Long?) = filter.update { it.copy(categoryId = categoryId) }

    fun setPaymentMethod(method: ExpensePaymentMethod?) =
        filter.update { it.copy(paymentMethod = method) }

    fun setMember(memberId: Long?) = filter.update { it.copy(memberId = memberId) }

    /** Clears text and structured filters but keeps the selected period. */
    fun clearFilters() = filter.update {
        it.copy(query = "", categoryId = null, paymentMethod = null, memberId = null)
    }
}
