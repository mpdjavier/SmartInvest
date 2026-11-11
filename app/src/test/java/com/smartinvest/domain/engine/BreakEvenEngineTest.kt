package com.smartinvest.domain.engine

import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.BreakEvenRateSource
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.Wallet
import com.smartinvest.domain.model.WalletType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakEvenEngineTest {

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
        symbol: String = "H$id",
    ) = Holding(
        id = id,
        symbol = symbol,
        name = "Holding $id",
        assetType = assetType,
        currency = currency,
        currentValueCents = value,
        costCents = value,
        tnaPercent = tna,
        maturityDate = null,
        notes = "",
    )

    private val fx = listOf(FxRate("bolsa", "MEP", 0.0, 1_200.0, 0))

    @Test
    fun `peso wallet break-even uses tna over 365 days`() {
        val report = DollarBreakEvenEngine.calculate(
            wallets = listOf(wallet(1, 1_000_000, 36.5)),
            holdings = emptyList(),
            fxRates = fx,
            fallbackMepRate = 1_000.0,
            referenceTnaPercent = 0.0,
            monthlyDevaluationPercent = 0.0,
        )

        val item = report.wallet(1)!!
        assertEquals(BreakEvenRateSource.OWN_TNA, item.rateSource)
        assertFalse(item.isDollarPosition)
        assertEquals(1_236.0, item.point(30)!!.breakEvenMepRate!!, 0.001)
        assertEquals(3.0, item.point(30)!!.requiredDevaluationPercent!!, 0.001)
        assertEquals(1_272.0, item.point(60)!!.breakEvenMepRate!!, 0.001)
        assertEquals(6.0, item.point(60)!!.requiredDevaluationPercent!!, 0.001)
        assertEquals(1_344.0, item.point(120)!!.breakEvenMepRate!!, 0.001)
        assertEquals(12.0, item.point(120)!!.requiredDevaluationPercent!!, 0.001)
    }

    @Test
    fun `holding without tna falls back to the reference rate`() {
        val report = DollarBreakEvenEngine.calculate(
            wallets = emptyList(),
            holdings = listOf(holding(1, AssetType.LECAP, CurrencyCode.ARS, 500_000)),
            fxRates = fx,
            fallbackMepRate = 1_000.0,
            referenceTnaPercent = 60.0,
            monthlyDevaluationPercent = 0.0,
        )

        val item = report.holding(1)!!
        assertEquals(BreakEvenRateSource.REFERENCE_TNA, item.rateSource)
        assertEquals(60.0, item.annualRatePercent, 0.001)
        assertEquals(1_259.178, item.point(30)!!.breakEvenMepRate!!, 0.001)
        assertEquals(4.9315, item.point(30)!!.requiredDevaluationPercent!!, 0.001)
    }

    @Test
    fun `no rate makes the break-even equal to the current mep`() {
        val report = DollarBreakEvenEngine.calculate(
            wallets = listOf(wallet(1, 100_000, 0.0)),
            holdings = emptyList(),
            fxRates = fx,
            fallbackMepRate = 1_000.0,
            referenceTnaPercent = 0.0,
            monthlyDevaluationPercent = 0.0,
        )

        val item = report.wallet(1)!!
        assertEquals(BreakEvenRateSource.NO_RATE, item.rateSource)
        item.points.forEach { point ->
            assertEquals(1_200.0, point.breakEvenMepRate!!, 0.001)
            assertEquals(0.0, point.requiredDevaluationPercent!!, 0.001)
        }
    }

    @Test
    fun `usd positions have no fx break-even`() {
        val report = DollarBreakEvenEngine.calculate(
            wallets = listOf(wallet(1, 100_000, 5.0, currency = CurrencyCode.USD)),
            holdings = listOf(
                holding(1, AssetType.CEDEAR, CurrencyCode.ARS, 200_000),
                holding(2, AssetType.BOND, CurrencyCode.USD, 300_000, tna = 8.0),
            ),
            fxRates = fx,
            fallbackMepRate = 1_000.0,
            referenceTnaPercent = 50.0,
            monthlyDevaluationPercent = 0.0,
        )

        val usdWallet = report.wallet(1)!!
        assertTrue(usdWallet.isDollarPosition)
        assertEquals(BreakEvenRateSource.OWN_TNA, usdWallet.rateSource)
        usdWallet.points.forEach { assertNull(it.breakEvenMepRate) }

        val cedear = report.holding(1)!!
        assertTrue(cedear.isDollarPosition)
        assertEquals(BreakEvenRateSource.ALREADY_USD, cedear.rateSource)
        cedear.points.forEach { assertNull(it.breakEvenMepRate) }

        val usdBond = report.holding(2)!!
        assertTrue(usdBond.isDollarPosition)
        assertEquals(BreakEvenRateSource.OWN_TNA, usdBond.rateSource)
        assertEquals(8.0, usdBond.annualRatePercent, 0.001)
        assertEquals(0.6575, usdBond.point(30)!!.instrumentReturnPercent, 0.001)
        assertNull(usdBond.point(30)!!.breakEvenMepRate)
    }

    @Test
    fun `expected devaluation marks when dollars already win`() {
        val report = DollarBreakEvenEngine.calculate(
            wallets = listOf(wallet(1, 100_000, 30.0)),
            holdings = emptyList(),
            fxRates = fx,
            fallbackMepRate = 1_000.0,
            referenceTnaPercent = 0.0,
            monthlyDevaluationPercent = 3.0,
        )

        val point = report.wallet(1)!!.point(30)!!
        assertEquals(1_229.589, point.breakEvenMepRate!!, 0.001)
        assertEquals(1_236.0, point.expectedMepRate, 0.001)
        assertTrue(point.dollarWinsAtExpectedMep!!)
    }

    @Test
    fun `calm devaluation leaves the instrument ahead`() {
        val report = DollarBreakEvenEngine.calculate(
            wallets = listOf(wallet(1, 100_000, 30.0)),
            holdings = emptyList(),
            fxRates = fx,
            fallbackMepRate = 1_000.0,
            referenceTnaPercent = 0.0,
            monthlyDevaluationPercent = 1.0,
        )

        val point = report.wallet(1)!!.point(30)!!
        assertEquals(1_212.0, point.expectedMepRate, 0.001)
        assertFalse(point.dollarWinsAtExpectedMep!!)
    }

    @Test
    fun `peso summary is weighted by position value`() {
        val report = DollarBreakEvenEngine.calculate(
            wallets = listOf(
                wallet(1, 100_000, 60.0),
                wallet(2, 300_000, 20.0),
            ),
            holdings = listOf(holding(1, AssetType.CEDEAR, CurrencyCode.ARS, 900_000)),
            fxRates = fx,
            fallbackMepRate = 1_000.0,
            referenceTnaPercent = 0.0,
            monthlyDevaluationPercent = 0.0,
        )

        // (100k × 60 + 300k × 20) / 400k = 30% → 30d: 1.200 × (1 + 0,30 × 30/365)
        val point = report.pesoSummary.first { it.days == 30 }
        assertEquals(1_229.589, point.breakEvenMepRate!!, 0.001)
        assertEquals(2.4658, point.requiredDevaluationPercent!!, 0.001)
    }

    @Test
    fun `inactive wallets and empty balances are ignored`() {
        val report = DollarBreakEvenEngine.calculate(
            wallets = listOf(
                wallet(1, 100_000, 30.0, active = false),
                wallet(2, 0, 30.0),
            ),
            holdings = emptyList(),
            fxRates = fx,
            fallbackMepRate = 1_000.0,
            referenceTnaPercent = 0.0,
            monthlyDevaluationPercent = 0.0,
        )

        assertTrue(report.items.isEmpty())
        assertTrue(report.pesoSummary.isEmpty())
    }

    @Test
    fun `estimated mep is propagated to the report`() {
        val report = DollarBreakEvenEngine.calculate(
            wallets = listOf(wallet(1, 100_000, 30.0)),
            holdings = emptyList(),
            fxRates = emptyList(),
            fallbackMepRate = 1_000.0,
            referenceTnaPercent = 0.0,
            monthlyDevaluationPercent = 0.0,
        )

        assertTrue(report.mepIsEstimated)
        assertNotNull(report.wallet(1))
        assertEquals(1_000.0, report.mepRate, 0.001)
    }
}
