package com.smartinvest.domain.model

import java.time.YearMonth

data class AppSettings(
    val salaryFciPercent: Int = 50,
    val salaryLecapPercent: Int = 30,
    val salaryMepPercent: Int = 20,
    /** USD 20.000 expressed in USD cents. */
    val ao27dThresholdUsdCents: Long = 2_000_000L,
    val rebalanceTargetPesoPercent: Int = 60,
    val rebalanceTolerancePp: Double = 5.0,
    val maturityAlertDays: Int = 30,
    val rateDropThresholdPp: Double = 0.5,
    val fallbackMepRate: Double = 1_000.0,
    /** Expected monthly official devaluation (crawling peg) for the real-rate comparison. */
    val monthlyDevaluationPercent: Double = 2.0,
    /** 0 = automatic weighted TNA of ARS wallets and peso holdings. */
    val pesoReferenceTnaPercent: Double = 0.0,
    val notificationsEnabled: Boolean = true,
    /** Privacy mode: monetary amounts are shown as asterisks on every screen. */
    val hideAmounts: Boolean = false,
) {
    val salaryWeights: List<Int>
        get() = listOf(salaryFciPercent, salaryLecapPercent, salaryMepPercent)

    val salaryWeightsSum: Int get() = salaryWeights.sum()
}

enum class AllocationBucket(val label: String, val destination: String) {
    FCI_T0("FCI T+0 eco", "Money market · rescate inmediato"),
    LECAP("Letras a tasa fija", "Bróker · LECAP/BONCAP"),
    MEP("Dólar MEP / cobertura", "USD hard currency · cobertura"),
}

data class SalaryAllocationLine(
    val bucket: AllocationBucket,
    val percent: Int,
    val amountCents: Long,
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
