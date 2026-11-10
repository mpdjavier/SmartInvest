package com.smartinvest.ui.investments

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.Wallet
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

data class InvestmentsUiState(
    val overview: PortfolioOverview? = null,
    val wallets: List<Wallet> = emptyList(),
    val holdings: List<Holding> = emptyList(),
    val fxRates: List<FxRate> = emptyList(),
    val isWorking: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class InvestmentsViewModel @Inject constructor(
    observePortfolio: ObservePortfolioOverviewUseCase,
    private val investments: InvestmentRepository,
    private val alerts: AlertsRepository,
) : ViewModel() {

    private val working = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private val core = combine(
        observePortfolio(),
        investments.observeWallets(),
        investments.observeHoldings(),
    ) { overview, wallets, holdings ->
        Triple(overview, wallets, holdings)
    }

    private val extras = combine(
        investments.observeFxRates(),
        working,
        message,
    ) { fxRates, isWorking, msg ->
        Triple(fxRates, isWorking, msg)
    }

    val uiState = combine(core, extras) { (overview, wallets, holdings), (fxRates, isWorking, msg) ->
        InvestmentsUiState(
            overview = overview,
            wallets = wallets,
            holdings = holdings,
            fxRates = fxRates,
            isWorking = isWorking,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InvestmentsUiState())

    fun syncRates() {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            message.value = null
            val outcome = runCatching { investments.syncRates() }
            investments.refreshDollarRates()
            investments.refreshSnapshot()
            alerts.scanAlerts()
            message.value = outcome.fold(
                onSuccess = { "${it.message} · Fuente: ${it.source}" },
                onFailure = { "No se pudieron sincronizar las tasas. Se mantienen las actuales." },
            )
            working.value = false
        }
    }

    fun refreshMarket() {
        if (working.value) return
        viewModelScope.launch {
            working.value = true
            message.value = null
            val fx = investments.refreshDollarRates()
            val quotes = runCatching { investments.syncMarketQuotes() }.getOrNull()
            investments.refreshSnapshot()
            alerts.scanAlerts()
            message.value = buildString {
                if (fx.isSuccess) {
                    append("Dólar y snapshot actualizados")
                } else {
                    append("DolarAPI sin respuesta (se usa último MEP)")
                }
                if (quotes != null && (quotes.updatedHoldings > 0 || quotes.failedHoldings > 0)) {
                    append(" · ")
                    append(quotes.message)
                }
            }
            working.value = false
        }
    }

    fun clearMessage() {
        message.value = null
    }
}
