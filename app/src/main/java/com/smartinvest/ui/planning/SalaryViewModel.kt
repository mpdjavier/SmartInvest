package com.smartinvest.ui.planning

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.domain.engine.SalaryAllocationPlanner
import com.smartinvest.domain.model.AppSettings
import com.smartinvest.domain.model.CarryRiskAssessment
import com.smartinvest.domain.model.HouseholdSummary
import com.smartinvest.domain.model.LiquiditySource
import com.smartinvest.domain.model.SalaryPlan
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.usecase.ObserveHouseholdSummaryUseCase
import com.smartinvest.domain.usecase.ObservePortfolioOverviewUseCase
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
    val macro: CarryRiskAssessment,
)

@HiltViewModel
class SalaryViewModel @Inject constructor(
    observeHousehold: ObserveHouseholdSummaryUseCase,
    observePortfolio: ObservePortfolioOverviewUseCase,
    private val investments: InvestmentRepository,
) : ViewModel() {

    private val month = YearMonth.now()
    private val salaryText = MutableStateFlow("")
    private val saving = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private val dataFlow = combine(
        observeHousehold(month),
        observePortfolio(),
    ) { summary, overview ->
        val wallets = overview.valuation.wallets.map { it.wallet }
        val holdings = overview.valuation.holdings.map { it.holding }
        SalaryData(
            summary = summary,
            settings = overview.settings,
            sources = LiquiditySources.build(wallets, holdings, overview.valuation.mepRate),
            macro = overview.macro,
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
            targetPesoPercent = data.macro.riskAdjustedTargetPesoPercent,
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
