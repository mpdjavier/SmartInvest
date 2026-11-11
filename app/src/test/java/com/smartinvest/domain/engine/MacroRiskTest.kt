package com.smartinvest.domain.engine

import com.smartinvest.domain.model.AppSettings
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CarryRiskBand
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.Holding
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
    fun `no reference tna returns zero and yields a neutral real-rate score`() {
        val settings = AppSettings(pesoReferenceTnaPercent = 0.0)
        val valuation = valuation(pesoPercent = 60.0, mep = 1_000.0, official = 1_000.0)

        val rate = MacroRiskEngine.referencePesoRate(emptyList(), emptyList(), settings)
        val assessment = MacroRiskEngine.assess(
            valuation = valuation,
            fxRates = fx(1_000.0),
            settings = settings,
            referenceTnaPercent = rate,
            today = today,
        )

        assertEquals(0.0, rate, 0.001)
        val realFactor = assessment.factors.first { it.key == "real_rate" }
        assertEquals(20, realFactor.score)
        assertEquals(0.0, assessment.realMonthlyRatePercent, 0.001)
    }

    @Test
    fun `low band allows peso overweight up to seventy percent`() {
        val settings = AppSettings(rebalanceTargetPesoPercent = 60)
        val valuation = valuation(pesoPercent = 60.0, mep = 1_000.0, official = 1_000.0)

        val assessment = MacroRiskEngine.assess(
            valuation = valuation,
            fxRates = fx(1_000.0),
            settings = settings,
            referenceTnaPercent = 60.0,
            today = today,
        )

        // real = (1 + 0,05) / 1,02 − 1 = 2,94% monthly → 8/40; no gap; no country risk
        assertEquals(8, assessment.score)
        assertEquals(CarryRiskBand.LOW, assessment.band)
        assertEquals(70.0, assessment.riskAdjustedTargetPesoPercent, 0.001)
        assertEquals(2.94, assessment.realMonthlyRatePercent, 0.01)
        assertEquals(41.6, assessment.realAnnualRatePercent, 0.2)
        assertEquals(0.0, assessment.fxGapPercent, 0.001)
        assertTrue(assessment.suggestion.contains("70%"))
    }

    @Test
    fun `moderate band keeps the strict strategic target`() {
        val settings = AppSettings(rebalanceTargetPesoPercent = 60)
        val valuation = valuation(pesoPercent = 60.0, mep = 1_100.0, official = 1_000.0)

        val assessment = MacroRiskEngine.assess(
            valuation = valuation,
            fxRates = fx(1_100.0),
            settings = settings,
            referenceTnaPercent = 30.0,
            today = today,
        )

        // real = 1,025/1,02 − 1 = 0,49% → 28/40; gap 10% → 18/25; country 0 → 46
        assertEquals(46, assessment.score)
        assertEquals(CarryRiskBand.MODERATE, assessment.band)
        assertEquals(60.0, assessment.riskAdjustedTargetPesoPercent, 0.001)
        assertEquals(10.0, assessment.fxGapPercent, 0.001)
        assertEquals(1_000.0, assessment.officialRate, 0.001)
        assertEquals(1_100.0, assessment.mepRate, 0.001)
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
        val valuation = valuation(pesoPercent = 90.0, mep = 1_500.0, official = 1_000.0)

        val assessment = MacroRiskEngine.assess(
            valuation = valuation,
            fxRates = fx(1_500.0),
            settings = settings,
            referenceTnaPercent = 10.0,
            today = today,
        )

        // real ≈ −3,97% → 40/40; gap 50% → 25/25; country 900/+100 → 19/25
        assertEquals(84, assessment.score)
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
        val lowDeviation = MacroRiskEngine.assess(
            valuation = valuation(pesoPercent = 62.0),
            fxRates = fx(1_000.0),
            settings = settings,
            referenceTnaPercent = 60.0,
            today = today,
        )
        val highDeviation = MacroRiskEngine.assess(
            valuation = valuation(pesoPercent = 95.0),
            fxRates = fx(1_000.0),
            settings = settings,
            referenceTnaPercent = 60.0,
            today = today,
        )

        assertEquals(lowDeviation.score, highDeviation.score)
        assertEquals(lowDeviation.band, highDeviation.band)
        val deviationFactor = highDeviation.factors.first { it.key == "deviation" }
        assertTrue(deviationFactor.isInformational)
        assertEquals(0, deviationFactor.maxScore)
        assertTrue(deviationFactor.detail.contains("No suma al índice"))
    }

    @Test
    fun `country risk above 800 caps the band at moderate`() {
        val settings = AppSettings(
            rebalanceTargetPesoPercent = 60,
            countryRiskPoints = 873,
            countryRisk30dChange = 291,
        )
        val valuation = valuation(pesoPercent = 60.0)

        val assessment = MacroRiskEngine.assess(
            valuation = valuation,
            fxRates = fx(1_000.0),
            settings = settings,
            referenceTnaPercent = 60.0,
            today = today,
        )

        // real 8 + gap 0 + country (11 + 10) = 29 → raw LOW, capped at MODERATE
        assertEquals(29, assessment.score)
        assertEquals(CarryRiskBand.LOW, assessment.rawBand)
        assertEquals(CarryRiskBand.MODERATE, assessment.band)
        assertTrue(assessment.countryRiskCeilingActive)
        assertTrue(assessment.suggestion.contains("Techo AMARILLO"))
    }

    @Test
    fun `gap doubling in thirty days caps the band at moderate`() {
        val settings = AppSettings(
            rebalanceTargetPesoPercent = 60,
            previousFxGapPercent = 2.5,
        )
        val valuation = valuation(pesoPercent = 60.0, mep = 1_050.0, official = 1_000.0)

        val assessment = MacroRiskEngine.assess(
            valuation = valuation,
            fxRates = fx(1_050.0),
            settings = settings,
            referenceTnaPercent = 60.0,
            today = today,
        )

        assertEquals(2.5, assessment.fxGapSpeedPp, 0.001)
        assertTrue(assessment.fxGapSpeedCeilingActive)
        assertEquals(CarryRiskBand.MODERATE, assessment.band)
        assertTrue(assessment.suggestion.contains("velocidad"))
    }

    @Test
    fun `hysteresis keeps high until the score drops below the exit threshold`() {
        val base = AppSettings(
            rebalanceTargetPesoPercent = 60,
            countryRiskPoints = 700,
            countryRisk30dChange = 100,
        )
        val valuation = valuation(pesoPercent = 60.0, mep = 1_100.0, official = 1_000.0)

        val withoutHistory = MacroRiskEngine.assess(
            valuation = valuation,
            fxRates = fx(1_100.0),
            settings = base,
            referenceTnaPercent = 30.0,
            today = today,
        )
        val withHighHistory = MacroRiskEngine.assess(
            valuation = valuation,
            fxRates = fx(1_100.0),
            settings = base.copy(previousCarryBandName = CarryRiskBand.HIGH.name),
            referenceTnaPercent = 30.0,
            today = today,
        )

        // real 28 + gap 18 + country 13 = 59: raw MODERATE, kept HIGH by hysteresis
        assertEquals(59, withoutHistory.score)
        assertEquals(CarryRiskBand.MODERATE, withoutHistory.band)
        assertEquals(CarryRiskBand.HIGH, withHighHistory.band)
        assertTrue(withHighHistory.hysteresisApplied)
    }

    @Test
    fun `hysteresis keeps low until the score reaches the exit threshold`() {
        val base = AppSettings(rebalanceTargetPesoPercent = 60)
        val valuation = valuation(pesoPercent = 60.0, mep = 1_060.0, official = 1_000.0)

        val withoutHistory = MacroRiskEngine.assess(
            valuation = valuation,
            fxRates = fx(1_060.0),
            settings = base,
            referenceTnaPercent = 30.0,
            today = today,
        )
        val withLowHistory = MacroRiskEngine.assess(
            valuation = valuation,
            fxRates = fx(1_060.0),
            settings = base.copy(previousCarryBandName = CarryRiskBand.LOW.name),
            referenceTnaPercent = 30.0,
            today = today,
        )

        // real 28 + gap 4 = 32: raw MODERATE, kept LOW by hysteresis
        assertEquals(32, withoutHistory.score)
        assertEquals(CarryRiskBand.MODERATE, withoutHistory.band)
        assertEquals(CarryRiskBand.LOW, withLowHistory.band)
        assertTrue(withLowHistory.hysteresisApplied)
    }

    @Test
    fun `electoral calendar caps the green peso target`() {
        assertEquals(70.0, MacroRiskEngine.electoralGreenCapPercent(LocalDate.of(2027, 2, 15), true), 0.001)
        assertEquals(65.0, MacroRiskEngine.electoralGreenCapPercent(LocalDate.of(2027, 4, 15), true), 0.001)
        assertEquals(60.0, MacroRiskEngine.electoralGreenCapPercent(LocalDate.of(2027, 9, 15), true), 0.001)
        assertEquals(60.0, MacroRiskEngine.electoralGreenCapPercent(LocalDate.of(2027, 11, 15), true), 0.001)
        assertEquals(70.0, MacroRiskEngine.electoralGreenCapPercent(LocalDate.of(2027, 12, 15), true), 0.001)
        assertEquals(70.0, MacroRiskEngine.electoralGreenCapPercent(LocalDate.of(2027, 9, 15), false), 0.001)

        val assessment = MacroRiskEngine.assess(
            valuation = valuation(pesoPercent = 60.0),
            fxRates = fx(1_000.0),
            settings = AppSettings(rebalanceTargetPesoPercent = 60),
            referenceTnaPercent = 60.0,
            today = LocalDate.of(2027, 4, 15),
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
}
