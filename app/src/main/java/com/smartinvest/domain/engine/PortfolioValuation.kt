package com.smartinvest.domain.engine

import com.smartinvest.core.time.DateUtils
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.HoldingValuation
import com.smartinvest.domain.model.InstrumentQuote
import com.smartinvest.domain.model.MarketSource
import com.smartinvest.domain.model.PortfolioValuation
import com.smartinvest.domain.model.Wallet
import com.smartinvest.domain.model.WalletValuation
import com.smartinvest.domain.model.effectiveTnaPercent
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

object InterestCalculator {

    /** Only the balance up to the remunerated cap earns interest. 0 = no cap. */
    fun remuneratedBalance(balanceCents: Long, capCents: Long): Long {
        if (balanceCents <= 0) return 0
        return if (capCents > 0) min(balanceCents, capCents) else balanceCents
    }

    fun dailyInterestCents(balanceCents: Long, tnaPercent: Double, capCents: Long = 0): Long {
        if (tnaPercent <= 0.0) return 0
        val base = remuneratedBalance(balanceCents, capCents)
        if (base <= 0) return 0
        return (base * tnaPercent / 100.0 / 365.0).roundToLong()
    }

    fun monthlyProjectionCents(dailyInterestCents: Long): Long = dailyInterestCents * 30L
}

object PortfolioValuationEngine {

    /**
     * CEDEARs (e.g. SPY) represent foreign equities and sovereign bonds (e.g. AO27 / AO27D)
     * maintain their value in USD even when quoted or held in ARS, so they count in the
     * dollar exposure bucket for the 60/40 rebalance.
     */
    fun isDollarExposure(holding: Holding): Boolean =
        holding.currency == CurrencyCode.USD ||
            holding.assetType == AssetType.CEDEAR ||
            holding.assetType == AssetType.BOND ||
            holding.assetType == AssetType.DOLAR_MEP ||
            holding.symbol.trim().uppercase().endsWith("USD") ||
            holding.marketKey.trim().equals("cafci:1522:4557", ignoreCase = true)

    fun mepRate(rates: List<FxRate>, fallback: Double): Pair<Double, Boolean> {
        val mep = rates.firstOrNull { it.house == "bolsa" }
            ?: rates.firstOrNull { it.house == "mep" }
            ?: rates.firstOrNull { it.house == "blue" }
        val rate = mep?.sell?.takeIf { it > 0.0 } ?: fallback.takeIf { it > 0.0 } ?: 1.0
        val estimated = mep == null || mep.sell <= 0.0
        return rate to estimated
    }

    fun calculate(
        wallets: List<Wallet>,
        holdings: List<Holding>,
        fxRates: List<FxRate>,
        fallbackMepRate: Double,
        quotes: List<InstrumentQuote> = emptyList(),
        today: LocalDate = DateUtils.today(),
    ): PortfolioValuation {
        val (mep, estimated) = mepRate(fxRates, fallbackMepRate)
        val activeWallets = wallets.filter { it.isActive }
        val effectiveToday = if (
            quotes.isNotEmpty() &&
            activeWallets.none { it.balanceCents > 0L && it.effectiveTnaPercent(holdings) > 0.0 } &&
            holdings.none { (it.tnaPercent ?: 0.0) > 0.0 } &&
            quotes.none { YearMonth.from(it.date) == YearMonth.from(today) }
        ) {
            quotes.maxOf { it.date }
        } else {
            today
        }

        val sanitizedHoldings = holdings.map { holding ->
            val series = quotesFor(holding, quotes).filter { !it.date.isAfter(today) }
            val latestQuote = series.lastOrNull()
            if (holding.quantity > 0.0 && latestQuote != null && latestQuote.price > 0.0) {
                val expectedCents = holding.valueCentsFor(latestQuote.price)
                if (isCorruptedValuation(holding.currentValueCents, expectedCents)) {
                    holding.copy(currentValueCents = expectedCents)
                } else {
                    holding
                }
            } else {
                holding
            }
        }

        val breakdown = DailyInterestEngine.build(
            month = YearMonth.from(effectiveToday),
            wallets = wallets,
            holdings = sanitizedHoldings,
            fxRates = fxRates,
            quotes = quotes,
            fallbackMepRate = fallbackMepRate,
            today = effectiveToday,
        )
        val latestDay = breakdown.days.firstOrNull { it.date == effectiveToday }
            ?: breakdown.days.firstOrNull()
        val lineBySourceId = latestDay?.lines
            ?.groupBy { it.sourceId }
            ?.mapValues { (_, items) -> items.sumOf { it.dailyArsCents } }
            .orEmpty()

        val walletValuations = activeWallets.map { wallet ->
            val effectiveTna = wallet.effectiveTnaPercent(sanitizedHoldings)
            val valueArs = if (wallet.currency == CurrencyCode.ARS) {
                wallet.balanceCents
            } else {
                (wallet.balanceCents * mep).roundToLong()
            }
            val remunerated = InterestCalculator.remuneratedBalance(
                wallet.balanceCents,
                wallet.remuneratedCapCents,
            )
            val dailyArs = lineBySourceId["wallet:${wallet.id}"] ?: 0L
            WalletValuation(
                wallet = wallet,
                valueArsCents = valueArs,
                remuneratedBalanceCents = remunerated,
                dailyInterestCents = dailyArs,
                effectiveTnaPercent = effectiveTna,
            )
        }

        val holdingValuations = sanitizedHoldings.map { holding ->
            val manualDate = PortfolioReconciliationEngine.manualValuationDate(holding)
            // Only closes up to today are valid for the current valuation. A quote dated in
            // the future must never override the balance loaded for today.
            val series = quotesFor(holding, quotes).filter { !it.date.isAfter(today) }
            val latestQuote = series.lastOrNull()
            val effectiveNativeCents = if (
                holding.quantity > 0.0 &&
                latestQuote != null &&
                latestQuote.price > 0.0 &&
                (manualDate == null || latestQuote.date.isAfter(manualDate))
            ) {
                holding.valueCentsFor(latestQuote.price)
            } else {
                holding.currentValueCents
            }
            val valueArs = if (holding.currency == CurrencyCode.ARS) {
                effectiveNativeCents
            } else {
                (effectiveNativeCents * mep).roundToLong()
            }
            val dailyArs = lineBySourceId["holding:${holding.id}"] ?: 0L
            HoldingValuation(
                holding = holding.copy(currentValueCents = effectiveNativeCents),
                valueArsCents = valueArs,
                dailyInterestCents = dailyArs,
            )
        }

        val arsAssets = activeWallets.filter { it.currency == CurrencyCode.ARS }.sumOf { it.balanceCents } +
            holdingValuations.filter { !isDollarExposure(it.holding) }.sumOf { it.valueArsCents }

        val usdWalletArs = activeWallets
            .filter { it.currency == CurrencyCode.USD }
            .sumOf { (it.balanceCents * mep).roundToLong() }
        val usdHoldingArs = holdingValuations
            .filter { isDollarExposure(it.holding) }
            .sumOf { it.valueArsCents }
        val usdAssetsArs = usdWalletArs + usdHoldingArs

        val totalArs = arsAssets + usdAssetsArs
        val totalUsd = if (mep > 0.0) (totalArs / mep).roundToLong() else 0L

        val holdingDailyInterest = holdingValuations.sumOf { it.dailyInterestCents }
        val dailyInterest = latestDay?.totalArsCents
            ?: (walletValuations.sumOf { it.dailyInterestCents } + holdingDailyInterest)
        val monthlyInterest = InterestCalculator.monthlyProjectionCents(breakdown.averageDailyArsCents)

        val cost = sanitizedHoldings.sumOf { holding ->
            val costNative = if (holding.costCents > 0L) holding.costCents else holding.currentValueCents
            if (holding.currency == CurrencyCode.ARS) costNative else (costNative * mep).roundToLong()
        } + activeWallets.sumOf { it.balanceCents * if (it.currency == CurrencyCode.ARS) 1L else mep.roundToLong() }

        return PortfolioValuation(
            totalArsCents = totalArs,
            totalUsdCents = totalUsd,
            arsAssetsCents = arsAssets,
            usdAssetsArsCents = usdAssetsArs,
            dailyInterestCents = dailyInterest,
            monthlyInterestCents = monthlyInterest,
            wallets = walletValuations,
            holdings = holdingValuations,
            mepRate = mep,
            mepIsEstimated = estimated,
            totalCostCents = cost,
        )
    }

    /**
     * Latest close-to-close market variation of [holding], in the holding's native cents.
     *
     * Returns null when there is no usable pair of quotes, so callers can fall back to TNA.
     * This is what makes a synced LECAP/bond/FCI contribute to "Interés / día" even if it
     * has no TNA configured.
     *
     * When the holding has no quantity (a common case: the user loads a preset and types
     * only the current value), the variation is applied proportionally to
     * [Holding.currentValueCents] instead of returning zero.
     */
    fun latestMarketDailyCents(
        holding: Holding,
        quotes: List<InstrumentQuote>,
    ): Long? {
        if (holding.marketKey.isBlank()) return null
        val series = quotesFor(holding, quotes)
        if (series.size < 2) return null
        val previous = series[series.size - 2]
        val latest = series.last()
        if (previous.price <= 0.0 || latest.price <= 0.0) return null
        val isFci = holding.assetType == AssetType.FCI || holding.source == MarketSource.CAFCI
        val spanDays = ChronoUnit.DAYS.between(previous.date, latest.date).coerceAtLeast(1L)
        val prevPrice = if (isFci && spanDays > 1L) {
            val dailyFactor = (latest.price / previous.price).pow(1.0 / spanDays.toDouble())
            if (dailyFactor > 0.0) latest.price / dailyFactor else previous.price
        } else {
            previous.price
        }
        return if (holding.quantity > 0.0) {
            holding.valueCentsFor(latest.price) - holding.valueCentsFor(prevPrice)
        } else {
            if (holding.currentValueCents <= 0L) return null
            val prevValueCents = (holding.currentValueCents * (prevPrice / latest.price)).roundToLong()
            holding.currentValueCents - prevValueCents
        }
    }

    /**
     * Quotes that belong to [holding], sorted oldest first.
     *
     * Matches the market key first; when the stored key and the quote key differ in
     * case/format (or a holding was re-keyed), falls back to the symbol so an existing
     * quote history is not silently ignored.
     */
    fun quotesFor(holding: Holding, quotes: List<InstrumentQuote>): List<InstrumentQuote> {
        val key = holding.marketKey.trim()
        val byKey = if (key.isBlank()) {
            emptyList()
        } else {
            quotes.filter { it.marketKey.trim().equals(key, ignoreCase = true) }
        }
        val matched = if (byKey.isNotEmpty()) {
            byKey
        } else {
            val symbol = holding.symbol.trim()
            if (symbol.isBlank()) {
                emptyList()
            } else {
                quotes.filter { it.symbol.trim().equals(symbol, ignoreCase = true) }
            }
        }
        return sanitizeQuoteSeries(matched.filter { it.price > 0.0 }.sortedBy { it.date })
    }

    /**
     * Filters out off-hours scraping glitches (such as `$ 2,30% TEM` mistaken for a price of
     * `2.30` on `S30N6` when the previous close was `129.08`) that drop >55% or spike >120%
     * within a short window.
     */
    internal fun sanitizeQuoteSeries(sorted: List<InstrumentQuote>): List<InstrumentQuote> {
        if (sorted.size < 2) return sorted
        val result = mutableListOf<InstrumentQuote>()
        for (quote in sorted) {
            if (quote.price <= 0.0) continue
            val prev = result.lastOrNull()
            if (prev == null) {
                result += quote
            } else {
                val spanDays = ChronoUnit.DAYS.between(prev.date, quote.date)
                val ratio = quote.price / prev.price
                if (spanDays > 60L || ratio in 0.45..2.20) {
                    result += quote
                }
            }
        }
        return result
    }

    /**
     * Detects when a holding's stored [storedCents] was overwritten by an off-hours
     * scraping artifact (deviating by more than -55% or +120% from [expectedQuoteCents]).
     */
    internal fun isCorruptedValuation(storedCents: Long, expectedQuoteCents: Long): Boolean {
        if (expectedQuoteCents <= 0L) return false
        if (storedCents <= 0L) return true
        val ratio = storedCents.toDouble() / expectedQuoteCents.toDouble()
        return ratio < 0.45 || ratio > 2.20
    }

    private fun toArs(nativeCents: Long, currency: CurrencyCode, mep: Double): Long =
        if (currency == CurrencyCode.ARS) nativeCents else (nativeCents * mep).roundToLong()
}
