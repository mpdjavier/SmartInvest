package com.smartinvest.domain.repository

import com.smartinvest.domain.model.ComparaTasasCatalog
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.HoldingMovement
import com.smartinvest.domain.model.HoldingMovementType
import com.smartinvest.domain.model.InstrumentQuote
import com.smartinvest.domain.model.MacroMarketSnapshot
import com.smartinvest.domain.model.MarketSyncOutcome
import com.smartinvest.domain.model.PortfolioSnapshot
import com.smartinvest.domain.model.PortfolioValuation
import com.smartinvest.domain.model.QuoteUnit
import com.smartinvest.domain.model.RatePoint
import com.smartinvest.domain.model.SavedSalaryPlan
import com.smartinvest.domain.model.SalaryPlan
import com.smartinvest.domain.model.SyncOutcome
import com.smartinvest.domain.model.Wallet
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface InvestmentRepository {
    fun observeWallets(): Flow<List<Wallet>>
    fun observeActiveWallets(): Flow<List<Wallet>>
    fun observeHoldings(): Flow<List<Holding>>
    /** Manual income / withdrawals registered against a holding, newest first. */
    fun observeHoldingMovements(holdingId: Long): Flow<List<HoldingMovement>>
    /** Holdings flagged as the wallet/transaction account (FCI). */
    fun observeWalletHoldings(): Flow<List<Holding>>
    fun observeQuotes(marketKey: String): Flow<List<InstrumentQuote>>
    fun observeAllQuotes(): Flow<List<InstrumentQuote>>
    fun observeSnapshots(): Flow<List<PortfolioSnapshot>>
    fun observeFxRates(): Flow<List<FxRate>>
    fun observeRateHistory(walletId: Long): Flow<List<RatePoint>>
    fun observeSalaryPlans(): Flow<List<SavedSalaryPlan>>

    suspend fun upsertWallet(wallet: Wallet): Long
    suspend fun setWalletActive(id: Long, active: Boolean)
    suspend fun updateWalletRate(walletId: Long, tnaPercent: Double, source: String)

    suspend fun upsertHolding(holding: Holding): Long
    suspend fun deleteHolding(id: Long)
    /**
     * Registers a manual income / withdrawal on [holdingId] and updates the
     * holding valuation, cost and quantity accordingly.
     */
    suspend fun addHoldingMovement(
        holdingId: Long,
        type: HoldingMovementType,
        amountCents: Long,
        date: LocalDate,
        note: String = "",
    ): Long
    /** Deletes a movement and reverts its effect on the holding. */
    suspend fun deleteHoldingMovement(id: Long)

    suspend fun syncRates(): SyncOutcome
    suspend fun refreshComparaTasasCatalog(): Result<ComparaTasasCatalog>
    suspend fun syncMarketQuotes(): MarketSyncOutcome
    suspend fun syncInstrument(
        marketKey: String,
        symbol: String,
        currency: CurrencyCode = CurrencyCode.ARS,
        quoteUnit: QuoteUnit = QuoteUnit.PER_UNIT,
    ): Result<Int>
    suspend fun refreshDollarRates(): Result<Unit>
    /**
     * Fetches the alternative macro market series (country risk, MEP/wholesale/blue
     * and 30-day fixed-term rates), persists them and returns the snapshot. When
     * [force] is false a snapshot younger than 12 hours is reused.
     */
    suspend fun refreshMacroMarket(force: Boolean = false): Result<MacroMarketSnapshot>
    suspend fun refreshSnapshot(): PortfolioValuation
    suspend fun saveSalaryPlan(plan: SalaryPlan)
}
