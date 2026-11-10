package com.smartinvest.domain.engine

import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.InstrumentQuote
import com.smartinvest.domain.model.MarketSource
import com.smartinvest.domain.model.QuoteUnit
import com.smartinvest.domain.model.Wallet
import com.smartinvest.domain.model.WalletType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PortfolioValuationTest {

    private fun wallet(
        id: Long,
        balance: Long,
        tna: Double,
        cap: Long = 0,
        currency: CurrencyCode = CurrencyCode.ARS,
    ) = Wallet(
        id = id,
        name = "w$id",
        institution = "i",
        type = WalletType.WALLET,
        currency = currency,
        balanceCents = balance,
        tnaPercent = tna,
        remuneratedCapCents = cap,
        isActive = true,
        rateSource = "test",
        rateUpdatedAt = null,
        liquidityPriority = 1,
        notes = "",
    )

    @Test
    fun `only balance up to the cap earns interest`() {
        assertEquals(500_000L, InterestCalculator.remuneratedBalance(800_000, 500_000))
        assertEquals(800_000L, InterestCalculator.remuneratedBalance(800_000, 0))
        assertEquals(0L, InterestCalculator.remuneratedBalance(0, 500_000))
    }

    @Test
    fun `daily interest uses tna over 365 days`() {
        // 1.000.000 cents at 36,5% TNA = 365.000 cents per year = 1.000 cents per day
        val daily = InterestCalculator.dailyInterestCents(1_000_000, 36.5)
        assertEquals(1000L, daily)
    }

    @Test
    fun `valuation converts usd holdings with mep`() {
        val wallets = listOf(wallet(1, 100_000_00, 30.0))
        val holdings = listOf(
            Holding(
                id = 1,
                symbol = "AO27D",
                name = "Bono",
                assetType = AssetType.BOND,
                currency = CurrencyCode.USD,
                currentValueCents = 1_000_00,
                costCents = 900_00,
                tnaPercent = null,
                maturityDate = null,
                notes = "",
            ),
        )
        val fx = listOf(FxRate("bolsa", "MEP", 0.0, 1_200.0, 0))

        val valuation = PortfolioValuationEngine.calculate(wallets, holdings, fx, 1_000.0)

        assertEquals(1_200.0, valuation.mepRate, 0.001)
        assertFalse(valuation.mepIsEstimated)
        assertEquals(100_000_00L, valuation.arsAssetsCents)
        assertEquals(1_200_000_00L, valuation.usdAssetsArsCents)
        assertEquals(1_300_000_00L, valuation.totalArsCents)
    }

    @Test
    fun `falls back to configured mep when api has no quote`() {
        val valuation = PortfolioValuationEngine.calculate(emptyList(), emptyList(), emptyList(), 1_100.0)
        assertEquals(1_100.0, valuation.mepRate, 0.001)
        assertTrue(valuation.mepIsEstimated)
    }

    @Test
    fun `cedear valued in ars counts as dollar exposure in rebalance without changing total`() {
        val wallets = listOf(wallet(1, 600_000_00, 30.0, currency = CurrencyCode.ARS))
        val holdings = listOf(
            Holding(
                id = 1,
                symbol = "SPY",
                name = "SPDR S&P 500 CEDEAR",
                assetType = AssetType.CEDEAR,
                currency = CurrencyCode.ARS,
                currentValueCents = 400_000_00,
                costCents = 350_000_00,
                tnaPercent = null,
                maturityDate = null,
                notes = "",
            ),
        )
        val fx = listOf(FxRate("bolsa", "MEP", 0.0, 1_000.0, 0))

        val valuation = PortfolioValuationEngine.calculate(wallets, holdings, fx, 1_000.0)

        // 600k ARS wallet + 400k ARS CEDEAR = 1M total ARS, but 60% ARS / 40% USD exposure
        assertEquals(1_000_000_00L, valuation.totalArsCents)
        assertEquals(600_000_00L, valuation.arsAssetsCents)
        assertEquals(400_000_00L, valuation.usdAssetsArsCents)
        assertEquals(60.0, valuation.pesoSharePercent, 0.001)
        assertEquals(40.0, valuation.dollarSharePercent, 0.001)
    }

    @Test
    fun `holdings with tna contribute to daily portfolio interest`() {
        val holdings = listOf(
            Holding(
                id = 1,
                symbol = "S30N6",
                name = "LECAP",
                assetType = AssetType.LECAP,
                currency = CurrencyCode.ARS,
                currentValueCents = 1_000_000_00,
                costCents = 950_000_00,
                tnaPercent = 36.5,
                maturityDate = null,
                notes = "",
            ),
        )
        val valuation = PortfolioValuationEngine.calculate(emptyList(), holdings, emptyList(), 1_000.0)
        assertEquals(1_000_00L, valuation.dailyInterestCents)
    }

    @Test
    fun `wallet takes tna from linked fci and does not double count the holding`() {
        val fci = Holding(
            id = 7,
            symbol = "MPARS",
            name = "Mercado Pago ARS",
            assetType = AssetType.FCI,
            currency = CurrencyCode.ARS,
            currentValueCents = 500_000_00,
            costCents = 500_000_00,
            tnaPercent = 36.5,
            maturityDate = null,
            notes = "",
        )
        val wallets = listOf(wallet(1, 1_000_000, 0.0).copy(linkedHoldingId = 7))

        val valuation = PortfolioValuationEngine.calculate(wallets, listOf(fci), emptyList(), 1_000.0)

        // 1.000.000 cents at 36,5% = 1.000 cents/day from the wallet only.
        assertEquals(36.5, valuation.wallets.first().effectiveTnaPercent, 0.001)
        assertEquals(1_000L, valuation.dailyInterestCents)
    }

    @Test
    fun `tracked usd bond adds latest market variation to daily interest`() {
        val holding = Holding(
            id = 3,
            symbol = "AO27D",
            name = "Bono AO27D",
            assetType = AssetType.BOND,
            currency = CurrencyCode.USD,
            currentValueCents = 100_000_00,
            costCents = 95_000_00,
            tnaPercent = null,
            maturityDate = null,
            notes = "",
            marketKey = "rava:AO27D",
            source = MarketSource.RAVA,
            quantity = 1_000.0,
            quoteUnit = QuoteUnit.PER_100_NOMINAL,
        )
        val quotes = listOf(
            InstrumentQuote(
                marketKey = "rava:AO27D",
                symbol = "AO27D",
                source = MarketSource.RAVA,
                price = 100.0,
                currency = CurrencyCode.USD,
                quoteUnit = QuoteUnit.PER_100_NOMINAL,
                date = LocalDate.of(2026, 11, 7),
                fetchedAt = 0,
            ),
            InstrumentQuote(
                marketKey = "rava:AO27D",
                symbol = "AO27D",
                source = MarketSource.RAVA,
                price = 101.0,
                currency = CurrencyCode.USD,
                quoteUnit = QuoteUnit.PER_100_NOMINAL,
                date = LocalDate.of(2026, 11, 10),
                fetchedAt = 0,
            ),
        )

        val valuation = PortfolioValuationEngine.calculate(
            wallets = emptyList(),
            holdings = listOf(holding),
            fxRates = emptyList(),
            fallbackMepRate = 1_000.0,
            quotes = quotes,
        )

        // (101 - 100) × 0,01 × 1.000 × 100 = 1.000 cents USD = US$ 10 → $ 10.000 with MEP 1.000.
        assertEquals(1_000_000L, valuation.holdings.first().dailyInterestCents)
        assertEquals(1_000_000L, valuation.dailyInterestCents)
    }

    @Test
    fun `holding without quantity adds market variation over its stored value`() {
        // Users often apply a Rava/CAFCI preset and type only the current value.
        val holding = Holding(
            id = 4,
            symbol = "AO27D",
            name = "Bono AO27D",
            assetType = AssetType.BOND,
            currency = CurrencyCode.USD,
            currentValueCents = 1_000_00,
            costCents = 950_00,
            tnaPercent = null,
            maturityDate = null,
            notes = "",
            marketKey = "rava:AO27D",
            source = MarketSource.RAVA,
            quantity = 0.0,
            quoteUnit = QuoteUnit.PER_100_NOMINAL,
        )
        val quotes = listOf(
            quote("rava:AO27D", "AO27D", 100.0, LocalDate.of(2026, 11, 7)),
            quote("rava:AO27D", "AO27D", 101.0, LocalDate.of(2026, 11, 10)),
        )

        val valuation = PortfolioValuationEngine.calculate(
            wallets = emptyList(),
            holdings = listOf(holding),
            fxRates = emptyList(),
            fallbackMepRate = 1_000.0,
            quotes = quotes,
        )

        // 1% over US$ 1.000 = US$ 10 = $ 10.000 at MEP 1.000.
        assertEquals(1_000_000L, valuation.holdings.first().dailyInterestCents)
    }

    @Test
    fun `quotes fall back to symbol when the stored market key differs`() {
        val holding = Holding(
            id = 5,
            symbol = "S30N6",
            name = "LECAP S30N6",
            assetType = AssetType.LECAP,
            currency = CurrencyCode.ARS,
            currentValueCents = 1_000_000_00,
            costCents = 950_000_00,
            tnaPercent = null,
            maturityDate = null,
            notes = "",
            marketKey = "rava:s30n6",
            source = MarketSource.RAVA,
            quantity = 10_000.0,
            quoteUnit = QuoteUnit.PER_100_NOMINAL,
        )
        val quotes = listOf(
            quote("rava:S30N6", "S30N6", 100.0, LocalDate.of(2026, 11, 7)),
            quote("rava:S30N6", "S30N6", 101.0, LocalDate.of(2026, 11, 10)),
        )

        val valuation = PortfolioValuationEngine.calculate(
            wallets = emptyList(),
            holdings = listOf(holding),
            fxRates = emptyList(),
            fallbackMepRate = 1_000.0,
            quotes = quotes,
        )

        // (101 - 100) × 0,01 × 10.000 × 100 = 10.000 cents = $ 100.
        assertEquals(10_000L, valuation.holdings.first().dailyInterestCents)
    }

    private fun quote(
        marketKey: String,
        symbol: String,
        price: Double,
        date: LocalDate,
    ) = InstrumentQuote(
        marketKey = marketKey,
        symbol = symbol,
        source = MarketSource.RAVA,
        price = price,
        currency = CurrencyCode.ARS,
        quoteUnit = QuoteUnit.PER_100_NOMINAL,
        date = date,
        fetchedAt = 0,
    )
}
