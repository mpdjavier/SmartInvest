package com.smartinvest.domain.usecase

import com.smartinvest.domain.engine.DailyInterestEngine
import com.smartinvest.domain.engine.MacroRiskEngine
import com.smartinvest.domain.engine.PortfolioAnalyticsEngine
import com.smartinvest.domain.engine.PortfolioValuationEngine
import com.smartinvest.domain.engine.RebalanceAdvisor
import com.smartinvest.domain.engine.SettlementCalculator
import com.smartinvest.domain.model.AppSettings
import com.smartinvest.domain.model.CarryRiskAssessment
import com.smartinvest.domain.model.DailyInterestBreakdown
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.HouseholdSummary
import com.smartinvest.domain.model.InstrumentQuote
import com.smartinvest.domain.model.PortfolioAnalytics
import com.smartinvest.domain.model.PortfolioSnapshot
import com.smartinvest.domain.model.PortfolioValuation
import com.smartinvest.domain.model.RebalanceAdvice
import com.smartinvest.domain.model.Wallet
import com.smartinvest.domain.repository.HouseholdRepository
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

class ObserveHouseholdSummaryUseCase @Inject constructor(
    private val repository: HouseholdRepository,
) {
    operator fun invoke(month: YearMonth): Flow<HouseholdSummary> = combine(
        repository.observeMembers(),
        repository.observeMonthlyExpenses(month),
        repository.observeFundMovements(month),
        repository.observeFundBalance(),
        repository.observeSettlements(month),
    ) { members, expenses, movements, balance, settlements ->
        SettlementCalculator.calculate(month, members, expenses, movements, balance, settlements)
    }
}

class ObserveInterestBreakdownUseCase @Inject constructor(
    private val investments: InvestmentRepository,
    private val settings: SettingsRepository,
) {
    operator fun invoke(
        month: YearMonth,
        today: LocalDate = com.smartinvest.core.time.DateUtils.today(),
    ): Flow<DailyInterestBreakdown> = combine(
        investments.observeWallets(),
        investments.observeHoldings(),
        investments.observeFxRates(),
        investments.observeAllQuotes(),
        settings.settings,
    ) { wallets, holdings, fxRates, quotes, currentSettings ->
        DailyInterestEngine.build(
            month = month,
            wallets = wallets,
            holdings = holdings,
            fxRates = fxRates,
            quotes = quotes,
            fallbackMepRate = currentSettings.fallbackMepRate,
            today = today,
        )
    }
}

data class PortfolioOverview(
    val valuation: PortfolioValuation,
    val analytics: PortfolioAnalytics,
    val rebalance: RebalanceAdvice,
    val settings: AppSettings,
    val macro: CarryRiskAssessment,
    val referencePesoRatePercent: Double,
)

private data class PortfolioInputs(
    val wallets: List<Wallet>,
    val holdings: List<Holding>,
    val fx: List<FxRate>,
    val snapshots: List<PortfolioSnapshot>,
    val quotes: List<InstrumentQuote>,
)

class ObservePortfolioOverviewUseCase @Inject constructor(
    private val investments: InvestmentRepository,
    private val settings: SettingsRepository,
) {
    operator fun invoke(today: java.time.LocalDate = com.smartinvest.core.time.DateUtils.today()): Flow<PortfolioOverview> {
        val inputs = combine(
            investments.observeWallets(),
            investments.observeHoldings(),
            investments.observeFxRates(),
            investments.observeSnapshots(),
            investments.observeAllQuotes(),
        ) { wallets, holdings, fx, snapshots, quotes ->
            PortfolioInputs(wallets, holdings, fx, snapshots, quotes)
        }
        return combine(inputs, settings.settings) { data, currentSettings ->
            val valuation = PortfolioValuationEngine.calculate(
                wallets = data.wallets,
                holdings = data.holdings,
                fxRates = data.fx,
                fallbackMepRate = currentSettings.fallbackMepRate,
                quotes = data.quotes,
            )
            val analytics = PortfolioAnalyticsEngine.calculate(valuation, data.snapshots, today)
            val referenceRate = MacroRiskEngine.referencePesoRate(data.wallets, data.holdings, currentSettings)
            PortfolioOverview(
                valuation = valuation,
                analytics = analytics,
                rebalance = RebalanceAdvisor.analyze(valuation, currentSettings),
                settings = currentSettings,
                macro = MacroRiskEngine.assess(
                    valuation = valuation,
                    fxRates = data.fx,
                    settings = currentSettings,
                    referenceTnaPercent = referenceRate,
                ),
                referencePesoRatePercent = referenceRate,
            )
        }
    }
}
