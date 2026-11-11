package com.smartinvest.domain.model

/** Kind of position shown in the dollar break-even report. */
enum class BreakEvenKind(val label: String) {
    WALLET("Billetera"),
    HOLDING("Activo"),
}

/** Where the annual rate used for the projection comes from. */
enum class BreakEvenRateSource(val label: String) {
    OWN_TNA("TNA propia"),
    REFERENCE_TNA("TNA de referencia"),
    NO_RATE("Sin tasa"),
    ALREADY_USD("Ya en dólares"),
}

/**
 * A single horizon of the dollar break-even calculation.
 *
 * [breakEvenMepRate] is the MEP rate at which buying dollars today would tie the
 * instrument's projected return. Above it, buying dollars wins; below it, the
 * instrument wins.
 *
 * It is null for positions that are already dollar-denominated: both alternatives
 * scale with the future MEP, so there is no finite exchange rate that makes plain
 * dollars catch up with a USD instrument that earns a positive USD rate.
 */
data class BreakEvenPoint(
    val days: Int,
    val breakEvenMepRate: Double?,
    /** Percentage the MEP must rise over the horizon to reach the break-even rate. */
    val requiredDevaluationPercent: Double?,
    /** Instrument return over the horizon, in the instrument's own currency. */
    val instrumentReturnPercent: Double,
    /** MEP projected with the configured monthly devaluation, for reference. */
    val expectedMepRate: Double,
    /** True when the expected MEP already exceeds the break-even rate. */
    val dollarWinsAtExpectedMep: Boolean?,
)

/**
 * Dollar break-even for one wallet or holding.
 *
 * Formula (peso positions, simple rate consistent with the rest of the app):
 *
 *   breakEvenMep = currentMep × (1 + TNA / 100 × days / 365)
 *
 * The required devaluation is therefore `TNA × days / 365`.
 */
data class InstrumentBreakEven(
    val id: String,
    val kind: BreakEvenKind,
    val name: String,
    val detail: String,
    val currency: CurrencyCode,
    val valueArsCents: Long,
    val annualRatePercent: Double,
    val rateSource: BreakEvenRateSource,
    /** True for USD positions and CEDEARs (already dollar exposure). */
    val isDollarPosition: Boolean,
    val note: String,
    val points: List<BreakEvenPoint>,
) {
    fun point(days: Int): BreakEvenPoint? = points.firstOrNull { it.days == days }
}

/** Weighted break-even of the whole peso sleeve, used as a portfolio reference. */
data class PortfolioBreakEvenPoint(
    val days: Int,
    val breakEvenMepRate: Double?,
    val requiredDevaluationPercent: Double?,
)

data class DollarBreakEvenReport(
    val mepRate: Double,
    val mepIsEstimated: Boolean,
    val monthlyDevaluationPercent: Double,
    val horizons: List<Int>,
    val items: List<InstrumentBreakEven>,
    val pesoSummary: List<PortfolioBreakEvenPoint> = emptyList(),
) {
    val hasItems: Boolean get() = items.isNotEmpty()

    fun item(id: String): InstrumentBreakEven? = items.firstOrNull { it.id == id }

    fun wallet(walletId: Long): InstrumentBreakEven? = item("wallet:$walletId")

    fun holding(holdingId: Long): InstrumentBreakEven? = item("holding:$holdingId")

    companion object {
        val DEFAULT_HORIZONS: List<Int> = listOf(30, 60, 120)
    }
}
