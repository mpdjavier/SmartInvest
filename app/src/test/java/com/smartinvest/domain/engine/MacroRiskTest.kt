package com.smartinvest.domain.engine

import com.smartinvest.domain.model.AppSettings
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CarryRiskBand
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.MacroMarketSnapshot
import com.smartinvest.domain.model.PortfolioValuation
import com.smartinvest.domain.model.Wallet
import com.smartinvest.domain.model.WalletType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.math.roundToLong

class MacroRiskTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 11)

    private fun wallet(
        id: Long,
        balance: Long,
        tna: Double,
        currency: CurrencyCode = CurrencyCode.ARS,
        active: Boolean = true,
        name: String = "w$id",
    ) = Wallet(
        id = id,
        name = name,
        institution = "i",
        type = WalletType.WALLET,
        currency = currency,
        balanceCents = balance,
        tnaPercent = tna,
        remuneratedCapCents = 0,
        isActive = active,
        rateSource = "test",
        rateUpdatedAt = null,
        liquidityPriority = 1,
        notes = "",
    )

    private fun holding(
        id: Long,
        assetType: AssetType,
        currency: CurrencyCode,
        value: Long,
        tna: Double? = null,
        maturity: LocalDate? = null,
    ) = Holding(
        id = id,
        symbol = "H$id",
        name = "Holding $id",
        assetType = assetType,
        currency = currency,
        currentValueCents = value,
        costCents = value,
        tnaPercent = tna,
        maturityDate = maturity,
        notes = "",
    )

    /** Builds a valuation with a precise peso share (in ARS) using a USD holding for the rest. */
    private fun valuation(
        pesoPercent: Double,
        totalArsCents: Long = 10_000_000L,
        mep: Double = 1_000.0,
        official: Double = mep,
    ): PortfolioValuation {
        val arsCents = (totalArsCents * pesoPercent / 100.0).roundToLong()
        val usdArs = totalArsCents - arsCents
        val usdCents = if (mep > 0.0) (usdArs / mep).roundToLong() else 0L
        val wallets = listOf(wallet(1, arsCents, 30.0))
        val holdings = if (usdCents > 0L) {
            listOf(holding(1, AssetType.DOLAR_MEP, CurrencyCode.USD, usdCents))
        } else {
            emptyList()
        }
        val fx = listOf(
            FxRate("oficial", "Oficial", 0.0, official, 0),
            FxRate("bolsa", "MEP", 0.0, mep, 0),
        )
        return PortfolioValuationEngine.calculate(wallets, holdings, fx, mep)
    }

    private fun fx(mep: Double, official: Double = 1_000.0) = listOf(
        FxRate("oficial", "Oficial", 0.0, official, 0),
        FxRate("bolsa", "MEP", 0.0, mep, 0),
    )

    private fun market(
        country: Int? = null,
        change: Int? = null,
        gap: Double? = null,
        gap14: Double? = null,
        blueGap: Double? = null,
        shortTna: Double? = null,
        mep: Double? = null,
        wholesale: Double? = null,
        blue: Double? = null,
        official: Double? = null,
        countryDate: String? = null,
        sources: List<String> = emptyList(),
    ) = MacroMarketSnapshot(
        countryRiskPoints = country,
        countryRisk30dChange = change,
        fxGapPercent = gap,
        fxGap14dAgoPercent = gap14,
        blueGapPercent = blueGap,
        shortTnaPercent = shortTna,
        mepRate = mep,
        wholesaleRate = wholesale,
        blueRate = blue,
        officialRate = official,
        countryRiskDate = countryDate,
        sources = sources,
    )

    private fun assess(
        settings: AppSettings = AppSettings(),
        pesoPercent: Double = 60.0,
        mep: Double = 1_000.0,
        official: Double = 1_000.0,
        tna: Double = 60.0,
        market: MacroMarketSnapshot? = null,
        trendActive: Boolean = false,
        day: LocalDate = today,
    ) = MacroRiskEngine.assess(
        valuation = valuation(pesoPercent = pesoPercent, mep = mep, official = official),
        fxRates = fx(mep, official),
        settings = settings,
        referenceTnaPercent = tna,
        market = market,
        countryRiskTrendActive = trendActive,
        today = day,
    )

    @Test
    fun `reference tna is balance weighted across ars wallets and peso holdings`() {
        val wallets = listOf(
            wallet(1, 100_000_00, 30.0),
            wallet(2, 300_000_00, 50.0),
            wallet(3, 500_000_00, 99.0, currency = CurrencyCode.USD),
            wallet(4, 200_000_00, 90.0, active = false),
        )
        val holdings = listOf(
            holding(1, AssetType.LECAP, CurrencyCode.ARS, 400_000_00, tna = 60.0),
            holding(2, AssetType.CEDEAR, CurrencyCode.ARS, 500_000_00, tna = 80.0),
        )

        val rate = MacroRiskEngine.referencePesoRate(wallets, holdings, AppSettings())

        assertEquals(52.5, rate, 0.001)
    }

    @Test
    fun `explicit reference tna overrides the weighted average`() {
        val wallets = listOf(wallet(1, 100_000_00, 30.0))
        val settings = AppSettings(pesoReferenceTnaPercent = 48.0)

        val rate = MacroRiskEngine.referencePesoRate(wallets, emptyList(), settings)

        assertEquals(48.0, rate, 0.001)
    }

    @Test
    fun `no short rate yields a neutral cushion score`() {
        val settings = AppSettings(pesoReferenceTnaPercent = 0.0)

        val rate = MacroRiskEngine.referencePesoRate(emptyList(), emptyList(), settings)
        val assessment = assess(settings = settings, tna = rate)

        assertEquals(0.0, rate, 0.001)
        val cushion = assessment.factors.first { it.key == "carry_cushion" }
        assertEquals(20, cushion.score)
        assertEquals(0.0, assessment.realMonthlyRatePercent, 0.001)
    }

    @Test
    fun `gap level uses the criterion thresholds`() {
        fun points(gap: Double): Int = assess(
            market = market(gap = gap, shortTna = 60.0),
        ).breakdown.components.first { it.key == "fx_gap" }.points

        assertEquals(0, points(4.9))
        assertEquals(15, points(5.0))
        assertEquals(15, points(12.0))
        assertEquals(30, points(12.1))
    }

    @Test
    fun `gap speed adds fifteen when the gap doubles or rises five points`() {
        fun speed(gap: Double, previous: Double): Int = assess(
            market = market(gap = gap, gap14 = previous, shortTna = 60.0),
        ).breakdown.components.first { it.key == "gap_speed" }.points

        assertEquals(15, speed(6.0, 3.0))
        assertEquals(0, speed(7.0, 3.6))
        assertEquals(15, speed(7.0, 2.0))
        assertEquals(0, speed(4.0, 3.9))
    }

    @Test
    fun `manual gap from settings is used when the automatic series is missing`() {
        val assessment = assess(
            settings = AppSettings(previousFxGapPercent = 1.0),
            market = market(gap = 7.0, shortTna = 60.0),
        )

        assertEquals(1.0, assessment.fxGapPreviousPercent ?: -1.0, 0.001)
        assertEquals(15, assessment.breakdown.components.first { it.key == "gap_speed" }.points)
    }

    @Test
    fun `blue gap above five adds ten points`() {
        val assessment = assess(
            market = market(gap = 0.0, blueGap = 5.1, shortTna = 20.0),
        )

        assertEquals(10, assessment.breakdown.components.first { it.key == "blue_gap" }.points)
        // TNA 20 / 12 − crawl 1 = 0,67 → 10 points.
        assertEquals(10, assessment.breakdown.components.first { it.key == "carry_cushion" }.points)
        assertEquals(20, assessment.score)
    }

    @Test
    fun `carry cushion follows the monthly thresholds`() {
        fun cushion(tna: Double): Int = assess(
            market = market(gap = 0.0, shortTna = tna),
        ).breakdown.components.first { it.key == "carry_cushion" }.points

        assertEquals(0, cushion(30.0))   // 2,50 − 1,00 = 1,50
        assertEquals(10, cushion(24.0))  // 2,00 − 1,00 = 1,00
        assertEquals(10, cushion(18.0))  // 1,50 − 1,00 = 0,50
        assertEquals(20, cushion(12.0))  // 1,00 − 1,00 = 0,00
        assertEquals(30, cushion(6.0))   // 0,50 − 1,00 = −0,50
    }

    @Test
    fun `country risk level uses the criterion thresholds`() {
        fun points(level: Int): Int = assess(
            market = market(country = level, change = 0, gap = 0.0, shortTna = 60.0),
        ).breakdown.components.first { it.key == "country_level" }.points

        assertEquals(0, points(599))
        assertEquals(10, points(600))
        assertEquals(10, points(800))
        assertEquals(20, points(801))
    }

    @Test
    fun `country risk trend enters at 150 and exits below 75 with hysteresis`() {
        fun active(change: Int, previousActive: Boolean): Boolean = assess(
            market = market(country = 500, change = change, gap = 0.0, shortTna = 60.0),
            trendActive = previousActive,
        ).countryRiskTrendActive

        assertTrue(active(151, false))
        assertFalse(active(100, false))
        assertTrue(active(100, true))
        assertTrue(active(75, true))
        assertFalse(active(74, true))
    }

    @Test
    fun `total score is capped at one hundred`() {
        val assessment = assess(
            market = market(
                country = 900,
                change = 200,
                gap = 13.0,
                gap14 = 2.0,
                blueGap = 6.0,
                shortTna = 6.0,
            ),
        )

        assertEquals(115, assessment.breakdown.scoreBeforeCap)
        assertEquals(100, assessment.score)
        assertEquals(CarryRiskBand.HIGH, assessment.band)
        assertTrue(assessment.breakdown.scoreCapNote != null)
        assertEquals(
            assessment.breakdown.scoreBeforeCap,
            assessment.breakdown.components.filterNot { it.isInformational }.sumOf { it.points },
        )
    }

    @Test
    fun `country risk above 800 caps the band at moderate`() {
        val assessment = assess(
            market = market(country = 873, change = 0, gap = 0.0, shortTna = 60.0),
        )

        assertEquals(20, assessment.score)
        assertEquals(CarryRiskBand.LOW, assessment.rawBand)
        assertEquals(CarryRiskBand.MODERATE, assessment.band)
        assertTrue(assessment.countryRiskCeilingActive)
        assertTrue(assessment.suggestion.contains("Techo AMARILLO"))
    }

    @Test
    fun `an active country risk trend also caps the band`() {
        val assessment = assess(
            market = market(country = 500, change = 200, gap = 0.0, shortTna = 60.0),
        )

        assertEquals(10, assessment.score)
        assertTrue(assessment.countryRiskTrendActive)
        assertEquals(CarryRiskBand.MODERATE, assessment.band)
        assertTrue(assessment.countryRiskCeilingActive)
    }

    @Test
    fun `automatic snapshot overrides manual settings`() {
        val settings = AppSettings(countryRiskPoints = 700, countryRisk30dChange = 0)
        val assessment = assess(
            settings = settings,
            market = market(
                country = 848,
                change = 176,
                gap = 4.44,
                blueGap = 4.52,
                shortTna = 21.18,
                mep = 1_628.8,
                wholesale = 1_559.5,
                blue = 1_630.0,
                official = 1_580.0,
                countryDate = "2026-11-18",
                sources = listOf("ArgentinaDatos · riesgo país"),
            ),
            mep = 1_628.8,
            official = 1_580.0,
            tna = 21.18,
        )

        assertEquals(848, assessment.countryRiskPoints)
        assertEquals(176, assessment.countryRisk30dChange)
        assertTrue(assessment.countryRiskTrendActive)
        assertTrue(assessment.countryRiskCeilingActive)
        assertEquals(40, assessment.score)
        assertEquals(CarryRiskBand.MODERATE, assessment.band)
        assertEquals(4.44, assessment.fxGapPercent, 0.001)
        assertTrue(
            assessment.breakdown.dataPoints.any { it.source.contains("ArgentinaDatos") },
        )
    }

    @Test
    fun `hysteresis keeps high until the score drops to fifty five`() {
        val settings = AppSettings(previousCarryBandName = CarryRiskBand.HIGH.name)
        val marketSnapshot = market(country = 700, change = 0, gap = 13.0, shortTna = 12.0)

        val withoutHistory = assess(
            market = marketSnapshot.copy(fxGap14dAgoPercent = 13.0),
        )
        val withHighHistory = assess(
            settings = settings,
            market = marketSnapshot.copy(fxGap14dAgoPercent = 13.0),
        )

        // 30 gap + 20 cushion + 10 country = 60: raw MODERATE, kept HIGH by hysteresis.
        assertEquals(60, withoutHistory.score)
        assertEquals(CarryRiskBand.MODERATE, withoutHistory.band)
        assertEquals(CarryRiskBand.HIGH, withHighHistory.band)
        assertTrue(withHighHistory.hysteresisApplied)
    }

    @Test
    fun `hysteresis enters green only ten points below the threshold`() {
        val amberHistory = AppSettings(previousCarryBandName = CarryRiskBand.MODERATE.name)
        val scoreThirty = assess(
            settings = amberHistory,
            market = market(country = 600, change = 0, gap = 0.0, shortTna = 12.0),
        )
        val scoreTwenty = assess(
            settings = amberHistory,
            market = market(gap = 0.0, shortTna = 12.0),
        )

        assertEquals(30, scoreThirty.score)
        assertEquals(CarryRiskBand.MODERATE, scoreThirty.band)
        assertTrue(scoreThirty.hysteresisApplied)

        assertEquals(20, scoreTwenty.score)
        assertEquals(CarryRiskBand.LOW, scoreTwenty.band)
        assertFalse(scoreTwenty.hysteresisApplied)
    }

    @Test
    fun `low band allows peso overweight up to seventy percent`() {
        val assessment = assess(settings = AppSettings(rebalanceTargetPesoPercent = 60))

        assertEquals(0, assessment.score)
        assertEquals(CarryRiskBand.LOW, assessment.band)
        assertEquals(70.0, assessment.riskAdjustedTargetPesoPercent, 0.001)
        assertEquals(4.0, assessment.carryCushionPercent, 0.01)
        assertEquals(0.0, assessment.fxGapPercent, 0.001)
        assertTrue(assessment.suggestion.contains("70%"))
    }

    @Test
    fun `moderate band keeps the strict strategic target`() {
        val assessment = assess(
            settings = AppSettings(rebalanceTargetPesoPercent = 60),
            mep = 1_060.0,
            official = 1_000.0,
            tna = 30.0,
        )

        // Gap 6% → 15; cushion 30/12 − 1 = 1,5 → 0. Total 15 → LOW.
        assertEquals(15, assessment.score)
        assertEquals(CarryRiskBand.LOW, assessment.band)
        assertEquals(6.0, assessment.fxGapPercent, 0.001)
        assertEquals(1_000.0, assessment.officialRate, 0.001)
        assertEquals(1_060.0, assessment.mepRate, 0.001)
    }

    @Test
    fun `high band uses the explicit red objective`() {
        val settings = AppSettings(
            rebalanceTargetPesoPercent = 60,
            redTargetPesoPercent = 45,
            monthlyDevaluationPercent = 5.0,
            countryRiskPoints = 900,
            countryRisk30dChange = 100,
        )
        val assessment = assess(
            settings = settings,
            pesoPercent = 90.0,
            mep = 1_500.0,
            official = 1_000.0,
            tna = 10.0,
        )

        // Gap 50% → 30; cushion 10/12 − 5 = −4,17 → 30; country 900 → 20. Total 80.
        assertEquals(80, assessment.score)
        assertEquals(CarryRiskBand.HIGH, assessment.band)
        assertEquals(45.0, assessment.riskAdjustedTargetPesoPercent, 0.001)
        assertTrue(assessment.realMonthlyRatePercent < 0.0)
        assertTrue(assessment.suggestion.contains("dólares"))
        assertTrue(assessment.suggestion.contains("45%"))
    }

    @Test
    fun `scope exclusion removes the configured buckets from the ratio`() {
        val wallets = listOf(
            wallet(1, 6_000_000L, 30.0, name = "Mercado Pago ARS"),
            wallet(2, 4_000_000L, 30.0, name = "Bróker"),
        )
        val usd = holding(1, AssetType.DOLAR_MEP, CurrencyCode.USD, 10_000L)
        val portfolio = PortfolioValuationEngine.calculate(
            wallets = wallets,
            holdings = listOf(usd),
            fxRates = fx(1_000.0),
            fallbackMepRate = 1_000.0,
        )

        val scoped = MacroRiskEngine.investableValuation(
            valuation = portfolio,
            settings = AppSettings(carryExcludedPatterns = "Mercado Pago"),
        )

        assertEquals(50.0, portfolio.pesoSharePercent, 0.001)
        assertEquals(28.57, scoped.pesoSharePercent, 0.01)
        assertEquals(4_000_000L, scoped.arsAssetsCents)
    }

    @Test
    fun `portfolio deviation does not change the score`() {
        val settings = AppSettings(rebalanceTargetPesoPercent = 60)
        val lowDeviation = assess(settings = settings, pesoPercent = 62.0)
        val highDeviation = assess(settings = settings, pesoPercent = 95.0)

        assertEquals(lowDeviation.score, highDeviation.score)
        assertEquals(lowDeviation.band, highDeviation.band)
        val deviationFactor = highDeviation.factors.first { it.key == "deviation" }
        assertTrue(deviationFactor.isInformational)
        assertEquals(0, deviationFactor.maxScore)
        assertTrue(deviationFactor.detail.contains("No suma al índice"))
    }

    @Test
    fun `electoral calendar caps the green peso target`() {
        assertEquals(70.0, MacroRiskEngine.electoralGreenCapPercent(LocalDate.of(2027, 2, 15), true), 0.001)
        assertEquals(65.0, MacroRiskEngine.electoralGreenCapPercent(LocalDate.of(2027, 4, 15), true), 0.001)
        assertEquals(60.0, MacroRiskEngine.electoralGreenCapPercent(LocalDate.of(2027, 9, 15), true), 0.001)
        assertEquals(60.0, MacroRiskEngine.electoralGreenCapPercent(LocalDate.of(2027, 11, 15), true), 0.001)
        assertEquals(70.0, MacroRiskEngine.electoralGreenCapPercent(LocalDate.of(2027, 12, 15), true), 0.001)
        assertEquals(70.0, MacroRiskEngine.electoralGreenCapPercent(LocalDate.of(2027, 9, 15), false), 0.001)

        val assessment = assess(
            settings = AppSettings(rebalanceTargetPesoPercent = 60),
            day = LocalDate.of(2027, 4, 15),
        )

        assertEquals(CarryRiskBand.LOW, assessment.band)
        assertEquals(65.0, assessment.riskAdjustedTargetPesoPercent, 0.001)
        assertTrue(assessment.electoralCapApplied)
    }

    @Test
    fun `liquidity guardrail flags an overweight in long dated instruments`() {
        val longLecap = holding(
            id = 1,
            assetType = AssetType.LECAP,
            currency = CurrencyCode.ARS,
            value = 9_000_000L,
            tna = 60.0,
            maturity = today.plusYears(1),
        )
        val usd = holding(2, AssetType.DOLAR_MEP, CurrencyCode.USD, 1_000L)
        val longPortfolio = PortfolioValuationEngine.calculate(
            wallets = emptyList(),
            holdings = listOf(longLecap, usd),
            fxRates = fx(1_000.0),
            fallbackMepRate = 1_000.0,
        )
        val shortPortfolio = PortfolioValuationEngine.calculate(
            wallets = emptyList(),
            holdings = listOf(
                longLecap.copy(maturityDate = today.plusMonths(3)),
                usd,
            ),
            fxRates = fx(1_000.0),
            fallbackMepRate = 1_000.0,
        )

        val longGuardrail = MacroRiskEngine.liquidityGuardrail(
            valuation = longPortfolio,
            target = 60.0,
            settings = AppSettings(),
            today = today,
        )
        val shortGuardrail = MacroRiskEngine.liquidityGuardrail(
            valuation = shortPortfolio,
            target = 60.0,
            settings = AppSettings(),
            today = today,
        )

        assertEquals(3_000_000L, longGuardrail.overweightCents)
        assertEquals(3_000_000L, longGuardrail.illiquidOverweightCents)
        assertFalse(longGuardrail.isSatisfied)
        assertTrue(shortGuardrail.isSatisfied)
        assertEquals(0L, shortGuardrail.illiquidOverweightCents)
    }

    @Test
    fun `upcoming maturities are organic flow that follows the band`() {
        val shortLecap = holding(
            id = 1,
            assetType = AssetType.LECAP,
            currency = CurrencyCode.ARS,
            value = 1_000_000L,
            tna = 60.0,
            maturity = today.plusDays(20),
        )
        val longLecap = holding(
            id = 2,
            assetType = AssetType.LECAP,
            currency = CurrencyCode.ARS,
            value = 2_000_000L,
            tna = 60.0,
            maturity = today.plusDays(60),
        )
        val usd = holding(3, AssetType.DOLAR_MEP, CurrencyCode.USD, 1_000L)
        val portfolio = PortfolioValuationEngine.calculate(
            wallets = emptyList(),
            holdings = listOf(shortLecap, longLecap, usd),
            fxRates = fx(1_000.0),
            fallbackMepRate = 1_000.0,
        )
        val settings = AppSettings(rebalanceTargetPesoPercent = 60, maturityAlertDays = 30)
        val assessment = MacroRiskEngine.assess(
            valuation = portfolio,
            fxRates = fx(1_000.0),
            settings = settings,
            referenceTnaPercent = 60.0,
            today = today,
        )

        val plan = OrganicRebalancePlanner.plan(
            valuation = portfolio,
            assessment = assessment,
            nextIncomeCents = 0L,
            settings = settings,
            today = today,
        )

        assertEquals(CarryRiskBand.LOW, assessment.band)
        assertEquals(1, plan.maturityFlows.size)
        assertEquals(1_000_000L, plan.upcomingMaturityCents)
        assertEquals(700_000L, plan.maturityToPesosCents)
        assertEquals(300_000L, plan.maturityToDollarsCents)
        assertTrue(plan.maturityDetail.contains("flujo") || plan.maturityDetail.contains("renovar"))
    }

    @Test
    fun `in red state maturities are renewed in dollars`() {
        val shortLecap = holding(
            id = 1,
            assetType = AssetType.LECAP,
            currency = CurrencyCode.ARS,
            value = 1_000_000L,
            tna = 10.0,
            maturity = today.plusDays(20),
        )
        val usd = holding(
            id = 2,
            assetType = AssetType.DOLAR_MEP,
            currency = CurrencyCode.USD,
            value = (1_000_000.0 / 1_500.0).roundToLong(),
        )
        val portfolio = PortfolioValuationEngine.calculate(
            wallets = emptyList(),
            holdings = listOf(shortLecap, usd),
            fxRates = fx(1_500.0),
            fallbackMepRate = 1_000.0,
        )
        val settings = AppSettings(
            rebalanceTargetPesoPercent = 60,
            monthlyDevaluationPercent = 5.0,
            countryRiskPoints = 900,
            countryRisk30dChange = 200,
            maturityAlertDays = 30,
        )
        val assessment = MacroRiskEngine.assess(
            valuation = portfolio,
            fxRates = fx(1_500.0),
            settings = settings,
            referenceTnaPercent = 10.0,
            today = today,
        )

        val plan = OrganicRebalancePlanner.plan(
            valuation = portfolio,
            assessment = assessment,
            nextIncomeCents = 0L,
            settings = settings,
            today = today,
        )

        assertEquals(CarryRiskBand.HIGH, assessment.band)
        assertEquals(1_000_000L, plan.upcomingMaturityCents)
        assertEquals(0L, plan.maturityToPesosCents)
        assertEquals(1_000_000L, plan.maturityToDollarsCents)
    }

    @Test
    fun `missing official quote falls back to mep and a zero gap`() {
        val valuation = valuation(pesoPercent = 60.0, mep = 1_200.0)
        val assessment = MacroRiskEngine.assess(
            valuation = valuation,
            fxRates = listOf(FxRate("bolsa", "MEP", 0.0, 1_200.0, 0)),
            settings = AppSettings(),
            referenceTnaPercent = 40.0,
            today = today,
        )

        assertEquals(1_200.0, assessment.officialRate, 0.001)
        assertEquals(0.0, assessment.fxGapPercent, 0.001)
    }

    @Test
    fun `overweight pesos sends the whole income to dollar coverage`() {
        val settings = AppSettings(rebalanceTargetPesoPercent = 60)
        val valuation = valuation(pesoPercent = 90.0, mep = 1_000.0, official = 1_000.0)
        val assessment = MacroRiskEngine.assess(
            valuation = valuation,
            fxRates = fx(1_000.0),
            settings = settings,
            referenceTnaPercent = 60.0,
            today = today,
        )
        assertEquals(CarryRiskBand.LOW, assessment.band)
        assertEquals(70.0, assessment.riskAdjustedTargetPesoPercent, 0.001)

        val plan = OrganicRebalancePlanner.plan(
            valuation = valuation,
            assessment = assessment,
            nextIncomeCents = 1_000_000L,
            settings = settings,
            today = today,
        )

        assertEquals(0L, plan.toPesosCents)
        assertEquals(1_000_000L, plan.toDollarsCents)
        assertEquals(100, plan.dollarPercentOfIncome)
        assertEquals(0, plan.pesoPercentOfIncome)
        assertTrue(plan.headline.contains("100%"))
        assertEquals(2, plan.legs.size)
        assertEquals(1_000_000L, plan.legs.sumOf { it.amountCents })
    }

    @Test
    fun `overweight dollars sends the whole income to pesos`() {
        val settings = AppSettings(rebalanceTargetPesoPercent = 60)
        val valuation = valuation(pesoPercent = 20.0, mep = 1_000.0, official = 1_000.0)
        val assessment = MacroRiskEngine.assess(
            valuation = valuation,
            fxRates = fx(1_000.0),
            settings = settings,
            referenceTnaPercent = 60.0,
            today = today,
        )

        val plan = OrganicRebalancePlanner.plan(
            valuation = valuation,
            assessment = assessment,
            nextIncomeCents = 1_000_000L,
            settings = settings,
            today = today,
        )

        assertEquals(1_000_000L, plan.toPesosCents)
        assertEquals(0L, plan.toDollarsCents)
        assertEquals(100, plan.pesoPercentOfIncome)
        assertEquals(0, plan.dollarPercentOfIncome)
        assertEquals(1_000_000L, plan.legs.sumOf { it.amountCents })
        assertTrue(plan.pesoPercentOfIncome == 100)
    }

    @Test
    fun `income split never sells assets and always preserves the total`() {
        val settings = AppSettings(
            rebalanceTargetPesoPercent = 60,
            salaryFciPercent = 60,
            salaryLecapPercent = 40,
        )
        val valuation = valuation(pesoPercent = 55.0, mep = 1_000.0, official = 1_000.0)
        val assessment = MacroRiskEngine.assess(
            valuation = valuation,
            fxRates = fx(1_000.0),
            settings = settings,
            referenceTnaPercent = 60.0,
            today = today,
        )

        val plan = OrganicRebalancePlanner.plan(
            valuation = valuation,
            assessment = assessment,
            nextIncomeCents = 2_500_000L,
            settings = settings,
            today = today,
        )

        assertEquals(2_500_000L, plan.toPesosCents + plan.toDollarsCents)
        assertEquals(2_500_000L, plan.legs.sumOf { it.amountCents })
        assertTrue(plan.toPesosCents >= 0L)
        assertTrue(plan.toDollarsCents >= 0L)
        assertTrue(plan.projectedPesoPercent >= plan.currentPesoPercent)
        assertTrue(plan.detail.contains("No se requiere vender activos"))
        assertEquals(0.6, plan.monthsToRealign ?: -1.0, 0.001)
    }

    @Test
    fun `zero income produces a neutral plan with no legs`() {
        val settings = AppSettings()
        val valuation = valuation(pesoPercent = 60.0)
        val assessment = MacroRiskEngine.assess(
            valuation = valuation,
            fxRates = fx(1_000.0),
            settings = settings,
            referenceTnaPercent = 60.0,
            today = today,
        )

        val plan = OrganicRebalancePlanner.plan(
            valuation = valuation,
            assessment = assessment,
            nextIncomeCents = 0L,
            settings = settings,
            today = today,
        )

        assertEquals(0L, plan.toPesosCents)
        assertEquals(0L, plan.toDollarsCents)
        assertTrue(plan.legs.isEmpty())
        assertEquals("Ingresá el próximo sueldo para calcular la distribución.", plan.headline)
    }

    @Test
    fun `wallet fci holding is excluded from investable carry valuation even without name match`() {
        val mpWalletFci = Holding(
            id = 10,
            symbol = "MP-ARS",
            name = "Mercado Fondo Ahorro Pesos",
            assetType = AssetType.FCI,
            currency = CurrencyCode.ARS,
            currentValueCents = 6_000_000L,
            costCents = 6_000_000L,
            tnaPercent = 30.0,
            maturityDate = null,
            notes = "",
            isWallet = true,
            settlementDays = 0,
        )
        val lecap = holding(1, AssetType.LECAP, CurrencyCode.ARS, 4_000_000L)
        val usd = holding(2, AssetType.DOLAR_MEP, CurrencyCode.USD, 10_000L)
        val portfolio = PortfolioValuationEngine.calculate(
            wallets = emptyList(),
            holdings = listOf(mpWalletFci, lecap, usd),
            fxRates = fx(1_000.0),
            fallbackMepRate = 1_000.0,
        )

        val scoped = MacroRiskEngine.investableValuation(
            valuation = portfolio,
            settings = AppSettings(carryExcludedPatterns = "Mercado Pago, viaje"),
        )

        assertEquals(4_000_000L, scoped.arsAssetsCents)
        assertEquals(28.57, scoped.pesoSharePercent, 0.01)
    }
}
