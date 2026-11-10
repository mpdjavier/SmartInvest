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
import com.smartinvest.domain.model.QuoteUnit
import com.smartinvest.domain.model.Wallet
import com.smartinvest.domain.model.WalletType
import com.smartinvest.domain.model.effectiveTnaPercent
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
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
        val linkedHoldingIds = wallets.filter { it.isActive }.mapNotNull { it.linkedHoldingId }.toSet()
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

            holdings.forEach { holding ->
                val series = if (holding.marketKey.isNotBlank()) {
                    PortfolioValuationEngine.quotesFor(holding, quotes)
                } else {
                    emptyList()
                }
                val idx = series.indexOfFirst { it.date == date }
                val prev = if (idx > 0) series[idx - 1] else null
                val curr = if (idx > 0) series[idx] else null
                val deltaNative = if (prev != null && curr != null) {
                    holdingDailyDeltaCents(holding, prev.price, curr.price)
                } else {
                    null
                }

                if (prev != null && curr != null && deltaNative != null) {
                    if ((holding.tnaPercent ?: 0.0) > 0.0) {
                        marketOverridesTna += holding.symbol.ifBlank { holding.name }
                    }
                    val basisNative = if (holding.quantity > 0.0) {
                        holding.valueCentsFor(prev.price)
                    } else {
                        holding.currentValueCents
                    }
                    val basisArs = toArs(basisNative, holding.currency, mep)
                    val deltaArs = toArs(deltaNative, holding.currency, mep)
                    lines += InterestLine(
                        sourceId = "holding:${holding.id}",
                        sourceName = holding.symbol.ifBlank { holding.name },
                        kind = holdingKind(holding.assetType),
                        currency = holding.currency,
                        basisCents = basisArs,
                        priceStart = prev.price,
                        priceEnd = curr.price,
                        quantity = holding.quantity,
                        quoteUnit = holding.quoteUnit,
                        dailyNativeCents = deltaNative,
                        dailyArsCents = deltaArs,
                        explanation = quoteFormula(holding, prev.price, curr.price, mep),
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
                            sourceName = holding.symbol.ifBlank { holding.name },
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
                    missingMarketSources += holding.symbol.ifBlank { holding.name }
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
     * Native-cent variation between two prices. With a quantity it uses the full position;
     * without one it applies the percentage move to the stored valuation, so holdings
     * loaded from a preset with only a value still contribute to the daily interest.
     */
    private fun holdingDailyDeltaCents(holding: Holding, prevPrice: Double, currPrice: Double): Long? {
        if (prevPrice <= 0.0) return null
        return if (holding.quantity > 0.0) {
            holding.valueCentsFor(currPrice) - holding.valueCentsFor(prevPrice)
        } else {
            if (holding.currentValueCents <= 0L) return null
            (holding.currentValueCents * (currPrice / prevPrice - 1.0)).roundToLong()
        }
    }

    private fun formatNative(cents: Long, currency: CurrencyCode): String =
        if (currency == CurrencyCode.USD) MoneyFormatter.usd(cents) else MoneyFormatter.ars(cents)

    private fun formatRate(tna: Double): String =
        String.format(localeAr, "%.2f%%", tna)

    private fun formatPrice(price: Double, currency: CurrencyCode): String {
        val prefix = if (currency == CurrencyCode.USD) "US$" else "$"
        val decimals = if (price < 20.0) 4 else 2
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

    private fun quoteFormula(holding: Holding, prevPrice: Double, currPrice: Double, mep: Double): String = buildString {
        if (holding.quantity > 0.0) {
            append(formatQty(holding.quantity))
            append(if (holding.quoteUnit == QuoteUnit.PER_100_NOMINAL) " VN (c/100) × (" else " u. × (")
            append(formatPrice(currPrice, holding.currency))
            append(" − ")
            append(formatPrice(prevPrice, holding.currency))
            append(")")
        } else {
            append("Valuación ")
            append(formatNative(holding.currentValueCents, holding.currency))
            append(" × (")
            append(formatPrice(currPrice, holding.currency))
            append(" ÷ ")
            append(formatPrice(prevPrice, holding.currency))
            append(" − 1)")
        }
        if (holding.currency == CurrencyCode.USD) {
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
