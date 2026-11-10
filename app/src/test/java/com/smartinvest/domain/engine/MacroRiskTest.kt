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
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

class MacroRiskTest {

    private fun wallet(
        id: Long,
        balance: Long,
        tna: Double,
        currency: CurrencyCode = CurrencyCode.ARS,
        active: Boolean = true,
    ) = Wallet(
        id = id,
        name = "w$id",
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
    ) = Holding(
        id = id,
        symbol = "H$id",
        name = "Holding $id",
        assetType = assetType,
        currency = currency,
        currentValueCents = value,
        costCents = value,
        tnaPercent = tna,
        maturityDate = null,
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
        val assessment = MacroRiskEngine.assess(valuation, emptyList(), settings, rate)

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
            fxRates = listOf(
                FxRate("oficial", "Oficial", 0.0, 1_000.0, 0),
                FxRate("bolsa", "MEP", 0.0, 1_000.0, 0),
            ),
            settings = settings,
            referenceTnaPercent = 60.0,
        )

        // real = (1 + 0,05) / 1,02 − 1 = 2,94% monthly → 8/40; no gap; no deviation
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
            fxRates = listOf(
                FxRate("oficial", "Oficial", 0.0, 1_000.0, 0),
                FxRate("bolsa", "MEP", 0.0, 1_100.0, 0),
            ),
            settings = settings,
            referenceTnaPercent = 30.0,
        )

        // real = 1,025/1,02 − 1 = 0,49% → 28/40; gap 10% → 7/35; deviation 0 → 35 total
        assertEquals(35, assessment.score)
        assertEquals(CarryRiskBand.MODERATE, assessment.band)
        assertEquals(60.0, assessment.riskAdjustedTargetPesoPercent, 0.001)
        assertEquals(10.0, assessment.fxGapPercent, 0.001)
        assertEquals(1_000.0, assessment.officialRate, 0.001)
        assertEquals(1_100.0, assessment.mepRate, 0.001)
    }

    @Test
    fun `high band cuts the peso target to forty percent`() {
        val settings = AppSettings(
            rebalanceTargetPesoPercent = 60,
            monthlyDevaluationPercent = 5.0,
        )
        val valuation = valuation(pesoPercent = 90.0, mep = 1_500.0, official = 1_000.0)

        val assessment = MacroRiskEngine.assess(
            valuation = valuation,
            fxRates = listOf(
                FxRate("oficial", "Oficial", 0.0, 1_000.0, 0),
                FxRate("bolsa", "MEP", 0.0, 1_500.0, 0),
            ),
            settings = settings,
            referenceTnaPercent = 10.0,
        )

        // real ≈ −3,97% → 40/40; gap 50% → 35/35; deviation 30 pp → 25/25
        assertEquals(100, assessment.score)
        assertEquals(CarryRiskBand.HIGH, assessment.band)
        assertEquals(40.0, assessment.riskAdjustedTargetPesoPercent, 0.001)
        assertTrue(assessment.realMonthlyRatePercent < 0.0)
        assertTrue(assessment.suggestion.contains("dólares"))
    }

    @Test
    fun `missing official quote falls back to mep and a zero gap`() {
        val valuation = valuation(pesoPercent = 60.0, mep = 1_200.0)
        val assessment = MacroRiskEngine.assess(
            valuation = valuation,
            fxRates = listOf(FxRate("bolsa", "MEP", 0.0, 1_200.0, 0)),
            settings = AppSettings(),
            referenceTnaPercent = 40.0,
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
            fxRates = listOf(FxRate("bolsa", "MEP", 0.0, 1_000.0, 0)),
            settings = settings,
            referenceTnaPercent = 60.0,
        )
        assertEquals(CarryRiskBand.MODERATE, assessment.band)
        assertEquals(60.0, assessment.riskAdjustedTargetPesoPercent, 0.001)

        val plan = OrganicRebalancePlanner.plan(
            valuation = valuation,
            assessment = assessment,
            nextIncomeCents = 1_000_000L,
            settings = settings,
        )

        assertEquals(0L, plan.toPesosCents)
        assertEquals(1_000_000L, plan.toDollarsCents)
        assertEquals(100, plan.dollarPercentOfIncome)
        assertEquals(0, plan.pesoPercentOfIncome)
        assertTrue(plan.headline.contains("100%"))
        assertEquals(1, plan.legs.size)
        assertEquals(1_000_000L, plan.legs.sumOf { it.amountCents })
    }

    @Test
    fun `overweight dollars sends the whole income to pesos`() {
        val settings = AppSettings(rebalanceTargetPesoPercent = 60)
        val valuation = valuation(pesoPercent = 20.0, mep = 1_000.0, official = 1_000.0)
        val assessment = MacroRiskEngine.assess(
            valuation = valuation,
            fxRates = listOf(FxRate("bolsa", "MEP", 0.0, 1_000.0, 0)),
            settings = settings,
            referenceTnaPercent = 60.0,
        )

        val plan = OrganicRebalancePlanner.plan(
            valuation = valuation,
            assessment = assessment,
            nextIncomeCents = 1_000_000L,
            settings = settings,
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
            fxRates = listOf(FxRate("bolsa", "MEP", 0.0, 1_000.0, 0)),
            settings = settings,
            referenceTnaPercent = 60.0,
        )

        val plan = OrganicRebalancePlanner.plan(
            valuation = valuation,
            assessment = assessment,
            nextIncomeCents = 2_500_000L,
            settings = settings,
        )

        assertEquals(2_500_000L, plan.toPesosCents + plan.toDollarsCents)
        assertEquals(2_500_000L, plan.legs.sumOf { it.amountCents })
        assertTrue(plan.toPesosCents >= 0L)
        assertTrue(plan.toDollarsCents >= 0L)
        assertTrue(plan.projectedPesoPercent >= plan.currentPesoPercent)
        assertTrue(plan.detail.contains("No se requiere vender activos"))
    }

    @Test
    fun `zero income produces a neutral plan with no legs`() {
        val settings = AppSettings()
        val valuation = valuation(pesoPercent = 60.0)
        val assessment = MacroRiskEngine.assess(
            valuation = valuation,
            fxRates = listOf(FxRate("bolsa", "MEP", 0.0, 1_000.0, 0)),
            settings = settings,
            referenceTnaPercent = 60.0,
        )

        val plan = OrganicRebalancePlanner.plan(
            valuation = valuation,
            assessment = assessment,
            nextIncomeCents = 0L,
            settings = settings,
        )

        assertEquals(0L, plan.toPesosCents)
        assertEquals(0L, plan.toDollarsCents)
        assertTrue(plan.legs.isEmpty())
        assertEquals("Ingresá el próximo sueldo para calcular la distribución.", plan.headline)
    }
}
