package com.smartinvest.domain.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Market data fetched automatically from public sources (ArgentinaDatos) and
 * persisted in settings. Every field is optional: when a source fails the engine
 * falls back to the manual settings, so the assistant always has an input.
 */
@Serializable
data class MacroMarketSnapshot(
    val fetchedAtMillis: Long = 0L,
    /** Country-risk level in basis points, e.g. 848. */
    val countryRiskPoints: Int? = null,
    /** Date of the country-risk observation ("YYYY-MM-DD"). */
    val countryRiskDate: String? = null,
    /** Country-risk change over the last 30 days, in points. */
    val countryRisk30dChange: Int? = null,
    /** MEP ÷ wholesale − 1, in percent. */
    val fxGapPercent: Double? = null,
    /** Same gap 14 days ago, in percent. */
    val fxGap14dAgoPercent: Double? = null,
    /** Blue ÷ wholesale − 1, in percent. */
    val blueGapPercent: Double? = null,
    /** Short peso rate: median 30-day fixed-term TNA, in percent. */
    val shortTnaPercent: Double? = null,
    /** Date of the fixed-term rate or of the FX series used ("YYYY-MM-DD"). */
    val shortTnaDate: String? = null,
    val officialRate: Double? = null,
    val mepRate: Double? = null,
    val wholesaleRate: Double? = null,
    val blueRate: Double? = null,
    /** Closing date common to the FX series ("YYYY-MM-DD"). */
    val fxDate: String? = null,
    val sources: List<String> = emptyList(),
) {
    val hasAnyData: Boolean
        get() = countryRiskPoints != null ||
            fxGapPercent != null ||
            blueGapPercent != null ||
            shortTnaPercent != null

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun decode(raw: String): MacroMarketSnapshot {
            val clean = raw.trim()
            if (clean.isEmpty()) return MacroMarketSnapshot()
            return runCatching { json.decodeFromString<MacroMarketSnapshot>(clean) }
                .getOrDefault(MacroMarketSnapshot())
        }

        fun encode(snapshot: MacroMarketSnapshot): String = json.encodeToString(snapshot)
    }
}

/** One scored component of the carry index, with the numbers behind it. */
data class CarryRiskComponent(
    val key: String,
    val label: String,
    val points: Int,
    val maxPoints: Int,
    /** Short value used as input, e.g. "Brecha 4,4%". */
    val input: String,
    /** Thresholds that define the points, e.g. "0 si <5% · 15 si 5–12% · 30 si >12%". */
    val rule: String,
    /** Full explanation with the exact numbers. */
    val detail: String,
    val isInformational: Boolean = false,
)

/** One raw datum used by the index, with its source and timestamp. */
data class CarryRiskDataPoint(
    val label: String,
    val value: String,
    val source: String,
    val asOf: String? = null,
    /** True when the value comes from a manual setting instead of an automatic source. */
    val isFallback: Boolean = false,
)

/** Everything needed to explain the index in detail. */
data class CarryRiskBreakdown(
    val components: List<CarryRiskComponent> = emptyList(),
    val dataPoints: List<CarryRiskDataPoint> = emptyList(),
    /** Sum of the components before the 100-point cap. */
    val scoreBeforeCap: Int = 0,
    /** Reason the index was capped at 100, or the band ceiling applied. */
    val scoreCapNote: String? = null,
    /** Notes about hysteresis, ceilings and the electoral calendar. */
    val notes: List<String> = emptyList(),
)

/**
 * Macro carry-risk bands. The score is always in 0..100.
 *
 * - LOW (0..30): carry favorable.
 * - MODERATE (31..65): equilibrium.
 * - HIGH (66..100): defensive.
 *
 * Hysteresis only delays improvements: RED is left at ≤55 and GREEN is entered
 * from AMBER at ≤20 (10 points below the entry threshold of 30).
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
 * The index only measures market variables: MEP gap level, gap speed, blue gap,
 * carry cushion and country risk. Portfolio deviation is intentionally excluded
 * from the score and used only to size the flow.
 */
data class CarryRiskAssessment(
    val score: Int,
    val band: CarryRiskBand,
    /** Band before hysteresis and ceilings, useful to explain the state. */
    val rawBand: CarryRiskBand,
    val suggestion: String,
    val realMonthlyRatePercent: Double,
    val realAnnualRatePercent: Double,
    /** TEM short rate minus the monthly crawl, in percentage points. */
    val carryCushionPercent: Double,
    /** Short peso TEM used for the cushion, in percent. */
    val shortTemPercent: Double,
    val fxGapPercent: Double,
    /** MEP gap 14 days ago, in percent (null when there is no history). */
    val fxGapPreviousPercent: Double?,
    val fxGapSpeedPp: Double,
    val blueGapPercent: Double,
    val officialRate: Double,
    val mepRate: Double,
    val wholesaleRate: Double,
    val blueRate: Double,
    val referenceTnaPercent: Double,
    val monthlyDevaluationPercent: Double,
    val pesoSharePercent: Double,
    val deviationPp: Double,
    val riskAdjustedTargetPesoPercent: Double,
    val strategicTargetPesoPercent: Double,
    val redTargetPesoPercent: Double,
    val countryRiskPoints: Int,
    val countryRisk30dChange: Int,
    val countryRiskDate: String?,
    /** The +10 for the 30-day move is active (entry at +150, exit at +75). */
    val countryRiskTrendActive: Boolean,
    /** Level > 800 or an active trend caps the band at AMBER. */
    val countryRiskCeilingActive: Boolean,
    val hysteresisApplied: Boolean,
    val previousBand: CarryRiskBand?,
    /** Peso ceiling for the GREEN state imposed by the electoral calendar. */
    val electoralCapPesoPercent: Double,
    val electoralCapApplied: Boolean,
    val liquidityGuardrail: LiquidityGuardrail,
    val factors: List<MacroRiskFactor>,
    /** Timestamp of the market snapshot used, when it came from an automatic source. */
    val marketFetchedAtMillis: Long? = null,
    val breakdown: CarryRiskBreakdown = CarryRiskBreakdown(),
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
