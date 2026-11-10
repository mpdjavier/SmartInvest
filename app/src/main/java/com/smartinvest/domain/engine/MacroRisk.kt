package com.smartinvest.domain.engine

import com.smartinvest.core.money.MoneyFormatter
import com.smartinvest.core.money.MoneyMath
import com.smartinvest.domain.model.AllocationBucket
import com.smartinvest.domain.model.AppSettings
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CarryRiskAssessment
import com.smartinvest.domain.model.CarryRiskBand
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.MacroRiskFactor
import com.smartinvest.domain.model.OrganicAllocationLeg
import com.smartinvest.domain.model.OrganicRebalancePlan
import com.smartinvest.domain.model.PortfolioValuation
import com.smartinvest.domain.model.Wallet
import com.smartinvest.domain.model.effectiveTnaPercent
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Macro carry-risk engine.
 *
 * Score = real-rate component (40) + FX-gap component (35) + portfolio deviation (25).
 *
 * Real rate compares the reference peso TNA (weighted FCI/LECAP/wallet rate) against
 * the expected monthly official devaluation (crawling peg):
 *
 *   realMonthly = (1 + TNA / 12) / (1 + devaluation) − 1
 *
 * The FX gap is MEP vs. official from DolarAPI. The deviation is the distance
 * between the current peso share and the strategic target.
 */
object MacroRiskEngine {

    private val localeAr: Locale = Locale.forLanguageTag("es-AR")

    private const val REAL_MAX_SCORE = 40
    private const val GAP_MAX_SCORE = 35
    private const val DEVIATION_MAX_SCORE = 25

    private const val COMFORTABLE_REAL_MONTHLY = 4.0
    private const val REAL_SCORE_RANGE = 5.0
    private const val CALM_FX_GAP = 3.0
    private const val FX_GAP_RANGE = 37.0
    private const val MAX_DEVIATION_PP = 25.0

    private val PESO_CARRY_TYPES = setOf(
        AssetType.LECAP,
        AssetType.BONCAP,
        AssetType.FCI,
        AssetType.BOND,
        AssetType.OTHER,
    )

    /**
     * Reference peso rate: the explicit setting when configured (> 0), otherwise a
     * balance-weighted average of all ARS wallets and peso carry holdings with TNA.
     */
    fun referencePesoRate(
        wallets: List<Wallet>,
        holdings: List<Holding>,
        settings: AppSettings,
    ): Double {
        if (settings.pesoReferenceTnaPercent > 0.0) return settings.pesoReferenceTnaPercent

        var weighted = 0.0
        var total = 0.0

        wallets.filter {
            it.isActive && it.currency == CurrencyCode.ARS &&
                it.effectiveTnaPercent(holdings) > 0.0 && it.balanceCents > 0L
        }.forEach { wallet ->
            weighted += wallet.effectiveTnaPercent(holdings) * wallet.balanceCents
            total += wallet.balanceCents
        }

        holdings.filter {
            it.currency == CurrencyCode.ARS &&
                it.assetType in PESO_CARRY_TYPES &&
                (it.tnaPercent ?: 0.0) > 0.0 &&
                it.currentValueCents > 0L
        }.forEach { holding ->
            val tna = holding.tnaPercent ?: 0.0
            weighted += tna * holding.currentValueCents
            total += holding.currentValueCents
        }

        return if (total > 0.0) weighted / total else 0.0
    }

    fun officialRate(rates: List<FxRate>): Double? =
        rates.firstOrNull { it.house.equals("oficial", ignoreCase = true) }
            ?.sell
            ?.takeIf { it > 0.0 }

    fun assess(
        valuation: PortfolioValuation,
        fxRates: List<FxRate>,
        settings: AppSettings,
        referenceTnaPercent: Double,
    ): CarryRiskAssessment {
        val target = settings.rebalanceTargetPesoPercent.toDouble().coerceIn(0.0, 100.0)
        val (mep, _) = PortfolioValuationEngine.mepRate(fxRates, settings.fallbackMepRate)
        val official = officialRate(fxRates) ?: mep
        val fxGap = if (official > 0.0 && mep > 0.0) {
            ((mep / official - 1.0) * 100.0).coerceAtLeast(0.0)
        } else {
            0.0
        }

        val hasReference = referenceTnaPercent > 0.0
        val devaluation = settings.monthlyDevaluationPercent.coerceAtLeast(0.0)
        val nominalMonthly = referenceTnaPercent / 12.0 / 100.0
        val devaluationBase = 1.0 + devaluation / 100.0
        val realMonthly = if (hasReference && devaluationBase > 0.0) {
            ((1.0 + nominalMonthly) / devaluationBase - 1.0) * 100.0
        } else {
            0.0
        }
        val realAnnual = if (hasReference) {
            ((1.0 + realMonthly / 100.0).pow(12.0) - 1.0) * 100.0
        } else {
            0.0
        }

        val realScore = if (hasReference) {
            (REAL_MAX_SCORE * (COMFORTABLE_REAL_MONTHLY - realMonthly) / REAL_SCORE_RANGE)
                .roundToInt()
                .coerceIn(0, REAL_MAX_SCORE)
        } else {
            20
        }
        val gapScore = (GAP_MAX_SCORE * (fxGap - CALM_FX_GAP) / FX_GAP_RANGE)
            .roundToInt()
            .coerceIn(0, GAP_MAX_SCORE)

        val deviation = valuation.pesoSharePercent - target
        val deviationScore = (DEVIATION_MAX_SCORE * abs(deviation) / MAX_DEVIATION_PP)
            .roundToInt()
            .coerceIn(0, DEVIATION_MAX_SCORE)

        val score = (realScore + gapScore + deviationScore).coerceIn(0, 100)
        val band = when {
            score <= 30 -> CarryRiskBand.LOW
            score <= 65 -> CarryRiskBand.MODERATE
            else -> CarryRiskBand.HIGH
        }

        val riskAdjustedTarget = when (band) {
            CarryRiskBand.LOW -> maxOf(target, 70.0).coerceIn(0.0, 100.0)
            CarryRiskBand.MODERATE -> target
            CarryRiskBand.HIGH -> minOf(target, 40.0).coerceIn(0.0, 100.0)
        }

        val suggestion = when (band) {
            CarryRiskBand.LOW ->
                "Entorno favorable para Carry. Se puede tolerar una sobreexposición en pesos de hasta el 70%."
            CarryRiskBand.MODERATE ->
                "Mantener objetivo estructural estricto de ${formatPercent(target, 0)} Pesos / " +
                    "${formatPercent(100.0 - target, 0)} Dólares."
            CarryRiskBand.HIGH ->
                "Alerta de estrés macro. Canalizar nuevos ingresos a cobertura en dólares."
        }

        val factors = listOf(
            MacroRiskFactor(
                key = "real_rate",
                label = "Tasa real en pesos",
                score = realScore,
                maxScore = REAL_MAX_SCORE,
                detail = if (hasReference) {
                    "TNA ${formatPercent(referenceTnaPercent, 2)} vs. devaluación " +
                        "${formatPercent(devaluation, 1)} mensual → " +
                        "${formatSignedPercent(realMonthly, 2)} real mensual " +
                        "(${formatSignedPercent(realAnnual, 1)} anual)."
                } else {
                    "Sin TNA de referencia en pesos. Se usa un puntaje neutro (20/40)."
                },
            ),
            MacroRiskFactor(
                key = "fx_gap",
                label = "Brecha cambiaria",
                score = gapScore,
                maxScore = GAP_MAX_SCORE,
                detail = "Oficial ${formatFx(official)} · MEP ${formatFx(mep)} → " +
                    "brecha ${formatPercent(fxGap, 1)}.",
            ),
            MacroRiskFactor(
                key = "deviation",
                label = "Desvío de cartera",
                score = deviationScore,
                maxScore = DEVIATION_MAX_SCORE,
                detail = "Cartera ${formatPercent(valuation.pesoSharePercent, 1)} vs. meta " +
                    "${formatPercent(target, 1)} → ${formatPp(deviation)}.",
            ),
        )

        return CarryRiskAssessment(
            score = score,
            band = band,
            suggestion = suggestion,
            realMonthlyRatePercent = realMonthly,
            realAnnualRatePercent = realAnnual,
            fxGapPercent = fxGap,
            officialRate = official,
            mepRate = mep,
            referenceTnaPercent = referenceTnaPercent,
            monthlyDevaluationPercent = devaluation,
            pesoSharePercent = valuation.pesoSharePercent,
            deviationPp = deviation,
            riskAdjustedTargetPesoPercent = riskAdjustedTarget,
            factors = factors,
        )
    }

    internal fun formatPercent(value: Double, decimals: Int): String =
        String.format(localeAr, "%.${decimals}f%%", value)

    internal fun formatSignedPercent(value: Double, decimals: Int): String {
        val sign = if (value >= 0.0) "+" else "−"
        return sign + formatPercent(abs(value), decimals)
    }

    internal fun formatPp(value: Double): String {
        val sign = if (value >= 0.0) "+" else "−"
        return sign + String.format(localeAr, "%.1f pp", abs(value))
    }

    internal fun formatFx(rate: Double): String =
        MoneyFormatter.arsWhole((rate * 100.0).roundToLong())
}

/**
 * Organic rebalance engine.
 *
 * It never forces selling. It solves how much of the next income must go to pesos
 * so the portfolio lands on the risk-adjusted target:
 *
 *   requiredPesos = target * (currentTotal + income) − currentPesos
 *   toPesos       = requiredPesos clamped to [0, income]
 *   toDollars     = income − toPesos
 */
object OrganicRebalancePlanner {

    private val localeAr: Locale = Locale.forLanguageTag("es-AR")

    fun plan(
        valuation: PortfolioValuation,
        assessment: CarryRiskAssessment,
        nextIncomeCents: Long,
        settings: AppSettings,
    ): OrganicRebalancePlan {
        val income = nextIncomeCents.coerceAtLeast(0L)
        val total = valuation.totalArsCents.coerceAtLeast(0L)
        val currentPesos = valuation.arsAssetsCents.coerceIn(0L, total)
        val currentPct = valuation.pesoSharePercent
        val target = assessment.riskAdjustedTargetPesoPercent.coerceIn(0.0, 100.0)
        val projectedTotal = total + income

        val desiredPesos = (projectedTotal.toDouble() * target / 100.0).roundToLong()
        val toPesos = if (income <= 0L) 0L else (desiredPesos - currentPesos).coerceIn(0L, income)
        val toDollars = income - toPesos
        val projectedPct = if (projectedTotal > 0L) {
            (currentPesos + toPesos) * 100.0 / projectedTotal
        } else {
            0.0
        }

        val dollarPercent = if (income > 0L) {
            (toDollars * 100.0 / income).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
        val pesoPercent = if (income > 0L) 100 - dollarPercent else 0

        val headline = when {
            income <= 0L -> "Ingresá el próximo sueldo para calcular la distribución."
            dollarPercent == 100 -> "Asigná el 100% del próximo ingreso a cobertura en dólares."
            pesoPercent == 100 -> "Asigná el 100% del próximo ingreso al tramo en pesos."
            else -> "Repartí el próximo ingreso: $pesoPercent% a pesos y " +
                "$dollarPercent% a cobertura en dólares."
        }

        val detail = if (income <= 0L) {
            "El motor no vende activos: usa el flujo del próximo sueldo para acercar la cartera a la meta."
        } else {
            val movement = when {
                projectedPct > currentPct + 0.05 -> "subiría"
                projectedPct < currentPct - 0.05 -> "bajaría"
                else -> "se mantendría"
            }
            "Con ${MoneyFormatter.ars(income)} la cartera $movement de " +
                "${formatPercent(currentPct, 1)} a ${formatPercent(projectedPct, 1)} en pesos. " +
                "Meta ajustada por riesgo: ${formatPercent(target, 1)} " +
                "(${assessment.band.label.lowercase(localeAr)}). No se requiere vender activos."
        }

        val tolerance = settings.rebalanceTolerancePp.coerceAtLeast(1.0)
        val balanced = income > 0L && abs(projectedPct - target) <= tolerance

        return OrganicRebalancePlan(
            nextIncomeCents = income,
            currentPesoPercent = currentPct,
            strategicTargetPesoPercent = settings.rebalanceTargetPesoPercent.toDouble().coerceIn(0.0, 100.0),
            riskAdjustedTargetPesoPercent = target,
            toPesosCents = toPesos,
            toDollarsCents = toDollars,
            pesoPercentOfIncome = pesoPercent,
            dollarPercentOfIncome = dollarPercent,
            projectedPesoPercent = projectedPct,
            riskBand = assessment.band,
            headline = headline,
            detail = detail,
            legs = buildLegs(toPesos, toDollars, income, settings),
            isBalancedAfterIncome = balanced,
        )
    }

    private fun buildLegs(
        toPesos: Long,
        toDollars: Long,
        income: Long,
        settings: AppSettings,
    ): List<OrganicAllocationLeg> {
        val legs = mutableListOf<OrganicAllocationLeg>()

        if (toPesos > 0L) {
            val fciWeight = settings.salaryFciPercent.coerceAtLeast(0)
            val lecapWeight = settings.salaryLecapPercent.coerceAtLeast(0)
            val weights = if (fciWeight + lecapWeight > 0) {
                listOf(fciWeight, lecapWeight)
            } else {
                listOf(1, 1)
            }
            val parts = MoneyMath.allocateByWeights(toPesos, weights)
            val fci = parts.getOrElse(0) { 0L }
            val lecap = parts.getOrElse(1) { 0L }
            if (fci > 0L) legs += leg(AllocationBucket.FCI_T0, fci, income)
            if (lecap > 0L) legs += leg(AllocationBucket.LECAP, lecap, income)
        }

        if (toDollars > 0L) {
            legs += leg(AllocationBucket.MEP, toDollars, income)
        }

        return legs
    }

    private fun leg(
        bucket: AllocationBucket,
        amountCents: Long,
        income: Long,
    ): OrganicAllocationLeg {
        val label = when (bucket) {
            AllocationBucket.FCI_T0 -> "FCI T+0 (eco)"
            AllocationBucket.LECAP -> "LECAP / BONCAP"
            AllocationBucket.MEP -> "Dólar MEP / CEDEAR"
        }
        val percent = if (income > 0L) {
            (amountCents * 100.0 / income).roundToInt().coerceIn(0, 100)
        } else {
            0
        }
        return OrganicAllocationLeg(
            bucket = bucket,
            label = label,
            destination = bucket.destination,
            percent = percent,
            amountCents = amountCents,
        )
    }

    private fun formatPercent(value: Double, decimals: Int): String =
        String.format(localeAr, "%.${decimals}f%%", value)
}
