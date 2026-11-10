package com.smartinvest.ui.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.domain.engine.EmergencyRedemptionPlanner
import com.smartinvest.domain.engine.PortfolioValuationEngine
import com.smartinvest.domain.model.LiquiditySource
import com.smartinvest.domain.model.RedemptionPlan
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.repository.SettingsRepository
import com.smartinvest.domain.usecase.ObserveHouseholdSummaryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.YearMonth
import javax.inject.Inject

data class EmergencyUiState(
    val requestedText: String = "",
    val plan: RedemptionPlan? = null,
    val sources: List<LiquiditySource> = emptyList(),
    val totalAvailableCents: Long = 0,
    val liquidAvailableCents: Long = 0,
    val householdMonthlySpendCents: Long = 0,
    val quickAmounts: List<Long> = emptyList(),
)

@HiltViewModel
class EmergencyViewModel @Inject constructor(
    observeHousehold: ObserveHouseholdSummaryUseCase,
    investments: InvestmentRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val month = YearMonth.now()
    private val requested = MutableStateFlow("")

    private val dataFlow = combine(
        observeHousehold(month),
        investments.observeWallets(),
        investments.observeHoldings(),
        investments.observeFxRates(),
        settingsRepository.settings,
    ) { summary, wallets, holdings, fx, settings ->
        val (mep, _) = PortfolioValuationEngine.mepRate(fx, settings.fallbackMepRate)
        val sources = LiquiditySources.build(wallets, holdings, mep)
        Triple(summary.totalSpendCents, sources, Unit)
    }

    val uiState = combine(dataFlow, requested) { (spend, sources, _), text ->
        val amount = MoneyParser.parseToCents(text) ?: 0L
        EmergencyUiState(
            requestedText = text,
            plan = EmergencyRedemptionPlanner.simulate(amount, sources),
            sources = sources.sortedWith(compareBy({ it.tier.order }, { it.settlementDays }, { -it.availableCents })),
            totalAvailableCents = sources.sumOf { it.availableCents },
            liquidAvailableCents = LiquiditySources.liquidForEmergency(sources),
            householdMonthlySpendCents = spend,
            quickAmounts = listOfNotNull(
                spend.takeIf { it > 0 },
                spend.takeIf { it > 0 }?.times(3),
                sources.sumOf { it.availableCents } / 2,
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EmergencyUiState())

    fun setRequested(value: String) {
        requested.value = value
    }

    fun useQuickAmount(cents: Long) {
        requested.value = (cents / 100.0).toString().replace('.', ',')
    }
}
