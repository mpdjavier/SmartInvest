package com.smartinvest.ui.household

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.domain.engine.ExpenseReportEngine
import com.smartinvest.domain.engine.ExpenseReportExporter
import com.smartinvest.domain.model.CreditCard
import com.smartinvest.domain.model.ExpensePeriod
import com.smartinvest.domain.model.ExpenseRecord
import com.smartinvest.domain.model.ExpenseReport
import com.smartinvest.domain.model.HouseholdCategory
import com.smartinvest.domain.model.HouseholdMember
import com.smartinvest.domain.model.YearMonthRange
import com.smartinvest.domain.repository.HouseholdRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import javax.inject.Inject

data class ExpenseReportUiState(
    val isLoading: Boolean = true,
    val period: ExpensePeriod = ExpensePeriod.CURRENT_MONTH,
    val range: YearMonthRange = ExpensePeriod.CURRENT_MONTH.range(),
    val report: ExpenseReport = ExpenseReport(),
    val records: List<ExpenseRecord> = emptyList(),
    val categories: List<HouseholdCategory> = emptyList(),
    val members: List<HouseholdMember> = emptyList(),
    val cards: List<CreditCard> = emptyList(),
    val message: String? = null,
) {
    val fileName: String get() = "smartinvest-gastos-${period.fileSlug}.csv"

    val periodLabel: String get() = period.label
}

/**
 * Builds the expense report for the selected period and exposes the CSV / share
 * renderings. Amounts always use the monthly impact, so installments never inflate
 * a period with the full purchase value.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ExpenseReportViewModel @Inject constructor(
    private val repository: HouseholdRepository,
) : ViewModel() {

    private val period = MutableStateFlow(ExpensePeriod.CURRENT_MONTH)
    private val message = MutableStateFlow<String?>(null)
    private val today: YearMonth = YearMonth.now()

    private val meta = combine(
        repository.observeMembers(),
        repository.observeCategories(includeArchived = true),
        repository.observeCards(includeInactive = true),
    ) { members, categories, cards -> Triple(members, categories, cards) }

    private val records = period
        .flatMapLatest { selected -> repository.observeExpenseRecords(selected.range(today)) }

    val uiState = combine(records, period, meta, message) { rows, selected, metaData, msg ->
        val (members, categories, cards) = metaData
        val report = ExpenseReportEngine.build(
            records = rows,
            categories = categories.associateBy { it.id },
            members = members.associateBy { it.id },
        )
        ExpenseReportUiState(
            isLoading = false,
            period = selected,
            range = selected.range(today),
            report = report,
            records = rows,
            categories = categories,
            members = members,
            cards = cards,
            message = msg,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ExpenseReportUiState(),
    )

    fun setPeriod(value: ExpensePeriod) {
        period.value = value
    }

    fun csv(): String {
        val state = uiState.value
        return ExpenseReportExporter.csv(
            records = state.records,
            categories = state.categories.associateBy { it.id },
            members = state.members.associateBy { it.id },
            cards = state.cards.associateBy { it.id },
        )
    }

    fun summary(): String {
        val state = uiState.value
        return ExpenseReportExporter.summary(state.report, state.periodLabel)
    }

    fun reportExportError(detail: String? = null) {
        message.value = detail?.let { "No se pudo exportar el reporte: $it" }
            ?: "No se pudo exportar el reporte."
    }

    fun clearMessage() {
        message.value = null
    }
}
