package com.smartinvest.ui.analytics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.time.DateUtils
import com.smartinvest.domain.engine.PortfolioAnalyticsEngine
import com.smartinvest.domain.model.PortfolioSnapshot
import com.smartinvest.domain.repository.AlertsRepository
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.usecase.ObservePortfolioOverviewUseCase
import com.smartinvest.domain.usecase.PortfolioOverview
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AnalyticsUiState(
    val overview: PortfolioOverview? = null,
    val series: List<PortfolioSnapshot> = emptyList(),
    val isWorking: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    observePortfolio: ObservePortfolioOverviewUseCase,
    private val investments: InvestmentRepository,
    private val alerts: AlertsRepository,
) : ViewModel() {

    private val working = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState = combine(
        observePortfolio(),
        working,
        message,
    ) { overview, isWorking, msg ->
        AnalyticsUiState(
            overview = overview,
            series = PortfolioAnalyticsEngine.seriesForChart(
                overview.analytics.series,
                DateUtils.today(),
                days = 90,
            ),
            isWorking = isWorking,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsUiState())

    fun refresh() {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            message.value = null
            runCatching {
                investments.refreshDollarRates()
                investments.refreshSnapshot()
                alerts.scanAlerts()
            }.onSuccess {
                message.value = "Snapshot y cotizaciones actualizados."
            }.onFailure {
                message.value = "No se pudo actualizar. Se muestran los últimos datos."
            }
            working.value = false
        }
    }

    fun clearMessage() {
        message.value = null
    }
}
