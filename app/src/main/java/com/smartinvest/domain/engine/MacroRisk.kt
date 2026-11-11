package com.smartinvest.domain.engine

import com.smartinvest.core.money.MoneyFormatter
import com.smartinvest.core.money.MoneyMath
import com.smartinvest.core.time.DateUtils
import com.smartinvest.domain.model.AllocationBucket
import com.smartinvest.domain.model.AppSettings
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CarryRiskAssessment
import com.smartinvest.domain.model.CarryRiskBand
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.LiquidityGuardrail
import com.smartinvest.domain.model.MacroRiskFactor
import com.smartinvest.domain.model.MaturityFlowLine
import com.smartinvest.domain.model.OrganicAllocationLeg
import com.smartinvest.domain.model.OrganicRebalancePlan
import com.smartinvest.domain.model.PortfolioValuation
import com.smartinvest.domain.model.Wallet
import com.smartinvest.domain.model.effectiveTnaPercent
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Macro carry-risk engine.
 *
 * The index measures only the macro scenario:
 *
 *   real rate (40) + FX gap level (25) + FX gap speed (10) + country risk (25)
 *
 * Portfolio deviation is deliberately excluded: it is the control variable, not
 * a market signal. It is still reported so the UI can size the flow.
 *
 * Real rate compares the reference peso TNA (weighted FCI/LECAP/wallet rate)
 * against the expected monthly official devaluation (crawling peg):
 *
 *   realMonthly = (1 + TNA / 12) / (1 + devaluation) − 1
 *
 * The FX gap is MEP vs. official from DolarAPI. Country risk level and its
 * 30-day change are manual inputs (see [AppSettings]).
 */
object MacroRiskEngine {

    private val localeAr: Locale = Locale.forLanguageTag("es-AR")

    private const val REAL_MAX_SCORE = 40
    private const val GAP_LEVEL_MAX_SCORE = 25
    private const val GAP_SPEED_MAX_SCORE = 10
    private const val COUNTRY_LEVEL_MAX_SCORE = 15
    private const val COUNTRY_TREND_MAX_SCORE = 10

    private const val COMFORTABLE_REAL_MONTHLY = 4.0
    private const val REAL_SCORE_RANGE = 5.0

    private const val CALM_FX_GAP = 5.0
    private const val TENSE_FX_GAP = 12.0
    private const val FX_GAP_SPEED_ZERO_PP = 1.0
    private const val FX_GAP_SPEED_ALERT_PP = 2.5

    private const val COUNTRY_LEVEL_FLOOR = 500.0
    private const val COUNTRY_LEVEL_CEILING = 1000.0
    private const val COUNTRY_CEILING_LEVEL = 800
    private const val COUNTRY_CEILING_CHANGE = 150

    private const val LOW_ENTER = 30
    private const val LOW_EXIT = 35
    private const val HIGH_ENTER = 66
    private const val HIGH_EXIT = 55

    private const val GREEN_MAX_PESO = 70.0

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

    /**
     * Portfolio used by the carry ratio. It excludes the buckets configured in
     * [AppSettings.carryExcludedPatterns] (by default the Mercado Pago liquidity
     * pool and the travel bucket), which are not part of the investable portfolio.
     */
    fun investableValuation(
        valuation: PortfolioValuation,
        settings: AppSettings,
    ): PortfolioValuation {
        val patterns = settings.carryExcludedPatterns
            .split(',')
            .map { it.trim().lowercase(localeAr) }
            .filter { it.isNotBlank() }
        if (patterns.isEmpty()) return valuation

        fun excluded(vararg values: String?): Boolean = values.any { value ->
            value != null && patterns.any { pattern -> value.lowercase(localeAr).contains(pattern) }
        }

        val wallets = valuation.wallets.filterNot {
            excluded(it.wallet.name, it.wallet.institution)
        }
        val holdings = valuation.holdings.filterNot {
            excluded(it.holding.name, it.holding.symbol)
        }
        if (wallets.size == valuation.wallets.size && holdings.size == valuation.holdings.size) {
            return valuation
        }

        val total = wallets.sumOf { it.valueArsCents } + holdings.sumOf { it.valueArsCents }
        val usd = wallets
            .filter { it.wallet.currency == CurrencyCode.USD }
            .sumOf { it.valueArsCents } + holdings
            .filter { PortfolioValuationEngine.isDollarExposure(it.holding) }
            .sumOf { it.valueArsCents }
        val ars = (total - usd).coerceAtLeast(0L)
        val daily = wallets.sumOf { it.dailyInterestCents } + holdings.sumOf { it.dailyInterestCents }
        val mep = valuation.mepRate.takeIf { it > 0.0 } ?: 1.0

        return valuation.copy(
            totalArsCents = total,
            totalUsdCents = (usd / mep).roundToLong(),
            arsAssetsCents = ars,
            usdAssetsArsCents = usd,
            dailyInterestCents = daily,
            monthlyInterestCents = daily * 30L,
            wallets = wallets,
            holdings = holdings,
            totalCostCents = total,
        )
    }

    /**
     * Green peso ceiling imposed by the electoral calendar.
     *
     * - Up to Feb 2027: 70%
     * - Mar–Jun 2027: 65%
     * - Jul–Nov 2027 (general election + runoff): 60%
     * - After Nov 2027: back to the normal 70%
     */
    fun electoralGreenCapPercent(date: LocalDate, enabled: Boolean): Double {
        if (!enabled) return GREEN_MAX_PESO
        return when {
            !date.isAfter(LocalDate.of(2027, 2, 28)) -> GREEN_MAX_PESO
            !date.isAfter(LocalDate.of(2027, 6, 30)) -> 65.0
            !date.isAfter(LocalDate.of(2027, 11, 30)) -> 60.0
            else -> GREEN_MAX_PESO
        }
    }

    fun assess(
        valuation: PortfolioValuation,
        fxRates: List<FxRate>,
        settings: AppSettings,
        referenceTnaPercent: Double,
        today: LocalDate = DateUtils.today(),
    ): CarryRiskAssessment {
        val scoped = investableValuation(valuation, settings)
        val target = settings.rebalanceTargetPesoPercent.toDouble().coerceIn(0.0, 100.0)
        val redTarget = settings.redTargetPesoPercent.toDouble().coerceIn(0.0, 100.0)
        val (mep, _) = PortfolioValuationEngine.mepRate(fxRates, settings.fallbackMepRate)
        val official = officialRate(fxRates) ?: mep
        val fxGap = if (official > 0.0 && mep > 0.0) {
            ((mep / official - 1.0) * 100.0).coerceAtLeast(0.0)
        } else {
            0.0
        }

        val previousGap = settings.previousFxGapPercent.takeIf { it >= 0.0 }
        val gapSpeed = previousGap
            ?.let { (fxGap - it).coerceAtLeast(0.0) }
            ?: 0.0
        val gapSpeedAlert = previousGap != null && gapSpeed >= FX_GAP_SPEED_ALERT_PP

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

        val gapLevelScore = ramp(
            value = fxGap,
            zeroAt = CALM_FX_GAP,
            fullAt = TENSE_FX_GAP,
            maxScore = GAP_LEVEL_MAX_SCORE,
        )
        val gapSpeedScore = if (previousGap == null) {
            0
        } else {
            ramp(
                value = gapSpeed,
                zeroAt = FX_GAP_SPEED_ZERO_PP,
                fullAt = FX_GAP_SPEED_ALERT_PP,
                maxScore = GAP_SPEED_MAX_SCORE,
            )
        }
        val gapScore = gapLevelScore + gapSpeedScore

        val countryLevel = settings.countryRiskPoints
        val countryChange = settings.countryRisk30dChange
        val countryConfigured = countryLevel > 0
        val countryLevelScore = if (countryConfigured) {
            ramp(
                value = countryLevel.toDouble(),
                zeroAt = COUNTRY_LEVEL_FLOOR,
                fullAt = COUNTRY_LEVEL_CEILING,
                maxScore = COUNTRY_LEVEL_MAX_SCORE,
            )
        } else {
            0
        }
        val countryTrendScore = if (countryConfigured) {
            ramp(
                value = countryChange.toDouble(),
                zeroAt = 0.0,
                fullAt = COUNTRY_CEILING_CHANGE.toDouble(),
                maxScore = COUNTRY_TREND_MAX_SCORE,
            )
        } else {
            0
        }
        val countryScore = countryLevelScore + countryTrendScore
        val countryCeiling = countryConfigured &&
            (countryLevel > COUNTRY_CEILING_LEVEL || countryChange > COUNTRY_CEILING_CHANGE)

        val score = (realScore + gapScore + countryScore).coerceIn(0, 100)

        val rawBand = when {
            score >= HIGH_ENTER -> CarryRiskBand.HIGH
            score <= LOW_ENTER -> CarryRiskBand.LOW
            else -> CarryRiskBand.MODERATE
        }
        val previousBand = settings.previousCarryBand
        val hysteresisBand = when (previousBand) {
            CarryRiskBand.HIGH -> if (score > HIGH_EXIT) CarryRiskBand.HIGH else rawBand
            CarryRiskBand.LOW -> if (score < LOW_EXIT) CarryRiskBand.LOW else rawBand
            else -> rawBand
        }
        val hysteresisApplied = hysteresisBand != rawBand

        var band = hysteresisBand
        if (countryCeiling || gapSpeedAlert) {
            band = atLeast(band, CarryRiskBand.MODERATE)
        }

        val electoralCap = electoralGreenCapPercent(today, settings.electoralCalendarEnabled)
        val riskAdjustedTarget = when (band) {
            CarryRiskBand.LOW -> minOf(GREEN_MAX_PESO, electoralCap)
            CarryRiskBand.MODERATE -> target
            CarryRiskBand.HIGH -> redTarget
        }.coerceIn(0.0, 100.0)
        val electoralCapApplied = band == CarryRiskBand.LOW && electoralCap < GREEN_MAX_PESO

        val deviation = scoped.pesoSharePercent - target
        val guardrail = liquidityGuardrail(scoped, target, settings, today)

        val suggestion = buildSuggestion(
            band = band,
            target = target,
            redTarget = redTarget,
            greenTarget = riskAdjustedTarget,
            electoralCapApplied = electoralCapApplied,
            countryCeiling = countryCeiling,
            gapSpeedAlert = gapSpeedAlert,
            countryLevel = countryLevel,
            gapSpeed = gapSpeed,
            guardrailMonths = settings.liquidityGuardrailMaxMonths,
            hasOverweight = guardrail.overweightCents > 0L,
        )

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
                maxScore = GAP_LEVEL_MAX_SCORE + GAP_SPEED_MAX_SCORE,
                detail = buildString {
                    append("Oficial ${formatFx(official)} · MEP ${formatFx(mep)} → ")
                    append("brecha ${formatPercent(fxGap, 1)} (calma <5%, atención 5–12%, ")
                    append("tensión >12%). ")
                    if (previousGap == null) {
                        append("Sin lectura previa: cargala en Ajustes para medir la velocidad.")
                    } else {
                        append("Hace 30 días ${formatPercent(previousGap, 1)} → ")
                        append("${formatSignedPp(gapSpeed)} de expansión.")
                    }
                },
            ),
            MacroRiskFactor(
                key = "country_risk",
                label = "Riesgo país",
                score = countryScore,
                maxScore = COUNTRY_LEVEL_MAX_SCORE + COUNTRY_TREND_MAX_SCORE,
                detail = if (countryConfigured) {
                    "Nivel ${countryLevel} pts (techo AMARILLO desde 800) · " +
                        "30 días ${formatSignedPoints(countryChange)} pts " +
                        "(techo desde +$COUNTRY_CEILING_CHANGE)."
                } else {
                    "Sin dato. Cargá el nivel y la variación de 30 días en Ajustes."
                },
            ),
            MacroRiskFactor(
                key = "deviation",
                label = "Desvío de cartera",
                score = 0,
                maxScore = 0,
                detail = "Cartera ${formatPercent(scoped.pesoSharePercent, 1)} vs. meta " +
                    "${formatPercent(target, 1)} → ${formatPp(deviation)}. " +
                    "No suma al índice: se usa para dimensionar el flujo.",
            ),
        )

        return CarryRiskAssessment(
            score = score,
            band = band,
            rawBand = rawBand,
            suggestion = suggestion,
            realMonthlyRatePercent = realMonthly,
            realAnnualRatePercent = realAnnual,
            fxGapPercent = fxGap,
            fxGap30dAgoPercent = previousGap,
            fxGapSpeedPp = gapSpeed,
            officialRate = official,
            mepRate = mep,
            referenceTnaPercent = referenceTnaPercent,
            monthlyDevaluationPercent = devaluation,
            pesoSharePercent = scoped.pesoSharePercent,
            deviationPp = deviation,
            riskAdjustedTargetPesoPercent = riskAdjustedTarget,
            strategicTargetPesoPercent = target,
            redTargetPesoPercent = redTarget,
            countryRiskPoints = countryLevel,
            countryRisk30dChange = countryChange,
            countryRiskCeilingActive = countryCeiling,
            fxGapSpeedCeilingActive = gapSpeedAlert,
            hysteresisApplied = hysteresisApplied,
            previousBand = previousBand,
            electoralCapPesoPercent = electoralCap,
            electoralCapApplied = electoralCapApplied,
            liquidityGuardrail = guardrail,
            factors = factors,
        )
    }

    /**
     * The overweight above [target] must sit in instruments maturing within
     * [AppSettings.liquidityGuardrailMaxMonths] months or in immediate redemption
     * (wallets / FCI T+0). Anything longer is flagged as illiquid overweight.
     */
    fun liquidityGuardrail(
        valuation: PortfolioValuation,
        target: Double,
        settings: AppSettings,
        today: LocalDate = DateUtils.today(),
    ): LiquidityGuardrail {
        val maxMonths = settings.liquidityGuardrailMaxMonths.coerceAtLeast(1)
        if (!settings.liquidityGuardrailEnabled) return LiquidityGuardrail.disabled(maxMonths)

        val total = valuation.totalArsCents.coerceAtLeast(0L)
        val pesos = valuation.arsAssetsCents.coerceIn(0L, total)
        val overweight = (pesos - total * target.coerceIn(0.0, 100.0) / 100.0)
            .roundToLong()
            .coerceAtLeast(0L)
        if (overweight <= 0L) {
            return LiquidityGuardrail(
                overweightCents = 0L,
                liquidPesoCents = 0L,
                illiquidOverweightCents = 0L,
                maxMonths = maxMonths,
                isSatisfied = true,
                detail = "Sin sobrepeso en pesos: no hay tramo por encima de la meta.",
            )
        }

        val horizon = today.plusMonths(maxMonths.toLong())
        val walletLiquid = valuation.wallets
            .filter { it.wallet.currency == CurrencyCode.ARS }
            .sumOf { it.valueArsCents }
        val holdingLiquid = valuation.holdings
            .filter {
                it.holding.currency == CurrencyCode.ARS &&
                    isLiquidForOverweight(it.holding, horizon)
            }
            .sumOf { it.valueArsCents }
        val liquid = (walletLiquid + holdingLiquid).coerceIn(0L, pesos)
        val illiquidOverweight = (overweight - liquid).coerceAtLeast(0L)
        val satisfied = illiquidOverweight == 0L

        val detail = if (satisfied) {
            "El sobrepeso de ${MoneyFormatter.ars(overweight)} está cubierto por " +
                "${MoneyFormatter.ars(liquid)} en instrumentos ≤$maxMonths meses o T+0/24 h."
        } else {
            "${MoneyFormatter.ars(illiquidOverweight)} del sobrepeso está en instrumentos " +
                "de más de $maxMonths meses. Pasalo a letras cortas o FCI T+0 antes de mantenerlo."
        }

        return LiquidityGuardrail(
            overweightCents = overweight,
            liquidPesoCents = liquid,
            illiquidOverweightCents = illiquidOverweight,
            maxMonths = maxMonths,
            isSatisfied = satisfied,
            detail = detail,
        )
    }

    private fun isLiquidForOverweight(holding: Holding, horizon: LocalDate): Boolean {
        val maturity = holding.maturityDate
        if (maturity != null) return !maturity.isAfter(horizon)
        return holding.assetType == AssetType.FCI
    }

    private fun buildSuggestion(
        band: CarryRiskBand,
        target: Double,
        redTarget: Double,
        greenTarget: Double,
        electoralCapApplied: Boolean,
        countryCeiling: Boolean,
        gapSpeedAlert: Boolean,
        countryLevel: Int,
        gapSpeed: Double,
        guardrailMonths: Int,
        hasOverweight: Boolean,
    ): String {
        val base = when (band) {
            CarryRiskBand.LOW -> if (electoralCapApplied) {
                "Entorno favorable, pero el calendario electoral limita el estado verde a " +
                    "${formatPercent(greenTarget, 0)} en pesos."
            } else {
                "Entorno favorable para carry: se tolera hasta " +
                    "${formatPercent(greenTarget, 0)} en pesos."
            }
            CarryRiskBand.MODERATE ->
                "Mantener la base estructural ${formatPercent(target, 0)} Pesos / " +
                    "${formatPercent(100.0 - target, 0)} Dólares."
            CarryRiskBand.HIGH ->
                "Nuevos ingresos y vencimientos a cobertura en dólares hasta alcanzar el " +
                    "objetivo defensivo ${formatPercent(redTarget, 0)} / " +
                    "${formatPercent(100.0 - redTarget, 0)}."
        }
        val notes = buildList {
            if (countryCeiling && band != CarryRiskBand.HIGH) {
                add("Techo AMARILLO por riesgo país ($countryLevel pts).")
            }
            if (gapSpeedAlert && band != CarryRiskBand.HIGH) {
                add("Techo AMARILLO por velocidad de la brecha (${formatSignedPp(gapSpeed)} en 30 días).")
            }
            if (hasOverweight && band != CarryRiskBand.LOW) {
                add("El sobrepeso en pesos debe estar en instrumentos ≤$guardrailMonths meses o T+0.")
            }
        }
        return if (notes.isEmpty()) base else "$base ${notes.joinToString(" ")}"
    }

    private fun ramp(value: Double, zeroAt: Double, fullAt: Double, maxScore: Int): Int {
        if (maxScore <= 0 || fullAt <= zeroAt) return 0
        val fraction = ((value - zeroAt) / (fullAt - zeroAt)).coerceIn(0.0, 1.0)
        return (maxScore * fraction).roundToInt().coerceIn(0, maxScore)
    }

    private fun atLeast(band: CarryRiskBand, floor: CarryRiskBand): CarryRiskBand =
        if (band.ordinal >= floor.ordinal) band else floor

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

    internal fun formatSignedPp(value: Double): String = formatPp(value)

    internal fun formatSignedPoints(value: Int): String =
        (if (value >= 0) "+" else "−") + abs(value)

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
 *
 * Upcoming maturities are treated as additional organic flow: renewing them in
 * the same currency is inertia, not a decision.
 */
object OrganicRebalancePlanner {

    private val localeAr: Locale = Locale.forLanguageTag("es-AR")

    fun plan(
        valuation: PortfolioValuation,
        assessment: CarryRiskAssessment,
        nextIncomeCents: Long,
        settings: AppSettings,
        today: LocalDate = DateUtils.today(),
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

        val maturities = upcomingMaturities(
            valuation = valuation,
            assessment = assessment,
            incomePesoPercent = if (income > 0L) pesoPercent else null,
            settings = settings,
            today = today,
        )

        val headline = when {
            income <= 0L -> "Ingresá el próximo sueldo para calcular la distribución."
            dollarPercent == 100 -> "Asigná el 100% del próximo ingreso a cobertura en dólares."
            pesoPercent == 100 -> "Asigná el 100% del próximo ingreso al tramo en pesos."
            else -> "Repartí el próximo ingreso: $pesoPercent% a pesos y " +
                "$dollarPercent% a cobertura en dólares."
        }

        val detail = if (income <= 0L) {
            "El motor no vende activos: usa el flujo del próximo sueldo y los vencimientos " +
                "para acercar la cartera a la meta."
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

        val maturityToPesos = maturities.sumOf { it.toPesosCents }
        val maturityToDollars = maturities.sumOf { it.toDollarsCents }
        val upcomingMaturity = maturities.sumOf { it.amountCents }
        val gapToTargetCents = abs(target - currentPct) / 100.0 * total
        val monthsToRealign = if (income > 0L) gapToTargetCents / income else null

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
            maturityFlows = maturities,
            upcomingMaturityCents = upcomingMaturity,
            maturityToPesosCents = maturityToPesos,
            maturityToDollarsCents = maturityToDollars,
            maturityDetail = maturityDetail(
                flows = maturities,
                totalCents = upcomingMaturity,
                days = settings.maturityAlertDays,
                band = assessment.band,
                toPesos = maturityToPesos,
                toDollars = maturityToDollars,
            ),
            monthsToRealign = monthsToRealign,
        )
    }

    private fun upcomingMaturities(
        valuation: PortfolioValuation,
        assessment: CarryRiskAssessment,
        incomePesoPercent: Int?,
        settings: AppSettings,
        today: LocalDate,
    ): List<MaturityFlowLine> {
        val horizon = today.plusDays(settings.maturityAlertDays.coerceAtLeast(1).toLong())
        val pesoPercent = incomePesoPercent ?: when (assessment.band) {
            CarryRiskBand.HIGH -> 0
            else -> assessment.riskAdjustedTargetPesoPercent.roundToInt().coerceIn(0, 100)
        }
        return valuation.holdings
            .map { it.holding }
            .filter { holding ->
                val maturity = holding.maturityDate
                holding.currency == CurrencyCode.ARS &&
                    holding.currentValueCents > 0L &&
                    maturity != null &&
                    !maturity.isBefore(today) &&
                    !maturity.isAfter(horizon)
            }
            .sortedBy { it.maturityDate }
            .map { holding ->
                val maturity = requireNotNull(holding.maturityDate)
                val toPesos = (holding.currentValueCents * pesoPercent / 100.0).roundToLong()
                MaturityFlowLine(
                    symbol = holding.symbol,
                    name = holding.name,
                    maturityDate = maturity,
                    amountCents = holding.currentValueCents,
                    toPesosCents = toPesos,
                    toDollarsCents = holding.currentValueCents - toPesos,
                )
            }
    }

    private fun maturityDetail(
        flows: List<MaturityFlowLine>,
        totalCents: Long,
        days: Int,
        band: CarryRiskBand,
        toPesos: Long,
        toDollars: Long,
    ): String {
        if (flows.isEmpty()) {
            return "Sin vencimientos en los próximos $days días."
        }
        val head = "Vencen ${MoneyFormatter.ars(totalCents)} en ${flows.size} " +
            (if (flows.size == 1) "instrumento" else "instrumentos") +
            " en los próximos $days días."
        val guidance = if (band == CarryRiskBand.HIGH) {
            "En ROJO renovalos en dólares, no por inercia en pesos."
        } else {
            "Al renovarlos, seguí el reparto del semáforo: " +
                "${MoneyFormatter.ars(toPesos)} a pesos y " +
                "${MoneyFormatter.ars(toDollars)} a dólares."
        }
        return "$head $guidance"
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
            if (fci > 0L) legs += leg(AllocationBucket.CARRY_FCI, fci, income)
            if (lecap > 0L) legs += leg(AllocationBucket.FIXED_RATE, lecap, income)
        }

        if (toDollars > 0L) {
            val mepWeight = settings.salaryMepPercent.coerceAtLeast(0)
            val bondWeight = settings.salaryBondPercent.coerceAtLeast(0)
            val weights = if (mepWeight + bondWeight > 0) {
                listOf(mepWeight, bondWeight)
            } else {
                listOf(1, 1)
            }
            val parts = MoneyMath.allocateByWeights(toDollars, weights)
            val mep = parts.getOrElse(0) { 0L }
            val bond = parts.getOrElse(1) { 0L }
            if (mep > 0L) legs += leg(AllocationBucket.MEP, mep, income)
            if (bond > 0L) legs += leg(AllocationBucket.SOVEREIGN_BOND, bond, income)
        }

        return legs
    }

    private fun leg(
        bucket: AllocationBucket,
        amountCents: Long,
        income: Long,
    ): OrganicAllocationLeg {
        val label = when (bucket) {
            AllocationBucket.LIQUIDITY -> "Liquidez 24/7 (FCI billetera)"
            AllocationBucket.CARRY_FCI -> "Carry principal (FCI T+1)"
            AllocationBucket.FIXED_RATE -> "Tasa fija (LECAP / BONCAP)"
            AllocationBucket.MEP -> "Cobertura MEP"
            AllocationBucket.SOVEREIGN_BOND -> "Resguardo (AO27D)"
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
