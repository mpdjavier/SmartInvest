package com.smartinvest.domain.engine

import com.smartinvest.core.money.MoneyFormatter
import com.smartinvest.core.money.MoneyMath
import com.smartinvest.core.time.DateUtils
import com.smartinvest.domain.model.AllocationBucket
import com.smartinvest.domain.model.AppSettings
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CarryRiskAssessment
import com.smartinvest.domain.model.CarryRiskBand
import com.smartinvest.domain.model.CarryRiskBreakdown
import com.smartinvest.domain.model.CarryRiskComponent
import com.smartinvest.domain.model.CarryRiskDataPoint
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.LiquidityGuardrail
import com.smartinvest.domain.model.MacroMarketSnapshot
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
 * Macro carry-risk engine (criterion of 11/11/2026, amendments of 19/11/2026).
 *
 * The index only uses market variables, with a theoretical maximum of 115 that is
 * capped at 100:
 *
 *   MEP gap level (30) + gap speed (15) + blue gap (10) + carry cushion (30)
 *   + country risk level (20) + country risk 30-day trend (10)
 *
 * Portfolio deviation is deliberately excluded: it is the control variable, not a
 * market signal. It is still reported so the UI can size the flow.
 *
 * The carry cushion compares the short peso TEM (median 30-day fixed-term TNA)
 * against the expected monthly crawl:
 *
 *   cushion = TNA / 12 − monthlyDevaluation
 *
 * Market inputs come from [MacroMarketSnapshot] (ArgentinaDatos) when available,
 * falling back to the manual [AppSettings] fields.
 */
object MacroRiskEngine {

    private val localeAr: Locale = Locale.forLanguageTag("es-AR")

    private const val GAP_LEVEL_MAX_SCORE = 30
    private const val GAP_SPEED_MAX_SCORE = 15
    private const val BLUE_GAP_MAX_SCORE = 10
    private const val CUSHION_MAX_SCORE = 30
    private const val COUNTRY_LEVEL_MAX_SCORE = 20
    private const val COUNTRY_TREND_MAX_SCORE = 10
    private const val TOTAL_CAP = 100

    private const val CALM_FX_GAP = 5.0
    private const val TENSE_FX_GAP = 12.0
    private const val FX_GAP_SPEED_PP = 5.0
    private const val BLUE_GAP_ALERT = 5.0

    private const val CUSHION_FULL = 1.5
    private const val CUSHION_HALF = 0.5

    private const val COUNTRY_LEVEL_LOW = 600
    private const val COUNTRY_LEVEL_HIGH = 800
    private const val COUNTRY_TREND_ENTER = 150
    private const val COUNTRY_TREND_EXIT = 75

    private const val HIGH_ENTER = 66
    private const val HIGH_EXIT = 55
    private const val LOW_ENTER = 30
    private const val LOW_EXIT_IMPROVE = 20

    private const val GREEN_MAX_PESO = 70.0

    private val PESO_CARRY_TYPES = setOf(
        AssetType.LECAP,
        AssetType.BONCAP,
        AssetType.FCI,
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
                !PortfolioValuationEngine.isDollarExposure(it) &&
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
        val hasWalletHolding = valuation.holdings.any { it.holding.isWallet }
        if (patterns.isEmpty() && !hasWalletHolding) return valuation

        fun excluded(vararg values: String?): Boolean = values.any { value ->
            value != null && patterns.any { pattern -> value.lowercase(localeAr).contains(pattern) }
        }

        val wallets = valuation.wallets.filterNot {
            excluded(it.wallet.name, it.wallet.institution)
        }
        val holdings = valuation.holdings.filterNot {
            // The wallet FCI is the transactional account that funds everyday spending,
            // so it never takes part in the carry ratio.
            it.holding.isWallet || excluded(it.holding.name, it.holding.symbol)
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
        market: MacroMarketSnapshot? = null,
        countryRiskTrendActive: Boolean = false,
        today: LocalDate = DateUtils.today(),
    ): CarryRiskAssessment {
        val scoped = investableValuation(valuation, settings)
        val target = settings.rebalanceTargetPesoPercent.toDouble().coerceIn(0.0, 100.0)
        val redTarget = settings.redTargetPesoPercent.toDouble().coerceIn(0.0, 100.0)

        val (liveMep, _) = PortfolioValuationEngine.mepRate(fxRates, settings.fallbackMepRate)
        val official = officialRate(fxRates) ?: market?.officialRate ?: liveMep
        val mep = market?.mepRate?.takeIf { it > 0.0 } ?: liveMep
        val wholesale = market?.wholesaleRate?.takeIf { it > 0.0 } ?: official
        val blue = market?.blueRate?.takeIf { it > 0.0 } ?: 0.0

        val fxGap = market?.fxGapPercent ?: if (wholesale > 0.0) {
            ((mep / wholesale - 1.0) * 100.0).coerceAtLeast(0.0)
        } else {
            0.0
        }
        val previousGap = market?.fxGap14dAgoPercent
            ?: settings.previousFxGapPercent.takeIf { it >= 0.0 }
        val gapSpeed = previousGap?.let { fxGap - it } ?: 0.0
        val blueGap = market?.blueGapPercent ?: if (blue > 0.0 && wholesale > 0.0) {
            ((blue / wholesale - 1.0) * 100.0).coerceAtLeast(0.0)
        } else {
            0.0
        }

        val shortTna = market?.shortTnaPercent?.takeIf { it > 0.0 }
            ?: referenceTnaPercent.takeIf { it > 0.0 }
        val shortTem = (shortTna ?: 0.0) / 12.0
        val devaluation = settings.monthlyDevaluationPercent.coerceAtLeast(0.0)
        val realMonthly = if (shortTna != null) {
            ((1.0 + shortTem / 100.0) / (1.0 + devaluation / 100.0) - 1.0) * 100.0
        } else {
            0.0
        }
        val realAnnual = if (shortTna != null) {
            ((1.0 + realMonthly / 100.0).pow(12.0) - 1.0) * 100.0
        } else {
            0.0
        }
        val carryCushion = if (shortTna != null) shortTem - devaluation else 0.0

        val gapLevelScore = when {
            fxGap < CALM_FX_GAP -> 0
            fxGap <= TENSE_FX_GAP -> 15
            else -> GAP_LEVEL_MAX_SCORE
        }
        val gapDoubled = previousGap != null && previousGap > 0.0 && fxGap >= previousGap * 2.0
        val gapRoseFive = previousGap != null && gapSpeed >= FX_GAP_SPEED_PP
        val gapSpeedScore = if (gapDoubled || gapRoseFive) GAP_SPEED_MAX_SCORE else 0
        val blueGapScore = if (blueGap > BLUE_GAP_ALERT) BLUE_GAP_MAX_SCORE else 0
        val cushionScore = when {
            shortTna == null -> 20
            carryCushion >= CUSHION_FULL -> 0
            carryCushion >= CUSHION_HALF -> 10
            carryCushion >= 0.0 -> 20
            else -> CUSHION_MAX_SCORE
        }

        val countryLevel = market?.countryRiskPoints ?: settings.countryRiskPoints
        val countryChange = market?.countryRisk30dChange ?: settings.countryRisk30dChange
        val countryConfigured = countryLevel > 0
        val countryLevelScore = when {
            !countryConfigured -> 0
            countryLevel < COUNTRY_LEVEL_LOW -> 0
            countryLevel <= COUNTRY_LEVEL_HIGH -> 10
            else -> COUNTRY_LEVEL_MAX_SCORE
        }
        val trendActive = countryConfigured && when {
            countryChange > COUNTRY_TREND_ENTER -> true
            countryRiskTrendActive && countryChange >= COUNTRY_TREND_EXIT -> true
            else -> false
        }
        val countryTrendScore = if (trendActive) COUNTRY_TREND_MAX_SCORE else 0
        val countryScore = countryLevelScore + countryTrendScore
        val countryCeiling = countryConfigured &&
            (countryLevel > COUNTRY_LEVEL_HIGH || trendActive)

        val scoreBeforeCap = gapLevelScore + gapSpeedScore + blueGapScore +
            cushionScore + countryScore
        val score = scoreBeforeCap.coerceAtMost(TOTAL_CAP)

        val plainBand = when {
            score >= HIGH_ENTER -> CarryRiskBand.HIGH
            score <= LOW_ENTER -> CarryRiskBand.LOW
            else -> CarryRiskBand.MODERATE
        }
        val previousBand = settings.previousCarryBand
        val hysteresisBand = when {
            previousBand == CarryRiskBand.HIGH ->
                if (score > HIGH_EXIT) CarryRiskBand.HIGH else plainBand
            previousBand == CarryRiskBand.MODERATE && score <= LOW_ENTER ->
                if (score <= LOW_EXIT_IMPROVE) CarryRiskBand.LOW else CarryRiskBand.MODERATE
            previousBand == CarryRiskBand.LOW && score <= LOW_ENTER -> CarryRiskBand.LOW
            else -> plainBand
        }
        val hysteresisApplied = hysteresisBand != plainBand

        var band = hysteresisBand
        if (countryCeiling) band = atLeast(band, CarryRiskBand.MODERATE)

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
            countryLevel = countryLevel,
            trendActive = trendActive,
            countryChange = countryChange,
            gapSpeedAlert = gapSpeedScore > 0,
            guardrailMonths = settings.liquidityGuardrailMaxMonths,
            hasOverweight = guardrail.overweightCents > 0L,
        )

        val gapSpeedDetail = if (previousGap == null) {
            "Sin lectura de 14 días atrás: no se puede medir la velocidad."
        } else {
            "Hace 14 días ${formatPercent(previousGap, 1)} → " +
                "${formatSignedPp(gapSpeed)} en dos semanas" +
                if (gapDoubled) " (se duplicó)." else "."
        }
        val factors = listOf(
            MacroRiskFactor(
                key = "fx_gap",
                label = "Brecha MEP",
                score = gapLevelScore,
                maxScore = GAP_LEVEL_MAX_SCORE,
                detail = "MEP ${formatFx(mep)} ÷ mayorista ${formatFx(wholesale)} → " +
                    "brecha ${formatPercent(fxGap, 1)} (0 si <5%; 15 si 5–12%; " +
                    "30 si >12%).",
            ),
            MacroRiskFactor(
                key = "gap_speed",
                label = "Velocidad de la brecha (14 días)",
                score = gapSpeedScore,
                maxScore = GAP_SPEED_MAX_SCORE,
                detail = gapSpeedDetail,
            ),
            MacroRiskFactor(
                key = "blue_gap",
                label = "Brecha del blue",
                score = blueGapScore,
                maxScore = BLUE_GAP_MAX_SCORE,
                detail = "Blue ${formatFx(blue)} ÷ mayorista → " +
                    "${formatPercent(blueGap, 1)} · +10 si supera 5%.",
            ),
            MacroRiskFactor(
                key = "carry_cushion",
                label = "Colchón de carry",
                score = cushionScore,
                maxScore = CUSHION_MAX_SCORE,
                detail = if (shortTna != null) {
                    "TEM corta ${formatPercent(shortTem, 2)} − crawl " +
                        "${formatPercent(devaluation, 1)} = " +
                        "${formatSignedPercent(carryCushion, 2)}/mes " +
                        "(0 si ≥1,5; 10 si 0,5–1,5; 20 si 0–0,5; 30 si negativo)."
                } else {
                    "Sin TNA corta en pesos. Se usa un puntaje neutro (20/30)."
                },
            ),
            MacroRiskFactor(
                key = "country_risk",
                label = "Riesgo país",
                score = countryScore,
                maxScore = COUNTRY_LEVEL_MAX_SCORE + COUNTRY_TREND_MAX_SCORE,
                detail = if (countryConfigured) {
                    "Nivel $countryLevel pts ($countryLevelScore/20) · " +
                        "30 días ${formatSignedPoints(countryChange)} pts " +
                        "($countryTrendScore/10, entrada +150, salida +75)."
                } else {
                    "Sin dato. Se puede cargar a mano en Ajustes."
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

        val components = listOf(
            CarryRiskComponent(
                key = "fx_gap",
                label = "Brecha MEP",
                points = gapLevelScore,
                maxPoints = GAP_LEVEL_MAX_SCORE,
                input = "Brecha ${formatPercent(fxGap, 2)}",
                rule = "<5% → 0 · 5–12% → 15 · >12% → 30",
                detail = "MEP ${formatFx(mep)} ÷ mayorista ${formatFx(wholesale)} − 1.",
            ),
            CarryRiskComponent(
                key = "gap_speed",
                label = "Velocidad de la brecha",
                points = gapSpeedScore,
                maxPoints = GAP_SPEED_MAX_SCORE,
                input = if (previousGap == null) {
                    "Sin dato previo"
                } else {
                    "${formatSignedPp(gapSpeed)} en 14 días"
                },
                rule = "+15 si se duplicó en 2 semanas o subió ≥5 pp",
                detail = gapSpeedDetail,
            ),
            CarryRiskComponent(
                key = "blue_gap",
                label = "Brecha del blue",
                points = blueGapScore,
                maxPoints = BLUE_GAP_MAX_SCORE,
                input = "Brecha ${formatPercent(blueGap, 2)}",
                rule = ">5% → +10; si no, 0",
                detail = "Blue ${formatFx(blue)} ÷ mayorista ${formatFx(wholesale)} − 1.",
            ),
            CarryRiskComponent(
                key = "carry_cushion",
                label = "Colchón de carry",
                points = cushionScore,
                maxPoints = CUSHION_MAX_SCORE,
                input = if (shortTna == null) {
                    "Sin dato"
                } else {
                    "${formatSignedPercent(carryCushion, 2)}/mes"
                },
                rule = "≥1,5% → 0 · 0,5–1,5% → 10 · 0–0,5% → 20 · negativo → 30",
                detail = "TEM corta ${formatPercent(shortTem, 2)} − crawl " +
                    "${formatPercent(devaluation, 1)}.",
            ),
            CarryRiskComponent(
                key = "country_level",
                label = "Riesgo país (nivel)",
                points = countryLevelScore,
                maxPoints = COUNTRY_LEVEL_MAX_SCORE,
                input = if (countryConfigured) "$countryLevel pts" else "Sin dato",
                rule = "<600 → 0 · 600–800 → 10 · >800 → 20",
                detail = "Nivel del último cierre disponible.",
            ),
            CarryRiskComponent(
                key = "country_trend",
                label = "Riesgo país (30 días)",
                points = countryTrendScore,
                maxPoints = COUNTRY_TREND_MAX_SCORE,
                input = formatSignedPoints(countryChange) + " pts",
                rule = "+10 con suba >150; se apaga recién por debajo de +75",
                detail = "Entrada en +150 y salida con histéresis en +75 " +
                    (if (trendActive) "(activo)." else "(inactivo)."),
            ),
            CarryRiskComponent(
                key = "deviation",
                label = "Desvío de cartera",
                points = 0,
                maxPoints = 0,
                input = formatPp(deviation),
                rule = "Informativo: no suma al índice",
                detail = "Cartera ${formatPercent(scoped.pesoSharePercent, 1)} vs. meta " +
                    "${formatPercent(target, 1)}. Se usa para dimensionar el flujo.",
                isInformational = true,
            ),
        )

        val marketMep = market?.mepRate != null
        val marketCountry = market?.countryRiskPoints != null
        val marketTna = market?.shortTnaPercent != null
        val dataPoints = listOf(
            CarryRiskDataPoint(
                label = "Dólar MEP",
                value = formatFx(mep),
                source = if (marketMep) "ArgentinaDatos · bolsa" else "DolarAPI / respaldo",
                asOf = market?.fxDate,
                isFallback = !marketMep,
            ),
            CarryRiskDataPoint(
                label = "Dólar mayorista",
                value = formatFx(wholesale),
                source = if (market?.wholesaleRate != null) {
                    "ArgentinaDatos · mayorista"
                } else {
                    "Dólar oficial / respaldo"
                },
                asOf = market?.fxDate,
                isFallback = market?.wholesaleRate == null,
            ),
            CarryRiskDataPoint(
                label = "Dólar blue",
                value = formatFx(blue),
                source = if (market?.blueRate != null) {
                    "ArgentinaDatos · blue"
                } else {
                    "Sin dato automático"
                },
                asOf = market?.fxDate,
                isFallback = market?.blueRate == null,
            ),
            CarryRiskDataPoint(
                label = "Riesgo país",
                value = if (countryConfigured) "$countryLevel pts" else "Sin dato",
                source = if (marketCountry) "ArgentinaDatos · riesgo país" else "Ajustes (manual)",
                asOf = market?.countryRiskDate,
                isFallback = !marketCountry,
            ),
            CarryRiskDataPoint(
                label = "Variación 30 d del riesgo país",
                value = formatSignedPoints(countryChange) + " pts",
                source = if (marketCountry) "ArgentinaDatos · riesgo país" else "Ajustes (manual)",
                asOf = market?.countryRiskDate,
                isFallback = !marketCountry,
            ),
            CarryRiskDataPoint(
                label = "TNA plazo fijo 30 d (mediana)",
                value = if (shortTna != null) formatPercent(shortTna, 2) else "Sin dato",
                source = if (marketTna) {
                    "ArgentinaDatos · BCRA"
                } else if (referenceTnaPercent > 0.0) {
                    "Cartera / TNA manual"
                } else {
                    "Sin dato automático"
                },
                asOf = market?.shortTnaDate,
                isFallback = !marketTna,
            ),
            CarryRiskDataPoint(
                label = "Crawl mensual esperado",
                value = formatPercent(devaluation, 1),
                source = "Ajustes",
            ),
        )

        val notes = buildList {
            if (scoreBeforeCap > TOTAL_CAP) {
                add("El total teórico ($scoreBeforeCap) se topa en 100.")
            }
            if (countryCeiling) {
                add(
                    "Techo AMARILLO: riesgo país > 800 o variación de 30 días activa " +
                        "(${formatSignedPoints(countryChange)} pts).",
                )
            }
            if (hysteresisApplied) {
                add(
                    "Histéresis: para mejorar de estado el índice debe caer 10 puntos " +
                        "por debajo del umbral (ROJO sale en ≤55; VERDE se entra en ≤20).",
                )
            }
            if (electoralCapApplied) {
                add(
                    "Calendario electoral: techo VERDE de " +
                        "${formatPercent(electoralCap, 0)} en pesos.",
                )
            }
            if (market == null || !market.hasAnyData) {
                add("Sin datos automáticos: se usan los valores manuales de Ajustes.")
            } else if (market.sources.isNotEmpty()) {
                add("Fuentes: ${market.sources.joinToString(" · ")}.")
            }
        }

        return CarryRiskAssessment(
            score = score,
            band = band,
            rawBand = plainBand,
            suggestion = suggestion,
            realMonthlyRatePercent = realMonthly,
            realAnnualRatePercent = realAnnual,
            carryCushionPercent = carryCushion,
            shortTemPercent = shortTem,
            fxGapPercent = fxGap,
            fxGapPreviousPercent = previousGap,
            fxGapSpeedPp = gapSpeed,
            blueGapPercent = blueGap,
            officialRate = official,
            mepRate = mep,
            wholesaleRate = wholesale,
            blueRate = blue,
            referenceTnaPercent = shortTna ?: 0.0,
            monthlyDevaluationPercent = devaluation,
            pesoSharePercent = scoped.pesoSharePercent,
            deviationPp = deviation,
            riskAdjustedTargetPesoPercent = riskAdjustedTarget,
            strategicTargetPesoPercent = target,
            redTargetPesoPercent = redTarget,
            countryRiskPoints = countryLevel,
            countryRisk30dChange = countryChange,
            countryRiskDate = market?.countryRiskDate,
            countryRiskTrendActive = trendActive,
            countryRiskCeilingActive = countryCeiling,
            hysteresisApplied = hysteresisApplied,
            previousBand = previousBand,
            electoralCapPesoPercent = electoralCap,
            electoralCapApplied = electoralCapApplied,
            liquidityGuardrail = guardrail,
            factors = factors,
            marketFetchedAtMillis = market?.fetchedAtMillis?.takeIf { it > 0L },
            breakdown = CarryRiskBreakdown(
                components = components,
                dataPoints = dataPoints,
                scoreBeforeCap = scoreBeforeCap,
                scoreCapNote = if (scoreBeforeCap > TOTAL_CAP) {
                    "El máximo teórico es 115; el índice se topa en 100."
                } else {
                    null
                },
                notes = notes,
            ),
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
                !PortfolioValuationEngine.isDollarExposure(it.holding) &&
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
        countryLevel: Int,
        trendActive: Boolean,
        countryChange: Int,
        gapSpeedAlert: Boolean,
        guardrailMonths: Int,
        hasOverweight: Boolean,
    ): String {
        val base = when (band) {
            CarryRiskBand.LOW -> if (electoralCapApplied) {
                "Entorno favorable, pero el calendario electoral limita el estado verde a " +
                    "${formatPercent(greenTarget, 0)} en pesos."
            } else {
                "Entorno favorable para carry: se tolera hasta " +
                    "${formatPercent(greenTarget, 0)} en pesos en instrumentos ≤$guardrailMonths " +
                    "meses o T+0."
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
                val trend = if (trendActive) {
                    " · variación 30 días ${formatSignedPoints(countryChange)} pts activa"
                } else {
                    ""
                }
                add("Techo AMARILLO por riesgo país ($countryLevel pts$trend).")
            }
            if (gapSpeedAlert) {
                add("La brecha sumó +15 por velocidad (se duplicó o subió ≥5 pp en 2 semanas).")
            }
            if (hasOverweight && band != CarryRiskBand.LOW) {
                add("El sobrepeso en pesos debe estar en instrumentos ≤$guardrailMonths meses o T+0.")
            }
        }
        return if (notes.isEmpty()) base else "$base ${notes.joinToString(" ")}"
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
                !PortfolioValuationEngine.isDollarExposure(holding) &&
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
