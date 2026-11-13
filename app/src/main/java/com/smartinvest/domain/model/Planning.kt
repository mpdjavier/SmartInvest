package com.smartinvest.domain.model

import java.time.YearMonth

data class AppSettings(
    /** Share of the peso sleeve that goes to the carry FCI (Adcap Balanceado III, T+1). */
    val salaryFciPercent: Int = 45,
    /** Share of the peso sleeve that goes to fixed-rate letters (LECAP/BONCAP). */
    val salaryLecapPercent: Int = 15,
    /** Share of the dollar sleeve that goes to direct MEP coverage. */
    val salaryMepPercent: Int = 25,
    /** Share of the dollar sleeve that goes to sovereign bonds (AO27D). */
    val salaryBondPercent: Int = 15,
    /**
     * Fixed monthly amount sent to the wallet FCI for 24/7 expenses and liquidity.
     * 0 means "use the real household spend of the month".
     */
    val salaryLiquidityCents: Long = 0L,
    /** USD 20.000 expressed in USD cents. */
    val ao27dThresholdUsdCents: Long = 2_000_000L,
    val rebalanceTargetPesoPercent: Int = 60,
    /** Explicit peso target for the RED (high risk) state. Defaults to a defensive 50/50. */
    val redTargetPesoPercent: Int = 50,
    val rebalanceTolerancePp: Double = 5.0,
    val maturityAlertDays: Int = 30,
    val rateDropThresholdPp: Double = 0.5,
    val fallbackMepRate: Double = 1_000.0,
    /** Expected monthly official devaluation (crawling peg) for the real-rate comparison. */
    val monthlyDevaluationPercent: Double = 2.0,
    /** 0 = automatic weighted TNA of ARS wallets and peso holdings. */
    val pesoReferenceTnaPercent: Double = 0.0,
    /** Country-risk level in points (0 = no data). Above 800 caps the band at AMBER. */
    val countryRiskPoints: Int = 0,
    /** Country-risk change over the last 30 days, in points (can be negative). */
    val countryRisk30dChange: Int = 0,
    /** MEP gap reading 30 days ago; negative means no data. Used for the gap velocity. */
    val previousFxGapPercent: Double = -1.0,
    /** Applies the pre-electoral ceiling to the GREEN peso target. */
    val electoralCalendarEnabled: Boolean = true,
    /** Blocks the GREEN overweight if it is not held in short/liquid instruments. */
    val liquidityGuardrailEnabled: Boolean = true,
    /** Maximum months to maturity (or T+0) allowed for the GREEN peso overweight. */
    val liquidityGuardrailMaxMonths: Int = 6,
    /** Last effective band, persisted to implement hysteresis between monthly decisions. */
    val previousCarryBandName: String = "",
    /**
     * Comma-separated substrings excluded from the carry ratio (wallet or holding
     * names). The Mercado Pago liquidity pool and the travel bucket live outside
     * the investable portfolio by default.
     */
    val carryExcludedPatterns: String = "Mercado Pago, viaje",
    val notificationsEnabled: Boolean = true,
    /** Privacy mode: monetary amounts are shown as asterisks on every screen. */
    val hideAmounts: Boolean = false,
    /** Serialized JSON list of user-configured/modified payment agenda items. */
    val paymentAgendaJson: String = "",
) {
    val previousCarryBand: CarryRiskBand?
        get() = previousCarryBandName
            .takeIf { it.isNotBlank() }
            ?.let { name -> runCatching { CarryRiskBand.valueOf(name) }.getOrNull() }

    val salaryWeights: List<Int>
        get() = listOf(salaryFciPercent, salaryLecapPercent, salaryMepPercent, salaryBondPercent)

    val salaryWeightsSum: Int get() = salaryWeights.sum()
}

/**
 * Destinations for the monthly salary flow:
 *
 * 1. Liquidity in the FCI marked as wallet (configurable fixed amount).
 * 2. Peso sleeve: carry FCI (T+1) + fixed-rate letters.
 * 3. Dollar sleeve: direct MEP + sovereign bonds as the floor.
 */
enum class AllocationBucket(val label: String, val destination: String) {
    LIQUIDITY("Gastos / liquidez 24/7", "FCI marcado como billetera"),
    CARRY_FCI("Carry principal (T+1)", "FCI Adcap Balanceado III · rescate 24 h"),
    FIXED_RATE("Tasa fija (bróker)", "LECAP / BONCAP (ej. T30A7)"),
    MEP("Cobertura / MEP", "Dólar MEP directo"),
    SOVEREIGN_BOND("Resguardo / piso", "Bonos soberanos (AO27D)"),
}

data class SalaryAllocationLine(
    val bucket: AllocationBucket,
    val percent: Int,
    val amountCents: Long,
    /** The liquidity line is a configurable amount, not a percentage of the remainder. */
    val isFixedAmount: Boolean = false,
)

data class SalaryPlan(
    val month: YearMonth,
    val netSalaryCents: Long,
    val householdCushionCents: Long,
    val investableCents: Long,
    val deficitCents: Long,
    val allocations: List<SalaryAllocationLine>,
    val emergencyLiquidCents: Long,
    val emergencyCoverageMonths: Double,
)

data class SavedSalaryPlan(
    val id: Long,
    val month: YearMonth,
    val netSalaryCents: Long,
    val cushionCents: Long,
    val fciCents: Long,
    val lecapCents: Long,
    val mepCents: Long,
    val bondCents: Long = 0L,
    val liquidityCents: Long = 0L,
    val createdAt: Long,
)

enum class LiquidityTier(val label: String, val order: Int) {
    FCI_T0("FCI T+0", 1),
    WALLET("Billetera", 2),
    LECAP("LECAP", 3),
    USD_HARD_CURRENCY("Dólares / MEP", 4),
    OTHER("Otros activos", 5),
}

data class LiquiditySource(
    val id: String,
    val name: String,
    val tier: LiquidityTier,
    val availableCents: Long,
    val settlementDays: Int,
    val note: String,
)

data class RedemptionLeg(
    val sourceName: String,
    val tier: LiquidityTier,
    val amountCents: Long,
    val settlementDays: Int,
    val note: String,
)

data class RedemptionPlan(
    val requestedCents: Long,
    val legs: List<RedemptionLeg>,
    val coveredCents: Long,
    val shortfallCents: Long,
    val maxSettlementDays: Int,
) {
    val isFullyCovered: Boolean get() = shortfallCents == 0L
}
