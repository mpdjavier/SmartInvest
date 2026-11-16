package com.smartinvest.domain.engine

import com.smartinvest.domain.model.FundBenchmarkComparison
import com.smartinvest.domain.model.FundCatalogEntry
import com.smartinvest.domain.model.FundData
import com.smartinvest.domain.model.FundEvaluation
import com.smartinvest.domain.model.FundFilterOption
import com.smartinvest.domain.model.FundPoint
import com.smartinvest.domain.model.FundReturnHorizon
import com.smartinvest.domain.model.FundSimulation
import com.smartinvest.domain.model.FundSortOption
import com.smartinvest.domain.model.FundStabilityScore
import com.smartinvest.domain.model.FundStabilityTier
import com.smartinvest.domain.model.FundWindowMetrics
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * Pure Kotlin evaluator for FCI classes.
 *
 * Computes return and stability metrics (drawdown, volatility, positive days) as
 * independent parameters so funds can be ranked by return or stability with an
 * optional stability threshold.
 */
object FundStabilityEngine {

    val WINDOWS: List<Int> = listOf(30, 90, 180, 365)
    const val DEFAULT_WINDOW: Int = 365
    const val BENCHMARK_NAME: String = "Adcap Balanceado III - Clase A"

    /** Business-day annualisation used by the source and by this app. */
    private const val TRADING_DAYS_PER_YEAR = 252.0

    /** Points inside [windowDays] counted backwards from the last observation. */
    fun windowPoints(history: List<FundPoint>, windowDays: Int): List<FundPoint> {
        if (history.isEmpty()) return emptyList()
        val clean = history
            .filter { it.value > 0.0 && it.value.isFinite() }
            .distinctBy { it.date }
            .sortedBy { it.date }
        if (clean.isEmpty()) return emptyList()
        if (windowDays <= 0) return clean
        val lastDate: LocalDate = clean.last().date
        val cutoff = lastDate.minusDays(windowDays.toLong())
        val selected = clean.filter { !it.date.isBefore(cutoff) }
        return if (selected.size >= 2) selected else clean.takeLast(2)
    }

    fun metrics(history: List<FundPoint>, windowDays: Int): FundWindowMetrics? {
        val points = windowPoints(history, windowDays)
        if (points.size < 2) return null

        val returns = points.zipWithNext { previous, current ->
            current.value / previous.value - 1.0
        }
        val count = returns.size
        val mean = returns.average()
        val variance = if (count > 1) {
            returns.sumOf { (it - mean).pow(2) } / (count - 1)
        } else {
            0.0
        }
        val volatility = sqrt(variance) * sqrt(TRADING_DAYS_PER_YEAR) * 100.0

        var peak = points.first().value
        var maxDrawdown = 0.0
        for (point in points) {
            if (point.value > peak) peak = point.value
            val drawdown = point.value / peak - 1.0
            if (drawdown < maxDrawdown) maxDrawdown = drawdown
        }

        val totalReturn = (points.last().value / points.first().value - 1.0) * 100.0
        val worstDay = (returns.minOrNull() ?: 0.0) * 100.0
        val bestDay = (returns.maxOrNull() ?: 0.0) * 100.0
        val positiveRatio = if (count > 0) {
            returns.count { it > 0.0 }.toDouble() / count * 100.0
        } else {
            0.0
        }

        return FundWindowMetrics(
            windowDays = windowDays,
            observations = points.size,
            totalReturnPercent = totalReturn.roundTo(4),
            annualizedVolatilityPercent = volatility.roundTo(2),
            maxDrawdownPercent = (maxDrawdown * 100.0).roundTo(2),
            worstDayPercent = worstDay.roundTo(2),
            bestDayPercent = bestDay.roundTo(2),
            positiveDayRatioPercent = positiveRatio.roundTo(1),
        )
    }

    fun score(metrics: FundWindowMetrics): FundStabilityScore {
        val drawdown = abs(metrics.maxDrawdownPercent)
        val volatility = abs(metrics.annualizedVolatilityPercent)
        val worstDay = abs(metrics.worstDayPercent)

        // Smooth penalties: 0% drawdown / volatility / worst day -> 100 points.
        val drawdownScore = 100.0 / (1.0 + drawdown / 8.0)
        val volatilityScore = 100.0 / (1.0 + volatility / 12.0)
        val worstDayScore = 100.0 / (1.0 + worstDay / 2.5)
        val consistencyScore = metrics.positiveDayRatioPercent.coerceIn(0.0, 100.0)

        val score = drawdownScore * 0.40 +
            volatilityScore * 0.28 +
            worstDayScore * 0.14 +
            consistencyScore * 0.18

        return FundStabilityScore(
            score = score.roundTo(1),
            tier = tierFor(score),
            drawdownScore = drawdownScore.roundTo(1),
            volatilityScore = volatilityScore.roundTo(1),
            worstDayScore = worstDayScore.roundTo(1),
            consistencyScore = consistencyScore.roundTo(1),
        )
    }

    fun tierFor(score: Double): FundStabilityTier = when {
        score >= 80.0 -> FundStabilityTier.VERY_STABLE
        score >= 65.0 -> FundStabilityTier.STABLE
        score >= 50.0 -> FundStabilityTier.MODERATE
        score >= 35.0 -> FundStabilityTier.SENSITIVE
        else -> FundStabilityTier.VOLATILE
    }

    /**
     * Stability score estimated from the catalog's 90-day summary. The source does
     * not publish the worst day there, so its 14% weight is redistributed across
     * drawdown, volatility and consistency (40/28/18 renormalised to 86%).
     */
    fun summaryScore(
        maxDrawdownPercent: Double,
        annualizedVolatilityPercent: Double,
        positiveDayRatioPercent: Double,
    ): FundStabilityScore {
        val drawdownScore = 100.0 / (1.0 + abs(maxDrawdownPercent) / 8.0)
        val volatilityScore = 100.0 / (1.0 + abs(annualizedVolatilityPercent) / 12.0)
        val consistencyScore = positiveDayRatioPercent.coerceIn(0.0, 100.0)

        val score = (drawdownScore * 0.40 +
            volatilityScore * 0.28 +
            consistencyScore * 0.18) / 0.86

        return FundStabilityScore(
            score = score.roundTo(1),
            tier = tierFor(score),
            drawdownScore = drawdownScore.roundTo(1),
            volatilityScore = volatilityScore.roundTo(1),
            consistencyScore = consistencyScore.roundTo(1),
        )
    }

    /**
     * Builds an evaluation for any class using only the catalog summary. Returns
     * are taken from the selected [horizon]; stability always uses the 90-day
     * volatility, drawdown and positive-day share.
     */
    fun evaluateFromSummary(
        entry: FundCatalogEntry,
        horizon: FundReturnHorizon,
    ): FundEvaluation {
        val publishedReturn = horizon.returnOf(entry)
        val volatility = entry.volatility90
        val drawdown = entry.maxDrop90
        val positiveDays = entry.positiveDays90
        val hasData = volatility != null && drawdown != null && positiveDays != null

        val metrics = FundWindowMetrics(
            windowDays = 90,
            observations = 0,
            totalReturnPercent = (publishedReturn ?: 0.0).roundTo(2),
            annualizedVolatilityPercent = (volatility ?: 0.0).roundTo(2),
            maxDrawdownPercent = (drawdown ?: 0.0).roundTo(2),
            worstDayPercent = 0.0,
            bestDayPercent = 0.0,
            positiveDayRatioPercent = (positiveDays ?: 0.0).roundTo(1),
            returnAvailable = publishedReturn != null,
        )
        val stability = if (hasData) {
            summaryScore(
                maxDrawdownPercent = drawdown!!,
                annualizedVolatilityPercent = volatility!!,
                positiveDayRatioPercent = positiveDays!!,
            )
        } else {
            FundStabilityScore(
                score = 0.0,
                tier = FundStabilityTier.NO_DATA,
                hasData = false,
            )
        }

        return FundEvaluation(
            name = entry.name,
            catalog = entry,
            history = emptyList(),
            windowDays = 90,
            metrics = metrics,
            stability = stability,
            fromSummary = true,
        )
    }

    /** Evaluations for the whole catalog, skipping classes without a name. */
    fun rankCatalog(
        entries: List<FundCatalogEntry>,
        horizon: FundReturnHorizon,
        sort: FundSortOption,
        minStability: Double = 0.0,
        filter: FundFilterOption = FundFilterOption.ALL,
    ): List<FundEvaluation> = rank(
        entries
            .asSequence()
            .filter { it.name.isNotBlank() }
            .map { evaluateFromSummary(it, horizon) }
            .filter { matchesThreshold(it, minStability) }
            .filter { matchesFilter(it, filter) }
            .toList(),
        sort,
    )

    fun evaluate(data: FundData, windowDays: Int = DEFAULT_WINDOW): FundEvaluation? {
        val metrics = metrics(data.history, windowDays) ?: return null
        return FundEvaluation(
            name = data.name,
            catalog = data.catalog,
            history = data.history,
            windowDays = windowDays,
            metrics = metrics,
            stability = score(metrics),
        )
    }

    fun rank(evaluations: List<FundEvaluation>, sort: FundSortOption): List<FundEvaluation> =
        when (sort) {
            FundSortOption.STABILITY -> evaluations.sortedWith(
                compareByDescending<FundEvaluation> { it.stability.hasData }
                    .thenByDescending { it.stability.score }
                    .thenByDescending { it.metrics.maxDrawdownPercent }
                    .thenBy { it.metrics.annualizedVolatilityPercent }
                    .thenByDescending { it.metrics.totalReturnPercent },
            )

            FundSortOption.RETURN -> evaluations.sortedWith(
                compareByDescending<FundEvaluation> { it.metrics.returnAvailable }
                    .thenByDescending { it.metrics.totalReturnPercent }
                    .thenByDescending { it.stability.score },
            )

            FundSortOption.DRAWDOWN -> evaluations.sortedWith(
                compareByDescending<FundEvaluation> { it.stability.hasData }
                    .thenByDescending { it.metrics.maxDrawdownPercent },
            )

            FundSortOption.VOLATILITY -> evaluations.sortedWith(
                compareByDescending<FundEvaluation> { it.stability.hasData }
                    .thenBy { it.metrics.annualizedVolatilityPercent },
            )
        }

    /** Optional user threshold: a fund must have data and reach the score. */
    fun matchesThreshold(evaluation: FundEvaluation, minScore: Double): Boolean =
        minScore <= 0.0 || (evaluation.stability.hasData && evaluation.stability.score >= minScore)

    fun matchesFilter(evaluation: FundEvaluation, filter: FundFilterOption): Boolean =
        when (filter) {
            FundFilterOption.ALL -> true
            FundFilterOption.NO_DRAWDOWN ->
                evaluation.stability.hasData && evaluation.metrics.maxDrawdownPercent >= -0.01
            FundFilterOption.CONSISTENT ->
                evaluation.stability.hasData && evaluation.metrics.positiveDayRatioPercent >= 90.0
        }

    fun compare(
        fund: FundEvaluation,
        benchmark: FundEvaluation,
        windowDays: Int = fund.windowDays,
    ): FundBenchmarkComparison {
        val f = fund.metrics
        val b = benchmark.metrics
        val stabilityDifference = fund.stability.score - benchmark.stability.score
        val safer = f.maxDrawdownPercent >= b.maxDrawdownPercent &&
            f.annualizedVolatilityPercent <= b.annualizedVolatilityPercent

        val verdict = when {
            stabilityDifference >= 5.0 && safer ->
                "Más estable que ${benchmark.name}"
            stabilityDifference >= 5.0 ->
                "Mejor puntaje de estabilidad que el benchmark"
            stabilityDifference <= -5.0 ->
                "Menos estable que el benchmark"
            safer ->
                "Estabilidad similar, con menor caída y volatilidad"
            else ->
                "Estabilidad similar al benchmark"
        }

        return FundBenchmarkComparison(
            windowDays = windowDays,
            returnDifferencePp = (f.totalReturnPercent - b.totalReturnPercent).roundTo(2),
            drawdownDifferencePp = (f.maxDrawdownPercent - b.maxDrawdownPercent).roundTo(2),
            volatilityDifferencePp = (f.annualizedVolatilityPercent - b.annualizedVolatilityPercent).roundTo(2),
            stabilityDifference = stabilityDifference.roundTo(1),
            saferThanBenchmark = safer,
            verdict = verdict,
        )
    }

    fun simulate(
        history: List<FundPoint>,
        initialCents: Long,
        windowDays: Int = DEFAULT_WINDOW,
    ): FundSimulation? {
        if (initialCents <= 0L) return null
        val points = windowPoints(history, windowDays)
        if (points.size < 2) return null
        val base = points.first().value
        if (base <= 0.0) return null

        val values = points.map { point ->
            (initialCents.toDouble() * point.value / base).roundToLong()
        }
        val finalCents = values.last()
        val gainCents = finalCents - initialCents
        val returnPercent = (finalCents.toDouble() / initialCents - 1.0) * 100.0
        val metrics = metrics(history, windowDays)

        return FundSimulation(
            initialCents = initialCents,
            finalCents = finalCents,
            gainCents = gainCents,
            returnPercent = returnPercent.roundTo(2),
            maxDrawdownPercent = metrics?.maxDrawdownPercent ?: 0.0,
            values = values,
            startDate = points.first().date,
            endDate = points.last().date,
        )
    }

    private fun Double.roundTo(decimals: Int): Double {
        if (!isFinite()) return 0.0
        val factor = 10.0.pow(decimals)
        return round(this * factor) / factor
    }
}
