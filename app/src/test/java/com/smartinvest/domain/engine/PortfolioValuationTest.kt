package com.smartinvest.domain.engine

import com.smartinvest.data.mapper.toDomain
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
    fun `bonds in ars and usd both count as dollar exposure alongside spy`() {
        val holdings = listOf(
            Holding(
                id = 1,
                symbol = "S30N6",
                name = "Letra del Tesoro S30N6",
                assetType = AssetType.LECAP,
                currency = CurrencyCode.ARS,
                currentValueCents = 500_000_00L,
                costCents = 500_000_00L,
                tnaPercent = null,
                maturityDate = null,
                notes = "",
            ),
            Holding(
                id = 2,
                symbol = "AO27",
                name = "Bonar 2027 $",
                assetType = AssetType.BOND,
                currency = CurrencyCode.ARS,
                currentValueCents = 200_000_00L,
                costCents = 200_000_00L,
                tnaPercent = null,
                maturityDate = null,
                notes = "",
            ),
            Holding(
                id = 3,
                symbol = "AO27D",
                name = "Bonar 2027 U\$S",
                assetType = AssetType.BOND,
                currency = CurrencyCode.USD,
                currentValueCents = 100_00L, // US$ 100 * 1.000 = $ 100.000 ARS
                costCents = 100_00L,
                tnaPercent = null,
                maturityDate = null,
                notes = "",
            ),
            Holding(
                id = 4,
                symbol = "SPY",
                name = "SPDR S&P 500 CEDEAR",
                assetType = AssetType.CEDEAR,
                currency = CurrencyCode.ARS,
                currentValueCents = 200_000_00L,
                costCents = 200_000_00L,
                tnaPercent = null,
                maturityDate = null,
                notes = "",
            ),
        )
        val fx = listOf(FxRate("bolsa", "MEP", 0.0, 1_000.0, 0))

        val valuation = PortfolioValuationEngine.calculate(emptyList(), holdings, fx, 1_000.0)

        assertTrue(PortfolioValuationEngine.isDollarExposure(holdings[1]))
        assertTrue(PortfolioValuationEngine.isDollarExposure(holdings[2]))
        assertTrue(PortfolioValuationEngine.isDollarExposure(holdings[3]))
        assertEquals(1_000_000_00L, valuation.totalArsCents)
        assertEquals(500_000_00L, valuation.arsAssetsCents)
        assertEquals(500_000_00L, valuation.usdAssetsArsCents)
        assertEquals(50.0, valuation.pesoSharePercent, 0.001)
        assertEquals(50.0, valuation.dollarSharePercent, 0.001)
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
        val valuation = PortfolioValuationEngine.calculate(
            wallets = emptyList(),
            holdings = holdings,
            fxRates = emptyList(),
            fallbackMepRate = 1_000.0,
            today = LocalDate.of(2026, 11, 1),
        )
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

        val valuation = PortfolioValuationEngine.calculate(
            wallets = wallets,
            holdings = listOf(fci),
            fxRates = emptyList(),
            fallbackMepRate = 1_000.0,
            today = LocalDate.of(2026, 11, 1),
        )

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

        // currentValueCents = US$ 1.010 at price 101 → prevValueCents = US$ 1.000 at price 100 → delta = US$ 10 = $ 10.000 at MEP 1.000.
        val holdingWithEndValue = holding.copy(currentValueCents = 1_010_00)
        val valuation = PortfolioValuationEngine.calculate(
            wallets = emptyList(),
            holdings = listOf(holdingWithEndValue),
            fxRates = emptyList(),
            fallbackMepRate = 1_000.0,
            quotes = quotes,
        )

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

    @Test
    fun `daily interest matches DailyInterestEngine and monthly projection uses average daily times 30`() {
        val today = LocalDate.of(2026, 11, 16)
        val wallets = listOf(wallet(1, 2_000_000_00L, 36.5))
        val fci = Holding(
            id = 10,
            symbol = "ADCAP-BAL3",
            name = "Adcap Balanceado III",
            assetType = AssetType.FCI,
            currency = CurrencyCode.ARS,
            currentValueCents = 5_000_000_00L,
            costCents = 4_800_000_00L,
            tnaPercent = null,
            maturityDate = null,
            notes = "",
            marketKey = "cafci:1402:4070",
            source = MarketSource.CAFCI,
            quantity = 0.0,
            quoteUnit = QuoteUnit.PER_UNIT,
        )
        val spy = Holding(
            id = 11,
            symbol = "SPY",
            name = "SPDR S&P 500 CEDEAR",
            assetType = AssetType.CEDEAR,
            currency = CurrencyCode.ARS,
            currentValueCents = 14_185_920_00L,
            costCents = 13_991_040_00L,
            tnaPercent = null,
            maturityDate = null,
            notes = "",
            marketKey = "rava:SPY",
            source = MarketSource.RAVA,
            quantity = 672.0,
            quoteUnit = QuoteUnit.PER_UNIT,
        )
        val quotes = listOf(
            quote("cafci:1402:4070", "ADCAP-BAL3", 100.0, LocalDate.of(2026, 11, 1)).copy(
                source = MarketSource.CAFCI,
                quoteUnit = QuoteUnit.PER_UNIT,
            ),
            quote("cafci:1402:4070", "ADCAP-BAL3", 101.5, LocalDate.of(2026, 11, 13)).copy(
                source = MarketSource.CAFCI,
                quoteUnit = QuoteUnit.PER_UNIT,
            ),
            // SPY only traded on Nov 10 -> Nov 11, not on Nov 16.
            quote("rava:SPY", "SPY", 20820.0, LocalDate.of(2026, 11, 10)).copy(quoteUnit = QuoteUnit.PER_UNIT),
            quote("rava:SPY", "SPY", 21110.0, LocalDate.of(2026, 11, 11)).copy(quoteUnit = QuoteUnit.PER_UNIT),
        )

        val valuation = PortfolioValuationEngine.calculate(
            wallets = wallets,
            holdings = listOf(fci, spy),
            fxRates = emptyList(),
            fallbackMepRate = 1_000.0,
            quotes = quotes,
            today = today,
        )
        val breakdown = DailyInterestEngine.build(
            month = java.time.YearMonth.from(today),
            wallets = wallets,
            holdings = listOf(fci, spy),
            fxRates = emptyList(),
            quotes = quotes,
            fallbackMepRate = 1_000.0,
            today = today,
        )
        val todayRow = breakdown.days.first { it.date == today }

        // Daily interest in Inicio/Cartera matches the exact today row in Intereses día a día.
        assertEquals(todayRow.totalArsCents, valuation.dailyInterestCents)
        // Monthly interest is average of daily interests × 30, not today's single-day interest × 30.
        assertEquals(breakdown.averageDailyArsCents * 30L, valuation.monthlyInterestCents)
        assertTrue(valuation.monthlyInterestCents != valuation.dailyInterestCents * 30L)
    }

    @Test
    fun `alert push flag is only enabled for event day alerts and not advance or overdue`() {
        val advance = com.smartinvest.data.local.entity.AlertEntity(
            id = 1,
            type = com.smartinvest.domain.model.AlertType.PAYMENT_DUE,
            severity = com.smartinvest.domain.model.AlertSeverity.INFO,
            title = "Agenda: Luz vence en 3 día(s)",
            message = "Fecha agendada 19/11/2026.",
            dedupeKey = "payment_agenda:luz:2026-11-19:advance",
            createdAt = 1_000L,
        )
        val todayAlert = advance.copy(
            id = 2,
            severity = com.smartinvest.domain.model.AlertSeverity.WARNING,
            title = "Agenda: Luz vence hoy",
            dedupeKey = "payment_agenda:luz:2026-11-19:today",
        )
        val overdue = advance.copy(
            id = 3,
            severity = com.smartinvest.domain.model.AlertSeverity.WARNING,
            title = "Agenda: Luz venció hace 1 día(s)",
            dedupeKey = "payment_agenda:luz:2026-11-19:overdue",
        )

        assertFalse(advance.toDomain().notifyPush)
        assertTrue(todayAlert.toDomain().notifyPush)
        assertFalse(overdue.toDomain().notifyPush)
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
