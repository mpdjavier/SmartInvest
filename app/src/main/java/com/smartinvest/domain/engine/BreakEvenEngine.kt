package com.smartinvest.domain.engine

import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.BreakEvenKind
import com.smartinvest.domain.model.BreakEvenPoint
import com.smartinvest.domain.model.BreakEvenRateSource
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.DollarBreakEvenReport
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.InstrumentBreakEven
import com.smartinvest.domain.model.PortfolioBreakEvenPoint
import com.smartinvest.domain.model.Wallet
import com.smartinvest.domain.model.effectiveTnaPercent
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Dollar break-even engine.
 *
 * For a peso position it answers: which MEP rate makes buying dollars today as
 * profitable as holding the instrument for N days?
 *
 *   instrument final ARS = position × (1 + TNA / 100 × days / 365)
 *   dollar final ARS     = position / MEP₀ × MEPₙ
 *   break-even MEPₙ      = MEP₀ × (1 + TNA / 100 × days / 365)
 *
 * The simple 365-day convention matches [InterestCalculator] and the rest of the
 * app. The only extra assumption is the configured monthly devaluation, used to
 * show the projected MEP and whether dollars already win at that expectation.
 *
 * USD positions (including CEDEARs, which move with the dollar) have no finite
 * FX break-even: both the instrument and plain dollars scale with the future
 * MEP, so the instrument wins by its own USD rate. They are returned with null
 * break-even points and a clear note.
 */
object DollarBreakEvenEngine {

    val HORIZONS: List<Int> = DollarBreakEvenReport.DEFAULT_HORIZONS

    fun calculate(
        wallets: List<Wallet>,
        holdings: List<Holding>,
        fxRates: List<FxRate>,
        fallbackMepRate: Double,
        referenceTnaPercent: Double,
        monthlyDevaluationPercent: Double,
        horizons: List<Int> = HORIZONS,
    ): DollarBreakEvenReport {
        val (mep, estimated) = PortfolioValuationEngine.mepRate(fxRates, fallbackMepRate)
        val cleanHorizons = horizons.filter { it > 0 }.distinct().sorted()
        val devaluation = monthlyDevaluationPercent.coerceAtLeast(0.0)

        val walletItems = wallets
            .filter { it.isActive && it.balanceCents > 0L }
            .map { wallet ->
                val isDollar = wallet.currency == CurrencyCode.USD
                val ownTna = wallet.effectiveTnaPercent(holdings)
                val (rate, source) = if (isDollar) {
                    if (ownTna > 0.0) ownTna to BreakEvenRateSource.OWN_TNA
                    else 0.0 to BreakEvenRateSource.ALREADY_USD
                } else {
                    resolveRate(ownTna, referenceTnaPercent)
                }
                val valueArs = if (isDollar) (wallet.balanceCents * mep).roundToLong() else wallet.balanceCents
                val note = when {
                    isDollar -> usdNote(rate, "La billetera ya está en dólares")
                    wallet.linkedHoldingId != null && ownTna > 0.0 ->
                        "TNA del FCI vinculado: ${formatRate(ownTna)} anual."
                    source == BreakEvenRateSource.OWN_TNA ->
                        "TNA propia: ${formatRate(ownTna)} anual."
                    source == BreakEvenRateSource.REFERENCE_TNA ->
                        "Sin TNA propia; se usa la TNA de referencia de la cartera (${formatRate(rate)})."
                    else -> "Sin TNA cargada: el break-even iguala el MEP actual."
                }
                InstrumentBreakEven(
                    id = "wallet:${wallet.id}",
                    kind = BreakEvenKind.WALLET,
                    name = wallet.name,
                    detail = "${wallet.type.label} · ${wallet.currency.symbol}",
                    currency = wallet.currency,
                    valueArsCents = valueArs,
                    annualRatePercent = rate,
                    rateSource = source,
                    isDollarPosition = isDollar,
                    note = note,
                    points = pointsFor(cleanHorizons, mep, rate, devaluation, isDollar),
                )
            }

        val holdingItems = holdings
            .filter { it.currentValueCents > 0L }
            .map { holding ->
                val isDollar = PortfolioValuationEngine.isDollarExposure(holding)
                val ownTna = holding.tnaPercent ?: 0.0
                val (rate, source) = if (isDollar) {
                    if (ownTna > 0.0) ownTna to BreakEvenRateSource.OWN_TNA
                    else 0.0 to BreakEvenRateSource.ALREADY_USD
                } else {
                    resolveRate(ownTna, referenceTnaPercent)
                }
                val valueArs = if (holding.currency == CurrencyCode.USD) {
                    (holding.currentValueCents * mep).roundToLong()
                } else {
                    holding.currentValueCents
                }
                val label = holding.symbol.ifBlank { holding.name.ifBlank { holding.assetType.label } }
                val detail = buildString {
                    append(holding.assetType.label)
                    append(" · ")
                    append(holding.currency.symbol)
                    if (holding.name.isNotBlank() && holding.name != holding.symbol) {
                        append(" · ")
                        append(holding.name)
                    }
                }
                val note = when {
                    isDollar && holding.assetType == AssetType.CEDEAR ->
                        usdNote(rate, "CEDEAR: cotiza en pesos pero sigue al dólar")
                    isDollar && holding.assetType == AssetType.DOLAR_MEP ->
                        usdNote(rate, "Es dólar MEP")
                    isDollar ->
                        usdNote(rate, "El activo ya está en dólares")
                    source == BreakEvenRateSource.OWN_TNA ->
                        "TNA propia: ${formatRate(ownTna)} anual."
                    source == BreakEvenRateSource.REFERENCE_TNA ->
                        "Sin TNA propia; se usa la TNA de referencia de la cartera (${formatRate(rate)})."
                    else -> "Sin TNA cargada: el break-even iguala el MEP actual."
                }
                InstrumentBreakEven(
                    id = "holding:${holding.id}",
                    kind = BreakEvenKind.HOLDING,
                    name = label,
                    detail = detail,
                    currency = holding.currency,
                    valueArsCents = valueArs,
                    annualRatePercent = rate,
                    rateSource = source,
                    isDollarPosition = isDollar,
                    note = note,
                    points = pointsFor(cleanHorizons, mep, rate, devaluation, isDollar),
                )
            }

        val items = (walletItems + holdingItems).sortedWith(
            compareBy({ it.kind.ordinal }, { -it.valueArsCents }),
        )

        return DollarBreakEvenReport(
            mepRate = mep,
            mepIsEstimated = estimated,
            monthlyDevaluationPercent = devaluation,
            horizons = cleanHorizons,
            items = items,
            pesoSummary = pesoSummary(items, mep, devaluation, cleanHorizons),
        )
    }

    private fun resolveRate(own: Double, reference: Double): Pair<Double, BreakEvenRateSource> = when {
        own > 0.0 -> own to BreakEvenRateSource.OWN_TNA
        reference > 0.0 -> reference to BreakEvenRateSource.REFERENCE_TNA
        else -> 0.0 to BreakEvenRateSource.NO_RATE
    }

    private fun pointsFor(
        horizons: List<Int>,
        mep: Double,
        annualRatePercent: Double,
        monthlyDevaluationPercent: Double,
        isDollarPosition: Boolean,
    ): List<BreakEvenPoint> = horizons.map { days ->
        val expectedMep = mep * (1.0 + monthlyDevaluationPercent / 100.0).pow(days / 30.0)
        val horizonReturn = annualRatePercent / 100.0 * days / 365.0
        if (isDollarPosition) {
            BreakEvenPoint(
                days = days,
                breakEvenMepRate = null,
                requiredDevaluationPercent = null,
                instrumentReturnPercent = horizonReturn * 100.0,
                expectedMepRate = expectedMep,
                dollarWinsAtExpectedMep = null,
            )
        } else {
            val factor = 1.0 + horizonReturn
            val breakEven = mep * factor
            BreakEvenPoint(
                days = days,
                breakEvenMepRate = breakEven,
                requiredDevaluationPercent = (factor - 1.0) * 100.0,
                instrumentReturnPercent = horizonReturn * 100.0,
                expectedMepRate = expectedMep,
                dollarWinsAtExpectedMep = expectedMep > breakEven,
            )
        }
    }

    private fun pesoSummary(
        items: List<InstrumentBreakEven>,
        mep: Double,
        monthlyDevaluationPercent: Double,
        horizons: List<Int>,
    ): List<PortfolioBreakEvenPoint> {
        val pesoItems = items.filter { !it.isDollarPosition && it.currency == CurrencyCode.ARS }
        val total = pesoItems.sumOf { it.valueArsCents }
        if (total <= 0L) return emptyList()
        val weightedRate = pesoItems.sumOf { it.annualRatePercent * it.valueArsCents } / total
        return horizons.map { days ->
            val factor = 1.0 + weightedRate / 100.0 * days / 365.0
            PortfolioBreakEvenPoint(
                days = days,
                breakEvenMepRate = mep * factor,
                requiredDevaluationPercent = (factor - 1.0) * 100.0,
            )
        }
    }

    private fun usdNote(rate: Double, prefix: String): String = buildString {
        append(prefix)
        append(": el tipo de cambio no define el break-even")
        if (rate > 0.0) {
            append("; rinde ")
            append(formatRate(rate))
            append(" anual en dólares.")
        } else {
            append(".")
        }
    }

    private fun formatRate(rate: Double): String =
        String.format(java.util.Locale.forLanguageTag("es-AR"), "%.2f%%", rate)
}
