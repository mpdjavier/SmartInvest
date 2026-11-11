package com.smartinvest.domain.model

import java.time.LocalDate

/**
 * Macro carry-risk bands. The score is always in 0..100.
 *
 * - LOW (0..30): high real yield and a calm FX gap.
 * - MODERATE (31..65): normal equilibrium.
 * - HIGH (66..100): FX tension, rate compression or a widening gap.
 *
 * The band has hysteresis: once HIGH, it only leaves at 55 or less; once LOW,
 * it only leaves at 35 or more.
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
) {
    /** Informational rows (e.g. portfolio deviation) do not add points. */
    val isInformational: Boolean get() = maxScore == 0
}

/**
 * Liquidity guardrail for the GREEN peso overweight.
 *
 * Only the part of the portfolio above the strategic target is analysed: that
 * overweight must sit in instruments maturing within [maxMonths] months or in
 * immediate-redemption vehicles (T+0 / 24 h).
 */
data class LiquidityGuardrail(
    val overweightCents: Long,
    val liquidPesoCents: Long,
    val illiquidOverweightCents: Long,
    val maxMonths: Int,
    val isSatisfied: Boolean,
    val detail: String,
) {
    companion object {
        fun disabled(maxMonths: Int): LiquidityGuardrail = LiquidityGuardrail(
            overweightCents = 0L,
            liquidPesoCents = 0L,
            illiquidOverweightCents = 0L,
            maxMonths = maxMonths,
            isSatisfied = true,
            detail = "Guardarraíl de liquidez desactivado.",
        )
    }
}

/**
 * Result of the macro carry-risk engine.
 *
 * The index only measures the macro scenario: real rate, FX gap level and
 * speed, and country risk. Portfolio deviation is intentionally excluded from
 * the score and used only to size the flow.
 */
data class CarryRiskAssessment(
    val score: Int,
    val band: CarryRiskBand,
    /** Band before hysteresis and ceilings, useful to explain the state. */
    val rawBand: CarryRiskBand,
    val suggestion: String,
    val realMonthlyRatePercent: Double,
    val realAnnualRatePercent: Double,
    val fxGapPercent: Double,
    val fxGap30dAgoPercent: Double?,
    val fxGapSpeedPp: Double,
    val officialRate: Double,
    val mepRate: Double,
    val referenceTnaPercent: Double,
    val monthlyDevaluationPercent: Double,
    val pesoSharePercent: Double,
    val deviationPp: Double,
    val riskAdjustedTargetPesoPercent: Double,
    val strategicTargetPesoPercent: Double,
    val redTargetPesoPercent: Double,
    val countryRiskPoints: Int,
    val countryRisk30dChange: Int,
    /** Level > 800 or a +150 point move in 30 days caps the band at AMBER. */
    val countryRiskCeilingActive: Boolean,
    /** The gap expanded fast enough in 30 days to cap the band at AMBER. */
    val fxGapSpeedCeilingActive: Boolean,
    val hysteresisApplied: Boolean,
    val previousBand: CarryRiskBand?,
    /** Peso ceiling for the GREEN state imposed by the electoral calendar. */
    val electoralCapPesoPercent: Double,
    val electoralCapApplied: Boolean,
    val liquidityGuardrail: LiquidityGuardrail,
    val factors: List<MacroRiskFactor>,
) {
    val dollarSharePercent: Double get() = 100.0 - pesoSharePercent
    val riskAdjustedTargetDollarPercent: Double get() = 100.0 - riskAdjustedTargetPesoPercent
    val countryRiskConfigured: Boolean get() = countryRiskPoints > 0
}

/** One concrete destination for the next income. */
data class OrganicAllocationLeg(
    val bucket: AllocationBucket,
    val label: String,
    val destination: String,
    val percent: Int,
    val amountCents: Long,
)

/** A maturity that is organic flow: it can be renewed according to the band. */
data class MaturityFlowLine(
    val symbol: String,
    val name: String,
    val maturityDate: LocalDate,
    val amountCents: Long,
    val toPesosCents: Long,
    val toDollarsCents: Long,
)

/**
 * Organic rebalance: the engine never sells assets, it only allocates new cash.
 *
 * [toPesosCents] + [toDollarsCents] always equal [nextIncomeCents]. Upcoming
 * maturities are treated as extra organic flow so they do not get renewed by
 * inertia in the same currency.
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
    val maturityFlows: List<MaturityFlowLine> = emptyList(),
    val upcomingMaturityCents: Long = 0L,
    val maturityToPesosCents: Long = 0L,
    val maturityToDollarsCents: Long = 0L,
    val maturityDetail: String = "",
    /** Months of the next income needed to close the gap to the risk-adjusted target. */
    val monthsToRealign: Double? = null,
)
