package com.smartinvest.domain.model

import java.time.LocalDate

/**
 * One class of an Argentine mutual fund (FCI) as published by the open
 * [compararfondos.com.ar](https://compararfondos.com.ar) API, which consolidates
 * CAFCI/CNV data. Monetary values are native to the fund currency (ARS or USD).
 */
data class FundCatalogEntry(
    val name: String,
    val manager: String = "",
    val type: String = "",
    val currency: String = "ARS",
    val category: String = "",
    val patrimony: Double = 0.0,
    val vcp: Double = 0.0,
    val vcpDate: LocalDate? = null,
    val return7: Double? = null,
    val return30: Double? = null,
    val return365: Double? = null,
    /** 90-day volatility published by the source, in percent. */
    val volatility90: Double? = null,
    /** 90-day maximum drawdown published by the source, in percent (negative). */
    val maxDrop90: Double? = null,
    /** 90-day share of positive days, in percent. */
    val positiveDays90: Double? = null,
) {
    val isMoneyMarket: Boolean get() = type.equals("MM", ignoreCase = true)
    val isDollar: Boolean get() = currency.equals("USD", ignoreCase = true)
}

data class FundPoint(
    val date: LocalDate,
    val value: Double,
)

/** Metrics calculated from the daily value-per-unit (VCP) series over a calendar window. */
data class FundWindowMetrics(
    val windowDays: Int,
    val observations: Int,
    val totalReturnPercent: Double,
    val annualizedVolatilityPercent: Double,
    val maxDrawdownPercent: Double,
    val worstDayPercent: Double,
    val bestDayPercent: Double,
    val positiveDayRatioPercent: Double,
    /** False when the source summary has no return for the selected horizon. */
    val returnAvailable: Boolean = true,
)

enum class FundStabilityTier(val label: String) {
    VERY_STABLE("Muy estable"),
    STABLE("Estable"),
    MODERATE("Moderado"),
    SENSITIVE("Sensible"),
    VOLATILE("Volátil"),
    NO_DATA("Sin datos"),
}

/**
 * Stability score in the 0..100 range. Higher is better and prioritises the
 * maximum drawdown and the volatility over raw return, so a fund that never
 * falls abruptly ranks above a more profitable but jumpy one.
 */
data class FundStabilityScore(
    val score: Double,
    val tier: FundStabilityTier,
    val drawdownScore: Double = 0.0,
    val volatilityScore: Double = 0.0,
    val worstDayScore: Double = 0.0,
    val consistencyScore: Double = 0.0,
    /** False when the source lacks the 90-day data needed to estimate stability. */
    val hasData: Boolean = true,
)

data class FundEvaluation(
    val name: String,
    val catalog: FundCatalogEntry?,
    val history: List<FundPoint>,
    val windowDays: Int,
    val metrics: FundWindowMetrics,
    val stability: FundStabilityScore,
    /**
     * True when the metrics come from the catalog summary (90-day volatility,
     * drawdown and positive days) instead of the daily VCP series. Used for the
     * whole-catalog ranking, where loading 2.300+ histories is not practical.
     */
    val fromSummary: Boolean = false,
    /** Inclusive bounds of a user-chosen range; null keeps the rolling [windowDays]. */
    val rangeStart: LocalDate? = null,
    val rangeEnd: LocalDate? = null,
) {
    val hasCustomRange: Boolean get() = rangeStart != null || rangeEnd != null

    val manager: String get() = catalog?.manager.orEmpty()
    val type: String get() = catalog?.type.orEmpty()
    val currency: String get() = catalog?.currency ?: "ARS"
    val lastDate: LocalDate? get() = history.lastOrNull()?.date
    val observations: Int get() = metrics.observations
}

/** Catalog entry + daily VCP series, before any window is selected. */
data class FundData(
    val name: String,
    val catalog: FundCatalogEntry?,
    val history: List<FundPoint>,
)

/**
 * User-chosen analysis range. Bounds are inclusive and may be open: only a
 * start, only an end, or both. An empty range keeps the rolling preset window.
 */
data class FundDateRange(
    val start: LocalDate? = null,
    val end: LocalDate? = null,
) {
    val isEmpty: Boolean get() = start == null && end == null
}

/** One date shared by the two overlaid funds, normalised to 100 at the start. */
data class FundOverlayPoint(
    val date: LocalDate,
    val primaryIndex: Double,
    val secondaryIndex: Double,
)

/**
 * Two funds superposed over the same dates. Both series start at 100 on the
 * first common observation, so the chart compares shape and not nominal VCP.
 */
data class FundOverlay(
    val primaryName: String,
    val secondaryName: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val observations: Int,
    val points: List<FundOverlayPoint>,
    val primaryReturnPercent: Double,
    val secondaryReturnPercent: Double,
    val primaryDrawdownPercent: Double,
    val secondaryDrawdownPercent: Double,
    val primaryVolatilityPercent: Double,
    val secondaryVolatilityPercent: Double,
    val differencePp: Double,
    val currencyMismatch: Boolean,
    val verdict: String,
)

data class FundSimulation(
    val initialCents: Long,
    val finalCents: Long,
    val gainCents: Long,
    val returnPercent: Double,
    val maxDrawdownPercent: Double,
    val values: List<Long>,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
)

data class FundBenchmarkComparison(
    val windowDays: Int,
    val returnDifferencePp: Double,
    val drawdownDifferencePp: Double,
    val volatilityDifferencePp: Double,
    val stabilityDifference: Double,
    val saferThanBenchmark: Boolean,
    val verdict: String,
)

enum class FundSortOption(val label: String, val shortLabel: String) {
    STABILITY("Estabilidad", "Estabilidad"),
    RETURN("Rentabilidad", "Rent."),
    DRAWDOWN("Máx. caída", "Caída"),
    VOLATILITY("Volatilidad", "Volat."),
}

enum class FundFilterOption(val label: String) {
    ALL("Todos"),
    CLASE_A("Clase A"),
    NO_DRAWDOWN("Sin caídas"),
    CONSISTENT("Días + ≥ 90%"),
}

/**
 * Where the ranking comes from: the classes offered by Eco Valores (default,
 * useful because the rest of the catalog is not reachable from that broker), the
 * curated watchlist, or the full catalog.
 */
enum class FundUniverse(val label: String) {
    ECO_VALORES("Eco Valores"),
    WATCHLIST("Seguimiento"),
    CATALOG("Todo el catálogo"),
}

/**
 * Return horizon available in the catalog summary. Both the 90-day stability
 * metrics and these returns are published for every class, so the whole catalog
 * can be ranked without downloading 2.300+ daily series.
 */
enum class FundReturnHorizon(
    val label: String,
    val shortLabel: String,
    val days: Int,
) {
    WEEK("7 días", "7d", 7),
    MONTH("30 días", "30d", 30),
    YEAR("12 meses", "12m", 365),
    ;

    fun returnOf(entry: FundCatalogEntry): Double? = when (this) {
        WEEK -> entry.return7
        MONTH -> entry.return30
        YEAR -> entry.return365
    }
}
