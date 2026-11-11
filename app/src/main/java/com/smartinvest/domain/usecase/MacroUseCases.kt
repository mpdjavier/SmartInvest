package com.smartinvest.domain.usecase

import com.smartinvest.domain.engine.MacroRiskEngine
import com.smartinvest.domain.engine.PortfolioValuationEngine
import com.smartinvest.domain.model.AppSettings
import com.smartinvest.domain.model.CarryRiskAssessment
import com.smartinvest.domain.model.PortfolioValuation
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject

/**
 * Everything the macro rebalancing assistant needs, already valued in ARS.
 *
 * [latestSalaryCents] comes from the most recent saved salary plan and is used
 * as a convenient default for the "next income" field.
 */
data class MacroRebalanceContext(
    val valuation: PortfolioValuation,
    val assessment: CarryRiskAssessment,
    val settings: AppSettings,
    val referencePesoRatePercent: Double,
    val latestSalaryCents: Long,
)

class ObserveMacroRebalanceUseCase @Inject constructor(
    private val investments: InvestmentRepository,
    private val settings: SettingsRepository,
) {
    operator fun invoke(): Flow<MacroRebalanceContext> = combine(
        investments.observeWallets(),
        investments.observeHoldings(),
        investments.observeFxRates(),
        investments.observeSalaryPlans(),
        settings.settings,
    ) { wallets, holdings, fxRates, salaryPlans, currentSettings ->
        val valuation = PortfolioValuationEngine.calculate(
            wallets = wallets,
            holdings = holdings,
            fxRates = fxRates,
            fallbackMepRate = currentSettings.fallbackMepRate,
        )
        val referenceRate = MacroRiskEngine.referencePesoRate(wallets, holdings, currentSettings)
        val investable = MacroRiskEngine.investableValuation(valuation, currentSettings)
        val assessment = MacroRiskEngine.assess(
            valuation = investable,
            fxRates = fxRates,
            settings = currentSettings,
            referenceTnaPercent = referenceRate,
        )
        if (currentSettings.previousCarryBand != assessment.band) {
            settings.setPreviousCarryBand(assessment.band.name)
        }
        MacroRebalanceContext(
            valuation = investable,
            assessment = assessment,
            settings = currentSettings,
            referencePesoRatePercent = referenceRate,
            latestSalaryCents = salaryPlans.maxByOrNull { it.month }?.netSalaryCents ?: 0L,
        )
    }
}
