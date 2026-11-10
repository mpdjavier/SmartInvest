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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class DailyInterestEngineTest {

    @Test
    fun `builds daily breakdown for remunerated wallet with cap and formula`() {
        val month = YearMonth.of(2026, 11)
        val today = LocalDate.of(2026, 11, 3)
        val wallet = Wallet(
            id = 1,
            name = "Mercado Pago",
            institution = "MP",
            type = WalletType.WALLET,
            currency = CurrencyCode.ARS,
            balanceCents = 800_000_00L,
            tnaPercent = 36.5,
            remuneratedCapCents = 500_000_00L,
            isActive = true,
            rateSource = "test",
            rateUpdatedAt = null,
            liquidityPriority = 2,
            notes = "",
        )

        val breakdown = DailyInterestEngine.build(
            month = month,
            wallets = listOf(wallet),
            holdings = emptyList(),
            fxRates = emptyList(),
            quotes = emptyList(),
            fallbackMepRate = 1_000.0,
            today = today,
        )

        // 500.000 ARS at 36.5% TNA = 500 ARS/day * 3 days = 1.500 ARS
        assertEquals(3, breakdown.days.size)
        assertEquals(150_000L, breakdown.totalArsCents)
        assertEquals(50_000L, breakdown.averageDailyArsCents)
        val firstLine = breakdown.days.first().lines.first()
        assertTrue(firstLine.explanation.contains("tope"))
        assertTrue(firstLine.explanation.contains("36,50%"))

        val reconciliation = breakdown.reconciliation
        assertEquals(3, reconciliation.daysConsidered)
        assertEquals(150_000L, reconciliation.accrualTotalArsCents)
        assertEquals(50_000L, reconciliation.accrualPerDayArsCents)
        assertEquals(150_000L, reconciliation.projectedMonthArsCents)
        assertEquals(0L, reconciliation.differenceVsProjectionArsCents)
        assertEquals(0L, reconciliation.unexplainedArsCents)
        assertTrue(reconciliation.isFullyExplained)
    }

    @Test
    fun `uses real market quote variation when holding has historical quotes`() {
        val month = YearMonth.of(2026, 11)
        val today = LocalDate.of(2026, 11, 2)
        val holding = Holding(
            id = 10,
            symbol = "AO27D",
            name = "Bonar 2027 U\$S",
            assetType = AssetType.BOND,
            currency = CurrencyCode.USD,
            currentValueCents = 102_450L,
            costCents = 100_000L,
            tnaPercent = null,
            maturityDate = null,
            notes = "",
            marketKey = "rava:AO27D",
            source = MarketSource.RAVA,
            quantity = 1000.0,
            quoteUnit = QuoteUnit.PER_100_NOMINAL,
        )
        val quotes = listOf(
            InstrumentQuote(
                marketKey = "rava:AO27D",
                symbol = "AO27D",
                source = MarketSource.RAVA,
                price = 102.00,
                currency = CurrencyCode.USD,
                quoteUnit = QuoteUnit.PER_100_NOMINAL,
                date = LocalDate.of(2026, 11, 1),
                fetchedAt = 1L,
            ),
            InstrumentQuote(
                marketKey = "rava:AO27D",
                symbol = "AO27D",
                source = MarketSource.RAVA,
                price = 102.50,
                currency = CurrencyCode.USD,
                quoteUnit = QuoteUnit.PER_100_NOMINAL,
                date = LocalDate.of(2026, 11, 2),
                fetchedAt = 2L,
            ),
        )
        val fx = listOf(FxRate("bolsa", "MEP", 1_000.0, 1_000.0, 0L))

        val breakdown = DailyInterestEngine.build(
            month = month,
            wallets = emptyList(),
            holdings = listOf(holding),
            fxRates = fx,
            quotes = quotes,
            fallbackMepRate = 1_000.0,
            today = today,
        )

        // 1000 nominales at (102.50 - 102.00)/100 = +5.00 USD = +5000 ARS = 500_000 cents
        assertTrue(breakdown.hasMarketData)
        assertEquals(1, breakdown.days.size)
        assertEquals(500_000L, breakdown.totalArsCents)

        val reconciliation = breakdown.reconciliation
        assertEquals(0L, reconciliation.accrualTotalArsCents)
        assertEquals(0L, reconciliation.accrualPerDayArsCents)
        assertEquals(500_000L, reconciliation.marketTotalArsCents)
        assertEquals(500_000L, reconciliation.differenceVsProjectionArsCents)
        assertEquals(0L, reconciliation.unexplainedArsCents)
        assertTrue(reconciliation.notes.any { it.contains("Mercado") })
    }

    @Test
    fun `holding without quantity uses value-based market variation`() {
        val month = YearMonth.of(2026, 11)
        val today = LocalDate.of(2026, 11, 2)
        val holding = Holding(
            id = 12,
            symbol = "BALIII",
            name = "Adcap Balanceado III",
            assetType = AssetType.FCI,
            currency = CurrencyCode.ARS,
            currentValueCents = 1_000_000_00L,
            costCents = 900_000_00L,
            tnaPercent = null,
            maturityDate = null,
            notes = "",
            marketKey = "cafci:1402:4070",
            source = MarketSource.CAFCI,
            quantity = 0.0,
            quoteUnit = QuoteUnit.PER_UNIT,
        )
        val quotes = listOf(
            InstrumentQuote(
                marketKey = "cafci:1402:4070",
                symbol = "FCI-1402",
                source = MarketSource.CAFCI,
                price = 100.0,
                currency = CurrencyCode.ARS,
                quoteUnit = QuoteUnit.PER_UNIT,
                date = LocalDate.of(2026, 11, 1),
                fetchedAt = 1L,
            ),
            InstrumentQuote(
                marketKey = "cafci:1402:4070",
                symbol = "FCI-1402",
                source = MarketSource.CAFCI,
                price = 101.0,
                currency = CurrencyCode.ARS,
                quoteUnit = QuoteUnit.PER_UNIT,
                date = LocalDate.of(2026, 11, 2),
                fetchedAt = 2L,
            ),
        )

        val breakdown = DailyInterestEngine.build(
            month = month,
            wallets = emptyList(),
            holdings = listOf(holding),
            fxRates = emptyList(),
            quotes = quotes,
            fallbackMepRate = 1_000.0,
            today = today,
        )

        // 1% over ARS 1.000.000 = ARS 10.000 = 1.000.000 cents.
        assertEquals(1, breakdown.days.size)
        assertEquals(1_000_000L, breakdown.totalArsCents)
        assertTrue(breakdown.days.first().lines.first().explanation.contains("Valuación"))
    }

    @Test
    fun `wallet uses linked FCI tna when the manual rate is empty`() {
        val month = YearMonth.of(2026, 11)
        val today = LocalDate.of(2026, 11, 2)
        val fci = Holding(
            id = 5,
            symbol = "MPARS",
            name = "Mercado Pago ARS",
            assetType = AssetType.FCI,
            currency = CurrencyCode.ARS,
            currentValueCents = 300_000_00L,
            costCents = 300_000_00L,
            tnaPercent = 40.0,
            maturityDate = null,
            notes = "",
            marketKey = "cafci:1:2",
            source = MarketSource.CAFCI,
            quantity = 0.0,
            quoteUnit = QuoteUnit.PER_UNIT,
        )
        val wallet = Wallet(
            id = 1,
            name = "Mercado Pago ARS",
            institution = "Mercado Pago",
            type = WalletType.MONEY_MARKET_FUND,
            currency = CurrencyCode.ARS,
            balanceCents = 100_000_00L,
            tnaPercent = 0.0,
            remuneratedCapCents = 0L,
            isActive = true,
            rateSource = "manual",
            rateUpdatedAt = null,
            liquidityPriority = 2,
            notes = "",
            linkedHoldingId = 5,
        )

        val breakdown = DailyInterestEngine.build(
            month = month,
            wallets = listOf(wallet),
            holdings = listOf(fci),
            fxRates = emptyList(),
            quotes = emptyList(),
            fallbackMepRate = 1_000.0,
            today = today,
        )

        // 100.000 ARS at 40% TNA = 109,59 ARS/day -> 10.959 cents/day * 2 days.
        assertEquals(2, breakdown.days.size)
        assertEquals(21_918L, breakdown.totalArsCents)
        assertTrue(breakdown.days.first().lines.first().explanation.contains("FCI vinculado"))
    }
}
