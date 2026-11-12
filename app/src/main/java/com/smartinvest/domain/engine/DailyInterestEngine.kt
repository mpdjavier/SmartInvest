package com.smartinvest.domain.engine

import com.smartinvest.core.money.MoneyFormatter
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.DailyInterestBreakdown
import com.smartinvest.domain.model.DailyInterestDay
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.InstrumentQuote
import com.smartinvest.domain.model.InterestLine
import com.smartinvest.domain.model.InterestReconciliation
import com.smartinvest.domain.model.InterestSourceKind
import com.smartinvest.domain.model.InterestSourceSummary
import com.smartinvest.domain.model.MarketSource
import com.smartinvest.domain.model.QuoteUnit
import com.smartinvest.domain.model.Wallet
import com.smartinvest.domain.model.WalletType
import com.smartinvest.domain.model.effectiveTnaPercent
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.pow
import kotlin.math.roundToLong

object DailyInterestEngine {

    private val localeAr: Locale = Locale.forLanguageTag("es-AR")

    fun build(
        month: YearMonth,
        wallets: List<Wallet>,
        holdings: List<Holding>,
        fxRates: List<FxRate>,
        quotes: List<InstrumentQuote>,
        fallbackMepRate: Double,
        today: LocalDate,
    ): DailyInterestBreakdown {
        val (mep, _) = PortfolioValuationEngine.mepRate(fxRates, fallbackMepRate)
        val activeWallets = wallets.filter {
            it.isActive && it.balanceCents > 0 && it.effectiveTnaPercent(holdings) > 0.0
        }
        val linkedHoldingIds = wallets
            .filter { it.isActive && it.balanceCents > 0L }
            .mapNotNull { it.linkedHoldingId }
            .toSet()
        val duplicateSymbols = holdings
            .groupingBy { it.symbol.trim().uppercase() }
            .eachCount()
            .filter { it.key.isNotBlank() && it.value > 1 }
            .keys
        val missingMarketSources = linkedSetOf<String>()
        val marketOverridesTna = linkedSetOf<String>()

        val daysInMonth = datesForMonth(month, today)
        if (daysInMonth.isEmpty()) {
            return DailyInterestBreakdown(
                month = month,
                days = emptyList(),
                totalArsCents = 0L,
                averageDailyArsCents = 0L,
                sources = emptyList(),
                reconciliation = InterestReconciliation(),
            )
        }

        val dayRows = daysInMonth.map { date ->
            val lines = mutableListOf<InterestLine>()

            activeWallets.forEach { wallet ->
                val tna = wallet.effectiveTnaPercent(holdings)
                val baseNative = InterestCalculator.remuneratedBalance(
                    wallet.balanceCents,
                    wallet.remuneratedCapCents,
                )
                val dailyNative = InterestCalculator.dailyInterestCents(
                    wallet.balanceCents,
                    tna,
                    wallet.remuneratedCapCents,
                )
                if (dailyNative != 0L) {
                    val dailyArs = toArs(dailyNative, wallet.currency, mep)
                    val basisArs = toArs(baseNative, wallet.currency, mep)
                    lines += InterestLine(
                        sourceId = "wallet:${wallet.id}",
                        sourceName = wallet.name,
                        kind = walletKind(wallet.type),
                        currency = wallet.currency,
                        basisCents = basisArs,
                        tnaPercent = tna,
                        dailyNativeCents = dailyNative,
                        dailyArsCents = dailyArs,
                        explanation = walletFormula(wallet, tna, baseNative, mep),
                    )
                }
            }

            holdings.forEach { rawHolding ->
                val effectiveValueCents = BrokerMovementImporter.effectiveValueCentsOnDate(
                    currentValueCents = rawHolding.currentValueCents,
                    notes = rawHolding.notes,
                    date = date,
                )
                val holding = if (effectiveValueCents != rawHolding.currentValueCents) {
                    rawHolding.copy(currentValueCents = effectiveValueCents)
                } else {
                    rawHolding
                }
                val label = holdingLabel(holding, duplicateSymbols)
                val isFci = holding.assetType == AssetType.FCI || holding.source == MarketSource.CAFCI
                val series = if (holding.marketKey.isNotBlank()) {
                    PortfolioValuationEngine.quotesFor(holding, quotes)
                } else {
                    emptyList()
                }
                val latestPrice = series.lastOrNull()?.price ?: 0.0
                val quotePair = resolveQuotePairForDate(
                    holding = holding,
                    isFci = isFci,
                    linkedHoldingIds = linkedHoldingIds,
                    series = series,
                    date = date,
                )
                val prevPrice = quotePair?.first
                val currPrice = quotePair?.second
                val deltaNative = if (prevPrice != null && currPrice != null) {
                    holdingDailyDeltaCents(holding, prevPrice, currPrice, latestPrice)
                } else {
                    null
                }

                if (prevPrice != null && currPrice != null && deltaNative != null) {
                    if ((holding.tnaPercent ?: 0.0) > 0.0) {
                        marketOverridesTna += label
                    }
                    val basisNative = holdingBasisCents(holding, prevPrice, currPrice, latestPrice)
                    val basisArs = toArs(basisNative, holding.currency, mep)
                    val deltaArs = toArs(deltaNative, holding.currency, mep)
                    lines += InterestLine(
                        sourceId = "holding:${holding.id}",
                        sourceName = label,
                        kind = holdingKind(holding.assetType),
                        currency = holding.currency,
                        basisCents = basisArs,
                        priceStart = prevPrice,
                        priceEnd = currPrice,
                        quantity = holding.quantity,
                        quoteUnit = holding.quoteUnit,
                        dailyNativeCents = deltaNative,
                        dailyArsCents = deltaArs,
                        explanation = quoteFormula(holding, prevPrice, currPrice, deltaNative, mep),
                    )
                } else if (
                    holding.id !in linkedHoldingIds &&
                    (holding.tnaPercent ?: 0.0) > 0.0 &&
                    holding.currentValueCents > 0L
                ) {
                    val tna = holding.tnaPercent ?: 0.0
                    val dailyNative = InterestCalculator.dailyInterestCents(holding.currentValueCents, tna)
                    if (dailyNative != 0L) {
                        val dailyArs = toArs(dailyNative, holding.currency, mep)
                        val basisArs = toArs(holding.currentValueCents, holding.currency, mep)
                        lines += InterestLine(
                            sourceId = "holding:${holding.id}",
                            sourceName = label,
                            kind = holdingKind(holding.assetType),
                            currency = holding.currency,
                            basisCents = basisArs,
                            tnaPercent = tna,
                            dailyNativeCents = dailyNative,
                            dailyArsCents = dailyArs,
                            explanation = holdingTnaFormula(holding, tna, mep),
                        )
                    }
                } else if (
                    series.size < 2 &&
                    holding.id !in linkedHoldingIds &&
                    holding.currentValueCents > 0L
                ) {
                    missingMarketSources += label
                }
            }

            DailyInterestDay(
                date = date,
                lines = lines,
                totalArsCents = lines.sumOf { it.dailyArsCents },
            )
        }.filter { it.lines.isNotEmpty() }

        val totalArs = dayRows.sumOf { it.totalArsCents }
        val avgDaily = if (dayRows.isEmpty()) 0L else (totalArs.toDouble() / dayRows.size).roundToLong()

        val sourceSummaries = dayRows
            .flatMap { it.lines }
            .groupBy { it.sourceId }
            .map { (sourceId, items) ->
                val sample = items.first()
                val sum = items.sumOf { it.dailyArsCents }
                val count = items.size
                val marketBased = sample.priceStart != null && sample.priceEnd != null
                val basisLabel = if (marketBased) {
                    "Cotización real (${sample.sourceName})"
                } else {
                    "TNA ${formatRate(sample.tnaPercent ?: 0.0)} ÷ 365"
                }
                InterestSourceSummary(
                    sourceId = sourceId,
                    sourceName = sample.sourceName,
                    kind = sample.kind,
                    totalArsCents = sum,
                    days = count,
                    averageDailyArsCents = if (count > 0) (sum.toDouble() / count).roundToLong() else 0L,
                    basisLabel = basisLabel,
                    accrualArsCents = if (marketBased) 0L else sum,
                    marketArsCents = if (marketBased) sum else 0L,
                )
            }
            .sortedByDescending { kotlin.math.abs(it.totalArsCents) }

        val allLines = dayRows.flatMap { it.lines }
        val accrualLines = allLines.filter { it.priceStart == null }
        val marketLines = allLines.filter { it.priceStart != null }
        val accrualTotal = accrualLines.sumOf { it.dailyArsCents }
        val marketTotal = marketLines.sumOf { it.dailyArsCents }
        val accrualPerDay = accrualLines
            .groupBy { it.sourceId }
            .values
            .sumOf { it.first().dailyArsCents }
        val daysConsidered = daysInMonth.size
        val projectedMonth = accrualPerDay * daysConsidered
        val differenceVsProjection = totalArs - projectedMonth
        val unexplained = differenceVsProjection - marketTotal
        val accrualSources = accrualLines.map { it.sourceId }.distinct().size
        val marketSources = marketLines.map { it.sourceId }.distinct().size

        val reconciliation = InterestReconciliation(
            daysConsidered = daysConsidered,
            accrualTotalArsCents = accrualTotal,
            marketTotalArsCents = marketTotal,
            totalArsCents = totalArs,
            accrualPerDayArsCents = accrualPerDay,
            projectedMonthArsCents = projectedMonth,
            differenceVsProjectionArsCents = differenceVsProjection,
            unexplainedArsCents = unexplained,
            accrualSources = accrualSources,
            marketSources = marketSources,
            missingMarketSources = missingMarketSources.toList(),
            notes = reconciliationNotes(
                daysConsidered = daysConsidered,
                accrualPerDay = accrualPerDay,
                projectedMonth = projectedMonth,
                marketTotal = marketTotal,
                accrualSources = accrualSources,
                marketSources = marketSources,
                unexplained = unexplained,
                missingSources = missingMarketSources.toList(),
                marketOverridesTna = marketOverridesTna.toList(),
            ),
        )

        return DailyInterestBreakdown(
            month = month,
            days = dayRows.sortedByDescending { it.date },
            totalArsCents = totalArs,
            averageDailyArsCents = avgDaily,
            sources = sourceSummaries,
            reconciliation = reconciliation,
        )
    }

    private fun reconciliationNotes(
        daysConsidered: Int,
        accrualPerDay: Long,
        projectedMonth: Long,
        marketTotal: Long,
        accrualSources: Int,
        marketSources: Int,
        unexplained: Long,
        missingSources: List<String>,
        marketOverridesTna: List<String>,
    ): List<String> = buildList {
        if (accrualSources > 0) {
            add(
                "TNA: $accrualSources ${if (accrualSources == 1) "fuente" else "fuentes"} × " +
                    "${formatArs(accrualPerDay)} / día × $daysConsidered días = ${formatArs(projectedMonth)}. " +
                    "Se usa el saldo y la TNA actuales para todos los días, no el saldo histórico.",
            )
        }
        if (marketSources > 0) {
            add(
                "Mercado: $marketSources ${if (marketSources == 1) "fuente" else "fuentes"} con " +
                    "cotización real suman ${formatArs(marketTotal)} de variación de precio. " +
                    "Esa variación también se incluye en “Interés / día”.",
            )
        }
        if (missingSources.isNotEmpty()) {
            add(
                "Sin variación calculada por falta de al menos 2 cotizaciones: " +
                    missingSources.joinToString(", ") + ". Actualizá mercado para completar el historial.",
            )
        }
        if (marketOverridesTna.isNotEmpty()) {
            add(
                "Estas tenencias tienen TNA pero se valoran con cotización real, así que la " +
                    "estimación diaria de TNA y el acumulado usan criterios distintos: " +
                    marketOverridesTna.joinToString(", ") + ".",
            )
        }
        if (unexplained != 0L) {
            add(
                "Quedan ${formatArs(unexplained)} sin explicar por completo: revisá cambios de saldo, " +
                    "topes remunerados, TNA modificadas durante el mes y redondeos diarios.",
            )
        } else if (marketSources > 0 || accrualSources > 0) {
            add("La diferencia contra la proyección diaria queda explicada por completo.")
        }
    }

    private fun formatArs(cents: Long): String = MoneyFormatter.ars(cents)

    private fun datesForMonth(month: YearMonth, today: LocalDate): List<LocalDate> {
        val currentMonth = YearMonth.from(today)
        if (month.isAfter(currentMonth)) return emptyList()
        val start = month.atDay(1)
        val end = if (month == currentMonth) today else month.atEndOfMonth()
        val count = (end.dayOfMonth - start.dayOfMonth + 1).coerceAtLeast(0)
        return List(count) { idx -> start.plusDays(idx.toLong()) }
    }

    private fun toArs(cents: Long, currency: CurrencyCode, mep: Double): Long =
        if (currency == CurrencyCode.ARS) cents else (cents * mep).roundToLong()

    /**
     * Resolves the `(prevPrice, currPrice)` pair for [holding] on [date].
     *
     * For regular market instruments (CEDEARs, LECAPs, bonds), a variation line is
     * emitted on dates that have a stored close (`idx > 0`).
     *
     * For FCIs (`AssetType.FCI` / `MarketSource.CAFCI`), cuotaparte yields accrue daily
     * even when CAFCI publishes with a lag or only provides window anchors: multi-day
     * jumps between stored quotes are normalized to a 1-day factor, and dates after the
     * first stored quote that lack an explicit row use the daily factor of the surrounding
     * (or latest) CAFCI interval.
     */
    private fun resolveQuotePairForDate(
        holding: Holding,
        isFci: Boolean,
        linkedHoldingIds: Set<Long>,
        series: List<InstrumentQuote>,
        date: LocalDate,
    ): Pair<Double, Double>? {
        val idx = series.indexOfFirst { it.date == date }
        if (idx > 0) {
            val prev = series[idx - 1]
            val curr = series[idx]
            if (prev.price <= 0.0 || curr.price <= 0.0) return null
            val spanDays = ChronoUnit.DAYS.between(prev.date, curr.date).coerceAtLeast(1L)
            return if (isFci && spanDays > 1L) {
                val dailyFactor = (curr.price / prev.price).pow(1.0 / spanDays.toDouble())
                val effectivePrev = if (dailyFactor > 0.0) curr.price / dailyFactor else prev.price
                effectivePrev to curr.price
            } else {
                prev.price to curr.price
            }
        }
        if (
            isFci &&
            holding.id !in linkedHoldingIds &&
            series.size >= 2 &&
            date.isAfter(series.first().date) &&
            (holding.tnaPercent ?: 0.0) <= 0.0
        ) {
            val nextIdx = series.indexOfFirst { it.date.isAfter(date) }
            val (refPrev, refCurr) = if (nextIdx > 0) {
                series[nextIdx - 1] to series[nextIdx]
            } else {
                series[series.size - 2] to series.last()
            }
            if (refPrev.price <= 0.0 || refCurr.price <= 0.0) return null
            val spanDays = ChronoUnit.DAYS.between(refPrev.date, refCurr.date).coerceAtLeast(1L)
            val dailyFactor = (refCurr.price / refPrev.price).pow(1.0 / spanDays.toDouble())
            if (dailyFactor <= 0.0) return null
            return (refCurr.price / dailyFactor) to refCurr.price
        }
        return null
    }

    private fun holdingLabel(holding: Holding, duplicateSymbols: Set<String>): String {
        val base = holding.symbol.ifBlank { holding.name }
        if (holding.symbol.trim().uppercase() !in duplicateSymbols) return base
        val detail = holding.notes.trim().ifBlank {
            holding.name.trim().takeIf {
                it.isNotBlank() &&
                    !it.equals(holding.symbol.trim(), ignoreCase = true) &&
                    !it.equals("Letra del Tesoro ${holding.symbol.trim()}", ignoreCase = true)
            }.orEmpty()
        }
        return if (detail.isNotBlank()) "$base ($detail)" else base
    }

    /**
     * Native-cent variation between two prices. With a quantity it uses the full position;
     * without one it scales the stored current valuation (`V_hoy`, at [latestPrice]) to
     * `currPrice` and `prevPrice` (`V_curr - V_prev`), avoiding inflating the variation by
     * applying `currPrice / prevPrice - 1` on top of an already-updated end-of-day balance.
     */
    private fun holdingDailyDeltaCents(
        holding: Holding,
        prevPrice: Double,
        currPrice: Double,
        latestPrice: Double = currPrice,
    ): Long? {
        if (prevPrice <= 0.0 || currPrice <= 0.0) return null
        return if (holding.quantity > 0.0) {
            holding.valueCentsFor(currPrice) - holding.valueCentsFor(prevPrice)
        } else {
            if (holding.currentValueCents <= 0L) return null
            val refPrice = if (latestPrice > 0.0) latestPrice else currPrice
            val currValueCents = (holding.currentValueCents * (currPrice / refPrice)).roundToLong()
            val prevValueCents = (holding.currentValueCents * (prevPrice / refPrice)).roundToLong()
            currValueCents - prevValueCents
        }
    }

    private fun holdingBasisCents(
        holding: Holding,
        prevPrice: Double,
        currPrice: Double,
        latestPrice: Double = currPrice,
    ): Long = if (holding.quantity > 0.0) {
        holding.valueCentsFor(prevPrice)
    } else {
        val refPrice = if (latestPrice > 0.0) latestPrice else currPrice
        if (refPrice > 0.0 && prevPrice > 0.0) {
            (holding.currentValueCents * (prevPrice / refPrice)).roundToLong()
        } else {
            holding.currentValueCents
        }
    }

    private fun formatNative(cents: Long, currency: CurrencyCode): String =
        if (currency == CurrencyCode.USD) MoneyFormatter.usd(cents) else MoneyFormatter.ars(cents)

    private fun formatRate(tna: Double): String =
        String.format(localeAr, "%.2f%%", tna)

    private fun formatPrice(price: Double, currency: CurrencyCode): String {
        val prefix = if (currency == CurrencyCode.USD) "US$" else "$"
        val decimals = when {
            price < 20.0 -> 4
            kotlin.math.abs(price * 100.0 - kotlin.math.round(price * 100.0)) > 1e-4 -> 3
            else -> 2
        }
        return "$prefix " + String.format(localeAr, "%.${decimals}f", price)
    }

    private fun formatQty(qty: Double): String =
        if (qty % 1.0 == 0.0) {
            String.format(localeAr, "%.0f", qty)
        } else {
            String.format(localeAr, "%.2f", qty)
        }

    private fun walletFormula(wallet: Wallet, tna: Double, baseNative: Long, mep: Double): String = buildString {
        append("Base ")
        append(formatNative(baseNative, wallet.currency))
        if (wallet.remuneratedCapCents > 0 && wallet.balanceCents > wallet.remuneratedCapCents) {
            append(" (tope ")
            append(formatNative(wallet.remuneratedCapCents, wallet.currency))
            append(")")
        }
        append(" × TNA ")
        append(formatRate(tna))
        if (wallet.linkedHoldingId != null) append(" (FCI vinculado)")
        append(" ÷ 365")
        if (wallet.currency == CurrencyCode.USD) {
            append(" · MEP ")
            append(MoneyFormatter.ars((mep * 100.0).roundToLong()))
        }
    }

    private fun holdingTnaFormula(holding: Holding, tna: Double, mep: Double): String = buildString {
        append("Valuación ")
        append(formatNative(holding.currentValueCents, holding.currency))
        append(" × TNA ")
        append(formatRate(tna))
        append(" ÷ 365")
        if (holding.currency == CurrencyCode.USD) {
            append(" · MEP ")
            append(MoneyFormatter.ars((mep * 100.0).roundToLong()))
        }
    }

    private fun quoteFormula(
        holding: Holding,
        prevPrice: Double,
        currPrice: Double,
        deltaNativeCents: Long,
        mep: Double,
    ): String = buildString {
        val pct = if (prevPrice > 0.0) (currPrice / prevPrice - 1.0) * 100.0 else 0.0
        val pctSign = if (pct >= 0.0) "+" else ""
        val pctText = pctSign + String.format(localeAr, "%.2f%%", pct)
        if (holding.quantity > 0.0) {
            append(formatQty(holding.quantity))
            append(if (holding.quoteUnit == QuoteUnit.PER_100_NOMINAL) " VN (c/100) × (" else " u. × (")
            append(formatPrice(currPrice, holding.currency))
            append(" − ")
            append(formatPrice(prevPrice, holding.currency))
            append(") · ")
            append(pctText)
        } else {
            append("Valuación ")
            append(formatNative(holding.currentValueCents, holding.currency))
            append(" · ")
            append(pctText)
            append(" (")
            append(formatPrice(currPrice, holding.currency))
            append(" ÷ ")
            append(formatPrice(prevPrice, holding.currency))
            append(" − 1)")
        }
        if (holding.currency == CurrencyCode.USD) {
            val usdSign = if (deltaNativeCents >= 0L) "+" else "−"
            append(" = ")
            append(usdSign)
            append(MoneyFormatter.usd(kotlin.math.abs(deltaNativeCents)))
            append(" · MEP ")
            append(MoneyFormatter.ars((mep * 100.0).roundToLong()))
        }
    }

    private fun walletKind(type: WalletType): InterestSourceKind = when (type) {
        WalletType.MONEY_MARKET_FUND -> InterestSourceKind.MONEY_MARKET
        else -> InterestSourceKind.WALLET
    }

    private fun holdingKind(type: AssetType): InterestSourceKind = when (type) {
        AssetType.FCI -> InterestSourceKind.FCI
        AssetType.LECAP, AssetType.BONCAP -> InterestSourceKind.LECAP
        AssetType.BOND -> InterestSourceKind.BOND
        AssetType.CEDEAR -> InterestSourceKind.CEDEAR
        else -> InterestSourceKind.OTHER
    }
}
