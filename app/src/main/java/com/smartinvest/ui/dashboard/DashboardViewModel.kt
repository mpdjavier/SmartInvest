package com.smartinvest.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.time.DateUtils
import com.smartinvest.data.local.dao.ExpenseDao
import com.smartinvest.domain.engine.HistoricalExpenseRecord
import com.smartinvest.domain.engine.PaymentAgendaEngine
import com.smartinvest.domain.engine.PaymentAgendaRow
import com.smartinvest.domain.model.AppAlert
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.HouseholdSummary
import com.smartinvest.domain.usecase.ObserveHouseholdSummaryUseCase
import com.smartinvest.domain.usecase.ObservePortfolioOverviewUseCase
import com.smartinvest.domain.usecase.PortfolioOverview
import com.smartinvest.domain.repository.AlertsRepository
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

data class DashboardUiState(
    val isLoading: Boolean = true,
    val overview: PortfolioOverview? = null,
    val household: HouseholdSummary? = null,
    val alerts: List<AppAlert> = emptyList(),
    val unreadCount: Int = 0,
    val fxRates: List<FxRate> = emptyList(),
    val isRefreshing: Boolean = false,
    val message: String? = null,
    val hideAmounts: Boolean = false,
    /** Pending recurring payments scheduled for today or tomorrow. */
    val upcomingPayments: List<PaymentAgendaRow> = emptyList(),
    val today: LocalDate = DateUtils.today(),
)

private data class DashboardCore(
    val overview: PortfolioOverview,
    val household: HouseholdSummary,
    val alerts: List<AppAlert>,
    val unread: Int,
)

private data class DashboardExtras(
    val fxRates: List<FxRate>,
    val isRefreshing: Boolean,
    val message: String?,
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    observePortfolio: ObservePortfolioOverviewUseCase,
    observeHousehold: ObserveHouseholdSummaryUseCase,
    expenseDao: ExpenseDao,
    private val investments: InvestmentRepository,
    private val alertsRepository: AlertsRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val month = YearMonth.now()
    private val refreshing = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private val core = combine(
        observePortfolio(),
        observeHousehold(month),
        alertsRepository.observeAlerts(),
        alertsRepository.observeUnreadCount(),
    ) { overview, household, alerts, unread ->
        DashboardCore(overview, household, alerts, unread)
    }

    private val extras = combine(
        investments.observeFxRates(),
        refreshing,
        message,
    ) { fxRates, isRefreshing, msg ->
        DashboardExtras(fxRates, isRefreshing, msg)
    }

    /**
     * Recurring payments due today and tomorrow, using the same projection as the
     * payment calendar. Two months are built when tomorrow falls in the next month.
     */
    private val upcomingPayments = combine(
        expenseDao.observeAll(),
        settingsRepository.settings,
    ) { expenses, settings ->
        val today = DateUtils.today()
        val tomorrow = today.plusDays(1)
        val records = expenses.map {
            HistoricalExpenseRecord(
                id = it.id,
                description = it.description,
                amountCents = it.totalAmountCents,
                date = LocalDate.ofEpochDay(it.dateEpochDay),
                categoryId = it.categoryId,
                isRecurring = it.isRecurring,
                recurrenceMonths = it.recurrenceMonths,
            )
        }
        val templates = PaymentAgendaEngine.decodeTemplates(settings.paymentAgendaJson)
        val months = listOf(YearMonth.from(today), YearMonth.from(tomorrow)).distinct()
        months.flatMap { month ->
            PaymentAgendaEngine.buildForMonth(
                month = month,
                today = today,
                expenses = records,
                serviceCategoryIds = emptySet(),
                templates = templates,
                recurringOnly = true,
            ).rows
        }.filter { row ->
            !row.isPaid && (row.scheduledDate == today || row.scheduledDate == tomorrow)
        }.distinctBy { it.key to it.scheduledDate }
            .sortedBy { it.scheduledDate }
    }

    val uiState = combine(
        core,
        extras,
        settingsRepository.settings,
        upcomingPayments,
    ) { c, e, settings, payments ->
        DashboardUiState(
            isLoading = false,
            overview = c.overview,
            household = c.household,
            alerts = c.alerts,
            unreadCount = c.unread,
            fxRates = e.fxRates,
            isRefreshing = e.isRefreshing,
            message = e.message,
            hideAmounts = settings.hideAmounts,
            upcomingPayments = payments,
            today = DateUtils.today(),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun refresh() {
        if (refreshing.value) return
        viewModelScope.launch {
            refreshing.value = true
            message.value = null
            val fx = investments.refreshDollarRates()
            investments.refreshSnapshot()
            alertsRepository.scanAlerts()
            message.value = if (fx.isSuccess) {
                "Patrimonio y cotizaciones actualizados."
            } else {
                "No se pudo actualizar DolarAPI. Se muestran los últimos valores."
            }
            refreshing.value = false
        }
    }

    fun clearMessage() {
        message.value = null
    }

    /** Persists the privacy switch; MainActivity mirrors it into [MoneyVisibility]. */
    fun togglePrivacy() {
        viewModelScope.launch {
            val current = settingsRepository.currentSettings()
            settingsRepository.update(current.copy(hideAmounts = !current.hideAmounts))
        }
    }
}
