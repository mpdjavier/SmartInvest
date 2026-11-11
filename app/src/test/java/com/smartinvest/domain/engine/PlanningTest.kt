package com.smartinvest.domain.engine

import com.smartinvest.domain.model.AppSettings
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.LiquiditySource
import com.smartinvest.domain.model.LiquidityTier
import com.smartinvest.ui.planning.LiquiditySources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class PlanningTest {

    @Test
    fun `salary plan reserves the 24 7 liquidity and splits the remainder in five lines`() {
        val plan = SalaryAllocationPlanner.plan(
            month = YearMonth.of(2025, 6),
            netSalaryCents = 100_000_000,
            householdMonthlySpendCents = 40_000_000,
            liquidEmergencyCents = 120_000_000,
            settings = AppSettings(
                salaryFciPercent = 50,
                salaryLecapPercent = 50,
                salaryMepPercent = 50,
                salaryBondPercent = 50,
                rebalanceTargetPesoPercent = 50,
            ),
        )

        assertEquals(60_000_000L, plan.investableCents)
        assertEquals(0L, plan.deficitCents)
        assertEquals(100_000_000L, plan.allocations.sumOf { it.amountCents })
        assertEquals(40_000_000L, plan.allocations[0].amountCents)
        assertTrue(plan.allocations[0].isFixedAmount)
        // Remainder: 30M pesos (15M carry + 15M fixed) and 30M dollars (15M MEP + 15M bonds).
        assertEquals(15_000_000L, plan.allocations[1].amountCents)
        assertEquals(15_000_000L, plan.allocations[2].amountCents)
        assertEquals(15_000_000L, plan.allocations[3].amountCents)
        assertEquals(15_000_000L, plan.allocations[4].amountCents)
        assertEquals(3.0, plan.emergencyCoverageMonths, 0.001)
    }

    @Test
    fun `configured liquidity replaces the cushion and the risk target drives the sleeves`() {
        val plan = SalaryAllocationPlanner.plan(
            month = YearMonth.of(2025, 6),
            netSalaryCents = 100_000_000,
            householdMonthlySpendCents = 40_000_000,
            liquidEmergencyCents = 0,
            settings = AppSettings(salaryLiquidityCents = 20_000_000),
            targetPesoPercent = 60.0,
        )

        assertEquals(80_000_000L, plan.investableCents)
        assertEquals(20_000_000L, plan.allocations[0].amountCents)
        // 60% pesos: 45/15 weights -> 36M / 12M.
        assertEquals(36_000_000L, plan.allocations[1].amountCents)
        assertEquals(12_000_000L, plan.allocations[2].amountCents)
        // 40% dollars: 25/15 weights -> 20M / 12M.
        assertEquals(20_000_000L, plan.allocations[3].amountCents)
        assertEquals(12_000_000L, plan.allocations[4].amountCents)
    }

    @Test
    fun `salary plan reports deficit when expenses exceed salary`() {
        val plan = SalaryAllocationPlanner.plan(
            month = YearMonth.of(2025, 6),
            netSalaryCents = 30_000_000,
            householdMonthlySpendCents = 45_000_000,
            liquidEmergencyCents = 0,
            settings = AppSettings(),
        )
        assertEquals(0L, plan.investableCents)
        assertEquals(15_000_000L, plan.deficitCents)
    }

    @Test
    fun `wallet fci is immediate while adcap balanceado iii settles at t plus one`() {
        fun fci(id: Long, symbol: String, isWallet: Boolean, days: Int) = Holding(
            id = id,
            symbol = symbol,
            name = symbol,
            assetType = AssetType.FCI,
            currency = CurrencyCode.ARS,
            currentValueCents = 10_000_000,
            costCents = 10_000_000,
            tnaPercent = 40.0,
            maturityDate = null,
            notes = "",
            isWallet = isWallet,
            settlementDays = days,
        )

        val walletSource = LiquiditySources.fromHolding(fci(1, "Mercado Fondo", true, 1), 1_000.0)!!
        val adcapSource = LiquiditySources.fromHolding(fci(2, "Adcap Balanceado III", false, 1), 1_000.0)!!

        assertEquals(0, walletSource.settlementDays)
        assertEquals(LiquidityTier.FCI_T0, walletSource.tier)
        assertEquals(1, adcapSource.settlementDays)
        assertTrue(adcapSource.note.contains("T+1"))
    }

    @Test
    fun `emergency planner consumes fci first`() {
        val sources = listOf(
            LiquiditySource("h1", "LECAP T30A7", LiquidityTier.LECAP, 20_000_000, 1, "T+1"),
            LiquiditySource("w2", "Ualá", LiquidityTier.WALLET, 8_000_000, 0, "inmediato"),
            LiquiditySource("w1", "FCI eco", LiquidityTier.FCI_T0, 4_000_000, 0, "T+0"),
        )

        val plan = EmergencyRedemptionPlanner.simulate(10_000_000, sources)

        assertTrue(plan.isFullyCovered)
        assertEquals(10_000_000L, plan.coveredCents)
        assertEquals(2, plan.legs.size)
        assertEquals("FCI eco", plan.legs[0].sourceName)
        assertEquals(4_000_000L, plan.legs[0].amountCents)
        assertEquals("Ualá", plan.legs[1].sourceName)
        assertEquals(6_000_000L, plan.legs[1].amountCents)
    }

    @Test
    fun `emergency planner reports shortfall`() {
        val sources = listOf(
            LiquiditySource("w1", "FCI eco", LiquidityTier.FCI_T0, 4_000_000, 0, "T+0"),
        )
        val plan = EmergencyRedemptionPlanner.simulate(10_000_000, sources)
        assertEquals(4_000_000L, plan.coveredCents)
        assertEquals(6_000_000L, plan.shortfallCents)
    }

    @Test
    fun `rebalance advisor flags a fully peso portfolio out of band`() {
        val wallets = listOf(
            com.smartinvest.domain.model.Wallet(
                id = 1,
                name = "Pesos",
                institution = "i",
                type = com.smartinvest.domain.model.WalletType.WALLET,
                currency = com.smartinvest.domain.model.CurrencyCode.ARS,
                balanceCents = 90_000_00,
                tnaPercent = 30.0,
                remuneratedCapCents = 0,
                isActive = true,
                rateSource = "test",
                rateUpdatedAt = null,
                liquidityPriority = 1,
                notes = "",
            ),
        )
        val valuation = PortfolioValuationEngine.calculate(wallets, emptyList(), emptyList(), 1_000.0)
        val advice = RebalanceAdvisor.analyze(valuation, AppSettings(rebalanceTargetPesoPercent = 60, rebalanceTolerancePp = 5.0))

        assertTrue(advice.isOutOfBand)
        assertEquals(100.0, advice.pesoPercent, 0.001)
        assertTrue(advice.suggestedAction.contains("Dólar MEP"))
    }
}
