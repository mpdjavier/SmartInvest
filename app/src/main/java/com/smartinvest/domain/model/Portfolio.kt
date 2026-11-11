package com.smartinvest.domain.model

import java.time.LocalDate

enum class WalletType(val label: String) {
    WALLET("Billetera virtual"),
    MONEY_MARKET_FUND("FCI money market"),
    BROKER("Bróker"),
    BANK("Banco"),
    OTHER("Otra cuenta"),
}

enum class CurrencyCode(val label: String, val symbol: String) {
    ARS("Pesos", "ARS"),
    USD("Dólares", "USD"),
}

data class Wallet(
    val id: Long,
    val name: String,
    val institution: String,
    val type: WalletType,
    val currency: CurrencyCode,
    val balanceCents: Long,
    val tnaPercent: Double,
    /** 0 means "no cap" (the whole balance earns the TNA). */
    val remuneratedCapCents: Long,
    val isActive: Boolean,
    val rateSource: String,
    val rateUpdatedAt: Long?,
    val liquidityPriority: Int,
    val notes: String,
    /** Optional FCI holding whose TNA / market rate backs this wallet. */
    val linkedHoldingId: Long? = null,
)

/** TNA applied to this wallet: the linked FCI rate wins when it is configured. */
fun Wallet.effectiveTnaPercent(holdings: List<Holding>): Double {
    val linkedTna = linkedHoldingId
        ?.let { holdingId -> holdings.firstOrNull { it.id == holdingId } }
        ?.tnaPercent
        ?: 0.0
    return if (linkedTna > 0.0) linkedTna else tnaPercent
}

/** Linked FCI holding, when configured and still present. */
fun Wallet.linkedHolding(holdings: List<Holding>): Holding? =
    linkedHoldingId?.let { holdingId -> holdings.firstOrNull { it.id == holdingId } }

enum class AssetType(val label: String) {
    LECAP("LECAP"),
    BONCAP("BONCAP"),
    BOND("Bono"),
    FCI("FCI"),
    CEDEAR("CEDEAR"),
    DOLAR_MEP("Dólar MEP"),
    CRYPTO("Cripto"),
    OTHER("Otro"),
}

/** Where the automatic quote comes from. */
enum class MarketSource(val label: String) {
    MANUAL("Manual"),
    RAVA("Rava"),
    CAFCI("CAFCI"),
}

/** How the quoted price is expressed relative to the position size. */
enum class QuoteUnit(val label: String) {
    PER_UNIT("Por unidad / cuotaparte"),
    PER_100_NOMINAL("Por 100 nominales"),
}

/**
 * A tracked holding.
 *
 * [marketKey] enables automatic quotes:
 *  - `rava:AO27D`
 *  - `cafci:1402:4070`
 *
 * When [quantity] > 0 the synced quote updates [currentValueCents] using
 * [quoteUnit]. [currency] is the currency of the quote (and of the valuation).
 */
data class Holding(
    val id: Long,
    val symbol: String,
    val name: String,
    val assetType: AssetType,
    val currency: CurrencyCode,
    val currentValueCents: Long,
    val costCents: Long,
    val tnaPercent: Double?,
    val maturityDate: LocalDate?,
    val notes: String,
    val marketKey: String = "",
    val source: MarketSource = MarketSource.MANUAL,
    val quantity: Double = 0.0,
    val quoteUnit: QuoteUnit = QuoteUnit.PER_UNIT,
    /**
     * Marks this FCI as the daily-expense account. Expenses default to it and
     * debits reduce its loaded value. Only one wallet FCI is expected at a time.
     */
    val isWallet: Boolean = false,
    /** Redemption delay in days. Wallet FCIs are treated as immediate (T+0). */
    val settlementDays: Int = 1,
) {
    val isTracked: Boolean get() = marketKey.isNotBlank()

    /** Multiplier that turns a quoted price into the value of one "unit". */
    val quoteFactor: Double get() = if (quoteUnit == QuoteUnit.PER_100_NOMINAL) 0.01 else 1.0

    fun valueCentsFor(price: Double): Long =
        kotlin.math.round(price * quoteFactor * quantity * 100.0).toLong()
}

data class InstrumentQuote(
    val marketKey: String,
    val symbol: String,
    val source: MarketSource,
    val price: Double,
    val currency: CurrencyCode,
    val quoteUnit: QuoteUnit,
    val date: LocalDate,
    val fetchedAt: Long,
)

data class PortfolioSnapshot(
    val id: Long,
    val date: LocalDate,
    val totalArsCents: Long,
    val totalUsdCents: Long,
    val arsAssetsCents: Long,
    val usdAssetsArsCents: Long,
    val dailyInterestCents: Long,
    val createdAt: Long,
)

data class FxRate(
    val house: String,
    val name: String,
    val buy: Double,
    val sell: Double,
    val updatedAt: Long,
)

data class WalletValuation(
    val wallet: Wallet,
    val valueArsCents: Long,
    val remuneratedBalanceCents: Long,
    val dailyInterestCents: Long,
    /** TNA actually applied: the linked FCI rate when available, else the manual rate. */
    val effectiveTnaPercent: Double = wallet.tnaPercent,
)

data class HoldingValuation(
    val holding: Holding,
    val valueArsCents: Long,
    /** Market close-to-close variation when quotes exist, else TNA-based accrual. */
    val dailyInterestCents: Long = 0L,
)

data class PortfolioValuation(
    val totalArsCents: Long,
    val totalUsdCents: Long,
    val arsAssetsCents: Long,
    val usdAssetsArsCents: Long,
    val dailyInterestCents: Long,
    val monthlyInterestCents: Long,
    val wallets: List<WalletValuation>,
    val holdings: List<HoldingValuation>,
    val mepRate: Double,
    val mepIsEstimated: Boolean,
    val totalCostCents: Long,
) {
    val pesoSharePercent: Double
        get() = if (totalArsCents <= 0) 0.0 else arsAssetsCents * 100.0 / totalArsCents

    val dollarSharePercent: Double get() = 100.0 - pesoSharePercent

    val unrealizedGainCents: Long get() = totalArsCents - totalCostCents
}

data class PerformanceWindow(
    val label: String,
    val startDate: LocalDate?,
    val startValueCents: Long?,
    val endDate: LocalDate?,
    val endValueCents: Long,
    val percent: Double?,
    val gainCents: Long?,
)

data class PortfolioAnalytics(
    val totalArsCents: Long,
    val change7d: PerformanceWindow,
    val change30d: PerformanceWindow,
    val ytd: PerformanceWindow,
    val dailyInterestCents: Long,
    val monthlyInterestCents: Long,
    val trailing30dGainCents: Long,
    val trailing30dGainPerDayCents: Long,
    val series: List<PortfolioSnapshot>,
)

data class RatePoint(
    val date: LocalDate,
    val tnaPercent: Double,
    val source: String,
)

data class SyncOutcome(
    val updatedWallets: Int,
    val failedWallets: Int,
    val source: String,
    val message: String,
    val atMillis: Long,
)

data class MarketSyncOutcome(
    val updatedHoldings: Int,
    val failedHoldings: Int,
    val storedQuotes: Int,
    val message: String,
    val atMillis: Long,
)

data class RebalanceAdvice(
    val pesoPercent: Double,
    val dollarPercent: Double,
    val targetPesoPercent: Double,
    val tolerancePp: Double,
    val bandLow: Double,
    val bandHigh: Double,
    val isOutOfBand: Boolean,
    val suggestedAction: String,
    val adjustmentArsCents: Long,
)
