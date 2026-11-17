package com.smartinvest.domain.engine

import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.InstrumentQuote
import com.smartinvest.domain.model.MarketSource
import com.smartinvest.domain.model.QuoteUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PortfolioReconciliationEngineTest {

    @Test
    fun `parses 2026-11-14 portfolio list with exact cents currencies and broker metadata`() {
        val parsed = PortfolioReconciliationEngine.presetHoldings2026_11_14()
        assertEquals(9, parsed.size)

        val byLabel = parsed.associateBy { it.displayLabel }

        val ao27d = requireNotNull(byLabel["AO27D (GALICIA)"])
        assertEquals(20_796_11L, ao27d.valueCents)
        assertEquals(CurrencyCode.USD, ao27d.currency)

        val s30Galicia = requireNotNull(byLabel["S30N6 (GALICIA)"])
        assertEquals(4_061_874_52L, s30Galicia.valueCents)
        assertEquals(CurrencyCode.ARS, s30Galicia.currency)
        assertEquals(3_154_364.0, s30Galicia.quantity, 0.001)

        val mpArs = requireNotNull(byLabel["MP-ARS"])
        assertEquals(615_166_58L, mpArs.valueCents)
        assertEquals(CurrencyCode.ARS, mpArs.currency)
        assertTrue(mpArs.isWallet)

        val mpUsd = requireNotNull(byLabel["MP-USD"])
        assertEquals(7_111_63L, mpUsd.valueCents)
        assertEquals(CurrencyCode.USD, mpUsd.currency)

        val adcap = requireNotNull(byLabel["ADCAP-BAL3"])
        assertEquals(74_814_083_00L, adcap.valueCents)
        assertEquals(67_982_224_10L, adcap.costCents)

        val t15 = requireNotNull(byLabel["T15E7 (IEB)"])
        assertEquals(781_899_42L, t15.valueCents)
        assertEquals(504_061.0, t15.quantity, 0.001)
        assertEquals(777_740_20L, t15.costCents)

        val ao27 = requireNotNull(byLabel["AO27 (IEB)"])
        assertEquals(778_787_10L, ao27.valueCents)
        assertEquals(481.0, ao27.quantity, 0.001)
        assertEquals(776_642_02L, ao27.costCents)

        val spy = requireNotNull(byLabel["SPY (IEB)"])
        assertEquals(14_165_760_00L, spy.valueCents)
        assertEquals(672.0, spy.quantity, 0.001)
        assertEquals(13_263_659_67L, spy.costCents)

        val s30Ieb = requireNotNull(byLabel["S30N6 (IEB)"])
        assertEquals(3_817_398_24L, s30Ieb.valueCents)
        assertEquals(2_964_509.0, s30Ieb.quantity, 0.001)
        assertEquals(3_653_297_51L, s30Ieb.costCents)
    }

    @Test
    fun `parses 2026-11-16 portfolio list with updated balances and withdrawals`() {
        val parsed = PortfolioReconciliationEngine.presetHoldings2026_11_16()
        assertEquals(9, parsed.size)

        val byLabel = parsed.associateBy { it.displayLabel }

        val ao27d = requireNotNull(byLabel["AO27D (GALICIA)"])
        assertEquals(20_796_11L, ao27d.valueCents)
        assertEquals(CurrencyCode.USD, ao27d.currency)

        val s30Galicia = requireNotNull(byLabel["S30N6 (GALICIA)"])
        assertEquals(4_065_344_32L, s30Galicia.valueCents)
        assertEquals(CurrencyCode.ARS, s30Galicia.currency)

        val mpArs = requireNotNull(byLabel["MP-ARS"])
        assertEquals(359_921_06L, mpArs.valueCents)
        assertEquals(CurrencyCode.ARS, mpArs.currency)
        assertTrue(mpArs.isWallet)

        val mpUsd = requireNotNull(byLabel["MP-USD"])
        assertEquals(7_112_67L, mpUsd.valueCents)
        assertEquals(CurrencyCode.USD, mpUsd.currency)

        val adcap = requireNotNull(byLabel["ADCAP-BAL3"])
        assertEquals(74_876_721_78L, adcap.valueCents)

        val t15 = requireNotNull(byLabel["T15E7 (IEB)"])
        assertEquals(782_363_16L, t15.valueCents)

        val ao27 = requireNotNull(byLabel["AO27 (IEB)"])
        assertEquals(782_154_10L, ao27.valueCents)

        val spy = requireNotNull(byLabel["SPY (IEB)"])
        assertEquals(13_970_880_00L, spy.valueCents)

        val s30Ieb = requireNotNull(byLabel["S30N6 (IEB)"])
        assertEquals(3_820_184_88L, s30Ieb.valueCents)
    }

    @Test
    fun `reconciles 2026-11-16 balances to the spreadsheet totals at MEP 1500`() {
        val specs = PortfolioReconciliationEngine.presetHoldings2026_11_16()
        val holdings = specs.mapIndexed { idx, spec ->
            Holding(
                id = (idx + 1).toLong(),
                symbol = spec.symbol,
                name = spec.name,
                assetType = spec.assetType,
                currency = spec.currency,
                currentValueCents = spec.valueCents,
                costCents = spec.costCents,
                tnaPercent = null,
                maturityDate = spec.maturityDate,
                notes = PortfolioReconciliationEngine.composeNotes(
                    existingNotes = "",
                    accountTag = spec.accountTag,
                    movementsBlock = "",
                    manualLocked = true,
                    manualDate = LocalDate.of(2026, 11, 16),
                ),
                marketKey = spec.marketKey,
                source = spec.source,
                quantity = spec.quantity,
                quoteUnit = spec.quoteUnit,
                isWallet = spec.isWallet,
                settlementDays = spec.settlementDays,
            )
        }

        val report = PortfolioReconciliationEngine.buildReport(
            holdings = holdings,
            quotes = emptyList(),
            mepRate = 1_500.0,
        )

        // Direct ARS sum = 4.065.344,32 + 359.921,06 + 74.876.721,78 + 782.363,16 + 782.154,10 + 13.970.880,00 + 3.820.184,88
        assertEquals(98_657_569_30L, report.nativeArsTotalCents)
        // Direct USD sum = 20.796,11 (AO27D) + 7.112,67 (MP-USD)
        assertEquals(27_908_78L, report.nativeUsdTotalCents)
        // Total in ARS at MEP 1.500 = 98.657.569,30 + (27.908,78 × 1.500)
        assertEquals(140_520_739_30L, report.totalArsCents)
        assertEquals(9_368_049L, report.totalUsdCents)

        val dollarLabels = report.items.filter { it.isDollarExposure }.map { it.holding.symbol }.toSet()
        assertEquals(setOf("AO27D", "AO27", "SPY", "MP-USD"), dollarLabels)
        assertTrue(report.items.all { it.isManualLocked })
        assertTrue(report.items.all { it.manualDate == LocalDate.of(2026, 11, 16) })
    }

    @Test
    fun `builds reconciliation report with exact ARS and USD totals exposure and implied prices`() {
        val specs = PortfolioReconciliationEngine.presetHoldings2026_11_14()
        val holdings = specs.mapIndexed { idx, spec ->
            val movBlock = if (spec.movements.isNotEmpty()) {
                BrokerMovementImporter.summarize(spec.movements).toNotesBlock(spec.currency)
            } else {
                ""
            }
            Holding(
                id = (idx + 1).toLong(),
                symbol = spec.symbol,
                name = spec.name,
                assetType = spec.assetType,
                currency = spec.currency,
                currentValueCents = spec.valueCents,
                costCents = spec.costCents,
                tnaPercent = null,
                maturityDate = spec.maturityDate,
                notes = PortfolioReconciliationEngine.composeNotes(
                    existingNotes = "",
                    accountTag = spec.accountTag,
                    movementsBlock = movBlock,
                    manualLocked = true,
                    manualDate = LocalDate.of(2026, 11, 14),
                ),
                marketKey = spec.marketKey,
                source = spec.source,
                quantity = spec.quantity,
                quoteUnit = spec.quoteUnit,
                isWallet = spec.isWallet,
                settlementDays = spec.settlementDays,
            )
        }
        val quotes = listOf(
            InstrumentQuote(
                marketKey = "rava:SPY",
                symbol = "SPY",
                source = MarketSource.RAVA,
                price = 21080.0,
                currency = CurrencyCode.ARS,
                quoteUnit = QuoteUnit.PER_UNIT,
                date = LocalDate.of(2026, 11, 14),
                fetchedAt = 1L,
            ),
            InstrumentQuote(
                marketKey = "rava:S30N6",
                symbol = "S30N6",
                source = MarketSource.RAVA,
                price = 128.77,
                currency = CurrencyCode.ARS,
                quoteUnit = QuoteUnit.PER_100_NOMINAL,
                date = LocalDate.of(2026, 11, 14),
                fetchedAt = 1L,
            ),
            InstrumentQuote(
                marketKey = "rava:T15E7",
                symbol = "T15E7",
                source = MarketSource.RAVA,
                price = 155.12,
                currency = CurrencyCode.ARS,
                quoteUnit = QuoteUnit.PER_100_NOMINAL,
                date = LocalDate.of(2026, 11, 14),
                fetchedAt = 1L,
            ),
            InstrumentQuote(
                marketKey = "rava:AO27",
                symbol = "AO27",
                source = MarketSource.RAVA,
                price = 161910.0,
                currency = CurrencyCode.ARS,
                quoteUnit = QuoteUnit.PER_100_NOMINAL,
                date = LocalDate.of(2026, 11, 14),
                fetchedAt = 1L,
            ),
        )

        val report = PortfolioReconciliationEngine.buildReport(
            holdings = holdings,
            quotes = quotes,
            mepRate = 1_500.0,
        )

        // Direct ARS sum (MP-USD is USD) = 4.061.874,52 + 615.166,58 + 74.814.083,00 + 781.899,42 + 778.787,10 + 14.165.760,00 + 3.817.398,24 = 99.034.968,86 ARS
        assertEquals(99_034_968_86L, report.nativeArsTotalCents)
        // Direct USD sum = 20.796,11 (AO27D) + 7.111,63 (MP-USD) = 27.907,74 USD
        assertEquals(27_907_74L, report.nativeUsdTotalCents)

        // Pure peso exposure = ADCAP-BAL3 + S30N6 (GALICIA) + S30N6 (IEB) + T15E7 + MP-ARS = 84.090.421,76 ARS
        assertEquals(84_090_421_76L, report.pesoExposureArsCents)

        // Dollarized exposure includes AO27D (USD), SPY (ARS), AO27 (ARS) and MP-USD (ARS)
        val dollarLabels = report.items.filter { it.isDollarExposure }.map { it.holding.symbol }.toSet()
        assertEquals(setOf("AO27D", "AO27", "SPY", "MP-USD"), dollarLabels)

        // Check implied prices and 0 diff against matching quotes
        val spyItem = report.items.first { it.holding.symbol == "SPY" }
        assertEquals(21_080.0, spyItem.impliedPrice ?: 0.0, 0.01)
        assertEquals(0L, spyItem.diffNativeCents)
        assertEquals(902_100_33L, spyItem.gainNativeCents)

        val ao27Item = report.items.first { it.holding.symbol == "AO27" }
        assertEquals(161_910.0, ao27Item.impliedPrice ?: 0.0, 0.01)
        assertEquals(0L, ao27Item.diffNativeCents)
        assertEquals(2_145_08L, ao27Item.gainNativeCents)

        // Total difference against matching quotes is 0
        assertEquals(0L, report.totalDiffArsCents)
    }

    @Test
    fun `manual valuation lock tag round-trips without affecting movement notes`() {
        val base = "IEB\n11/11/2026: +\$ 777.740,20 (COMPRA) [cant: +504061]"
        val locked = PortfolioReconciliationEngine.withManualValuationLock(
            notes = base,
            locked = true,
            date = LocalDate.of(2026, 11, 14),
        )
        assertTrue(PortfolioReconciliationEngine.isManualValuationLocked(locked))
        assertEquals(LocalDate.of(2026, 11, 14), PortfolioReconciliationEngine.manualValuationDate(locked))
        assertEquals(1, BrokerMovementImporter.parseMovementNotes(locked).size)

        val unlocked = PortfolioReconciliationEngine.withManualValuationLock(locked, locked = false)
        assertFalse(PortfolioReconciliationEngine.isManualValuationLocked(unlocked))
        assertEquals(base, unlocked)
    }
}
