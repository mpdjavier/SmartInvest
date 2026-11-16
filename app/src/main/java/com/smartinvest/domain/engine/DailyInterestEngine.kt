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

        // Daily compounding: every day's earnings are reinvested, so the next day's
        // base is the previous balance plus the interest already accrued in the month.
        val walletAccruedCents = mutableMapOf<Long, Long>()
        val holdingTnaAccruedCents = mutableMapOf<Long, Long>()

        // FCI prices filled forward from the last CAFCI quote. This is what makes the
        // base grow day after day instead of repeating the same one-day variation.
        val fciDailyPrices: Map<Long, Map<LocalDate, Double>> = holdings
            .filter { holding ->
                (holding.assetType == AssetType.FCI || holding.source == MarketSource.CAFCI) &&
                    holding.id !in linkedHoldingIds &&
                    (holding.tnaPercent ?: 0.0) <= 0.0 &&
                    holding.marketKey.isNotBlank()
            }
            .associate { holding ->
                holding.id to buildFciDailyPrices(
                    series = PortfolioValuationEngine.quotesFor(holding, quotes),
                    days = daysInMonth,
                )
            }

        val dayRows = daysInMonth.map { date ->
            val lines = mutableListOf<InterestLine>()

            activeWallets.forEach { wallet ->
                val tna = wallet.effectiveTnaPercent(holdings)
                val accrued = walletAccruedCents[wallet.id] ?: 0L
                val compoundedBalance = wallet.balanceCents + accrued
                val baseNative = InterestCalculator.remuneratedBalance(
                    compoundedBalance,
                    wallet.remuneratedCapCents,
                )
                val dailyNative = InterestCalculator.dailyInterestCents(
                    compoundedBalance,
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
                        explanation = walletFormula(
                            wallet = wallet,
                            tna = tna,
                            baseNative = baseNative,
                            mep = mep,
                            compounded = accrued > 0L,
                        ),
                    )
                    walletAccruedCents[wallet.id] = accrued + dailyNative
                }
            }

            holdings.forEach { rawHolding ->
                val effectiveValueCents = BrokerMovementImporter.effectiveValueCentsOnDate(
                    currentValueCents = rawHolding.currentValueCents,
                    notes = rawHolding.notes,
                    date = date,
                )
                val effectiveQuantity = BrokerMovementImporter.effectiveQuantityOnDate(
                    currentQuantity = rawHolding.quantity,
                    currentValueCents = rawHolding.currentValueCents,
                    notes = rawHolding.notes,
                    date = date,
                )
                if (effectiveValueCents <= 0L && effectiveQuantity <= 0.0) {
                    return@forEach
                }
                val holding = if (effectiveValueCents != rawHolding.currentValueCents || effectiveQuantity != rawHolding.quantity) {
                    rawHolding.copy(
                        currentValueCents = effectiveValueCents,
                        quantity = effectiveQuantity,
                    )
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
                val fciPrices = if (isFci) fciDailyPrices[holding.id] else null
                val quotePair = if (!fciPrices.isNullOrEmpty()) {
                    val previous = fciPrices[date.minusDays(1)]
                    val current = fciPrices[date]
                    if (previous != null && current != null) previous to current else null
                } else {
                    resolveQuotePairForDate(
                        holding = holding,
                        isFci = isFci,
                        linkedHoldingIds = linkedHoldingIds,
                        series = series,
                        date = date,
                    )
                }
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
                    effectiveValueCents > 0L
                ) {
                    val tna = holding.tnaPercent ?: 0.0
                    val accrued = holdingTnaAccruedCents[holding.id] ?: 0L
                    val compoundedValue = effectiveValueCents + accrued
                    val dailyNative = InterestCalculator.dailyInterestCents(compoundedValue, tna)
                    if (dailyNative != 0L) {
                        val dailyArs = toArs(dailyNative, holding.currency, mep)
                        val basisArs = toArs(compoundedValue, holding.currency, mep)
                        lines += InterestLine(
                            sourceId = "holding:${holding.id}",
                            sourceName = label,
                            kind = holdingKind(holding.assetType),
                            currency = holding.currency,
                            basisCents = basisArs,
                            tnaPercent = tna,
                            dailyNativeCents = dailyNative,
                            dailyArsCents = dailyArs,
                            explanation = holdingTnaFormula(
                                holding = holding,
                                tna = tna,
                                mep = mep,
                                basisNative = compoundedValue,
                                compounded = accrued > 0L,
                            ),
                        )
                        holdingTnaAccruedCents[holding.id] = accrued + dailyNative
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
        // Extra accrual produced by reinvesting each day's interest. It explains part of
        // the gap against the naive "first day × days" projection.
        val compounding = accrualTotal - projectedMonth
        val unexplained = differenceVsProjection - marketTotal - compounding
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
            compoundingArsCents = compounding,
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
                compounding = compounding,
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
        compounding: Long,
        unexplained: Long,
        missingSources: List<String>,
        marketOverridesTna: List<String>,
    ): List<String> = buildList {
        if (accrualSources > 0) {
            add(
                "TNA: $accrualSources ${if (accrualSources == 1) "fuente" else "fuentes"} con " +
                    "capitalización diaria. Primer día ${formatArs(accrualPerDay)} / día × " +
                    "$daysConsidered días = ${formatArs(projectedMonth)}; al reinvertir los " +
                    "intereses el acumulado suma ${formatArs(compounding)} extra.",
            )
        }
        if (marketSources > 0) {
            add(
                "Los FCI sin cotización publicada se completan día a día con el factor diario " +
                    "del último intervalo CAFCI. Así el valor de la cuotaparte y la base de " +
                    "cálculo crecen con los intereses, en lugar de repetir el mismo monto.",
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
     * Fills a day-by-day price series for an FCI from the stored CAFCI quotes.
     *
     * Real quotes anchor the series. Days without a published quote are filled with
     * the daily factor of the surrounding interval, applied **on top of the previous
     * day's price**, so the quoted value and therefore the interest base compound
     * forward instead of repeating the same one-day jump.
     */
    private fun buildFciDailyPrices(
        series: List<InstrumentQuote>,
        days: List<LocalDate>,
    ): Map<LocalDate, Double> {
        if (days.isEmpty()) return emptyMap()
        val sorted = series.filter { it.price > 0.0 }.sortedBy { it.date }
        if (sorted.size < 2) return emptyMap()
        val byDate = sorted.associateBy { it.date }
        val monthStart = days.first()
        val firstDate = sorted.first().date

        fun dailyFactor(date: LocalDate): Double {
            val nextIdx = sorted.indexOfFirst { it.date.isAfter(date) }
            val next = if (nextIdx >= 0) sorted[nextIdx] else sorted.last()
            val previous = if (nextIdx > 0) sorted[nextIdx - 1] else sorted[sorted.size - 2]
            if (previous.price <= 0.0 || next.price <= 0.0) return 1.0
            val span = ChronoUnit.DAYS.between(previous.date, next.date).coerceAtLeast(1L)
            return (next.price / previous.price).pow(1.0 / span.toDouble())
        }

        val prices = sortedMapOf<LocalDate, Double>()
        if (firstDate.isBefore(monthStart)) {
            val seed = sorted.lastOrNull { it.date.isBefore(monthStart) }?.price ?: sorted.first().price
            prices[monthStart.minusDays(1)] = seed
        }

        days.forEach { date ->
            val real = byDate[date]
            if (real != null) {
                prices[date] = real.price
            } else if (date.isAfter(firstDate)) {
                val base = prices[date.minusDays(1)]
                    ?: sorted.lastOrNull { it.date.isBefore(date) }?.price
                    ?: sorted.first().price
                prices[date] = base * dailyFactor(date)
            }
        }
        return prices
    }

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
        val tag = PortfolioReconciliationEngine.accountTag(holding)
        if (tag.isNotBlank()) return "$base ($tag)"
        val detail = holding.name.trim().takeIf {
            it.isNotBlank() &&
                !it.equals(holding.symbol.trim(), ignoreCase = true) &&
                !it.equals("Letra del Tesoro ${holding.symbol.trim()}", ignoreCase = true)
        }.orEmpty()
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

    private fun walletFormula(
        wallet: Wallet,
        tna: Double,
        baseNative: Long,
        mep: Double,
        compounded: Boolean = false,
    ): String = buildString {
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
        if (compounded) append(" · intereses reinvertidos")
        append(" ÷ 365")
        if (wallet.currency == CurrencyCode.USD) {
            append(" · MEP ")
            append(MoneyFormatter.ars((mep * 100.0).roundToLong()))
        }
    }

    private fun holdingTnaFormula(
        holding: Holding,
        tna: Double,
        mep: Double,
        basisNative: Long = holding.currentValueCents,
        compounded: Boolean = false,
    ): String = buildString {
        append("Valuación ")
        append(formatNative(basisNative, holding.currency))
        if (compounded) append(" (con intereses acumulados)")
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
