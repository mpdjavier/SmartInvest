package com.smartinvest.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.domain.model.AppAlert
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.HouseholdSummary
import com.smartinvest.domain.usecase.ObserveHouseholdSummaryUseCase
import com.smartinvest.domain.usecase.ObservePortfolioOverviewUseCase
import com.smartinvest.domain.usecase.PortfolioOverview
import com.smartinvest.domain.repository.AlertsRepository
import com.smartinvest.domain.repository.InvestmentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
    private val investments: InvestmentRepository,
    private val alertsRepository: AlertsRepository,
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

    val uiState = combine(core, extras) { c, e ->
        DashboardUiState(
            isLoading = false,
            overview = c.overview,
            household = c.household,
            alerts = c.alerts,
            unreadCount = c.unread,
            fxRates = e.fxRates,
            isRefreshing = e.isRefreshing,
            message = e.message,
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
}
