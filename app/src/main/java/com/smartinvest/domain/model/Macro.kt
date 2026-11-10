package com.smartinvest.domain.model

/**
 * Macro carry-risk bands. The score is always in 0..100.
 *
 * - LOW (0..30): high real yield and a calm FX gap.
 * - MODERATE (31..65): normal equilibrium.
 * - HIGH (66..100): FX tension, rate compression or a widening gap.
 */
enum class CarryRiskBand(
    val label: String,
    val emoji: String,
    val rangeLabel: String,
) {
    LOW("Riesgo bajo", "🟢", "0 – 30"),
    MODERATE("Riesgo moderado", "🟡", "31 – 65"),
    HIGH("Riesgo alto", "🔴", "66 – 100"),
}

/** One weighted component of the carry-risk score. */
data class MacroRiskFactor(
    val key: String,
    val label: String,
    val score: Int,
    val maxScore: Int,
    val detail: String,
)

/**
 * Result of the macro carry-risk engine.
 *
 * [realMonthlyRatePercent] compares the reference peso TNA against the expected
 * monthly crawling peg. [fxGapPercent] is MEP vs. official. [deviationPp] is the
 * signed distance between the current peso share and the strategic target.
 */
data class CarryRiskAssessment(
    val score: Int,
    val band: CarryRiskBand,
    val suggestion: String,
    val realMonthlyRatePercent: Double,
    val realAnnualRatePercent: Double,
    val fxGapPercent: Double,
    val officialRate: Double,
    val mepRate: Double,
    val referenceTnaPercent: Double,
    val monthlyDevaluationPercent: Double,
    val pesoSharePercent: Double,
    val deviationPp: Double,
    /** Peso target adjusted by the macro band (70% low, strategic target moderate, 40% high). */
    val riskAdjustedTargetPesoPercent: Double,
    val factors: List<MacroRiskFactor>,
) {
    val dollarSharePercent: Double get() = 100.0 - pesoSharePercent
    val riskAdjustedTargetDollarPercent: Double get() = 100.0 - riskAdjustedTargetPesoPercent
}

/** One concrete destination for the next income. */
data class OrganicAllocationLeg(
    val bucket: AllocationBucket,
    val label: String,
    val destination: String,
    val percent: Int,
    val amountCents: Long,
)

/**
 * Organic rebalance: the engine never sells assets, it only allocates new cash.
 *
 * [toPesosCents] + [toDollarsCents] always equal [nextIncomeCents].
 */
data class OrganicRebalancePlan(
    val nextIncomeCents: Long,
    val currentPesoPercent: Double,
    val strategicTargetPesoPercent: Double,
    val riskAdjustedTargetPesoPercent: Double,
    val toPesosCents: Long,
    val toDollarsCents: Long,
    val pesoPercentOfIncome: Int,
    val dollarPercentOfIncome: Int,
    val projectedPesoPercent: Double,
    val riskBand: CarryRiskBand,
    val headline: String,
    val detail: String,
    val legs: List<OrganicAllocationLeg>,
    val isBalancedAfterIncome: Boolean,
)
