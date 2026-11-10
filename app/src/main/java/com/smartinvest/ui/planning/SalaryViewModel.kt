package com.smartinvest.ui.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.domain.engine.PortfolioValuationEngine
import com.smartinvest.domain.engine.SalaryAllocationPlanner
import com.smartinvest.domain.model.AppSettings
import com.smartinvest.domain.model.HouseholdSummary
import com.smartinvest.domain.model.LiquiditySource
import com.smartinvest.domain.model.SalaryPlan
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.repository.SettingsRepository
import com.smartinvest.domain.usecase.ObserveHouseholdSummaryUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth
import javax.inject.Inject

data class SalaryUiState(
    val plan: SalaryPlan? = null,
    val settings: AppSettings = AppSettings(),
    val summary: HouseholdSummary? = null,
    val sources: List<LiquiditySource> = emptyList(),
    val salaryText: String = "",
    val isSaving: Boolean = false,
    val message: String? = null,
)

private data class SalaryData(
    val summary: HouseholdSummary,
    val settings: AppSettings,
    val sources: List<LiquiditySource>,
    val mepRate: Double,
)

@HiltViewModel
class SalaryViewModel @Inject constructor(
    observeHousehold: ObserveHouseholdSummaryUseCase,
    private val investments: InvestmentRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val month = YearMonth.now()
    private val salaryText = MutableStateFlow("")
    private val saving = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private val dataFlow = combine(
        observeHousehold(month),
        investments.observeWallets(),
        investments.observeHoldings(),
        investments.observeFxRates(),
        settingsRepository.settings,
    ) { summary, wallets, holdings, fx, settings ->
        val (mep, _) = PortfolioValuationEngine.mepRate(fx, settings.fallbackMepRate)
        SalaryData(
            summary = summary,
            settings = settings,
            sources = LiquiditySources.build(wallets, holdings, mep),
            mepRate = mep,
        )
    }

    val uiState = combine(dataFlow, salaryText, saving, message) { data, text, isSaving, msg ->
        val salaryCents = MoneyParser.parseToCents(text) ?: 0L
        val liquid = LiquiditySources.liquidForEmergency(data.sources)
        val plan = SalaryAllocationPlanner.plan(
            month = month,
            netSalaryCents = salaryCents,
            householdMonthlySpendCents = data.summary.totalSpendCents,
            liquidEmergencyCents = liquid,
            settings = data.settings,
        )
        SalaryUiState(
            plan = plan,
            settings = data.settings,
            summary = data.summary,
            sources = data.sources,
            salaryText = text,
            isSaving = isSaving,
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SalaryUiState())

    fun setSalary(value: String) {
        salaryText.value = value
    }

    fun savePlan() {
        val state = uiState.value
        val plan = state.plan ?: return
        if (plan.netSalaryCents <= 0) {
            message.value = "Ingresá el sueldo neto para guardar el plan."
            return
        }
        if (saving.value) return
        viewModelScope.launch {
            saving.value = true
            runCatching { investments.saveSalaryPlan(plan) }
                .onSuccess { message.value = "Plan de ${month} guardado." }
                .onFailure { message.value = it.message ?: "No se pudo guardar el plan" }
            saving.value = false
        }
    }

    fun clearMessage() {
        message.value = null
    }
}
