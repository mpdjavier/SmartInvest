package com.smartinvest.domain.engine

import com.smartinvest.domain.model.FundCatalogEntry
import com.smartinvest.domain.model.FundData
import com.smartinvest.domain.model.FundFilterOption
import com.smartinvest.domain.model.FundPoint
import com.smartinvest.domain.model.FundReturnHorizon
import com.smartinvest.domain.model.FundSortOption
import com.smartinvest.domain.model.FundStabilityTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FundStabilityEngineTest {

    private val start = LocalDate.of(2026, 1, 1)

    private fun series(values: List<Double>): List<FundPoint> =
        values.mapIndexed { index, value ->
            FundPoint(start.plusDays(index.toLong()), value)
        }

    private fun evaluation(name: String, values: List<Double>, windowDays: Int = 30) =
        FundStabilityEngine.evaluate(
            FundData(name = name, catalog = null, history = series(values)),
            windowDays,
        )!!

    @Test
    fun `metrics compute return drawdown worst day and positive ratio`() {
        val metrics = FundStabilityEngine.metrics(
            series(listOf(100.0, 110.0, 90.0, 105.0, 85.0)),
            windowDays = 30,
        )!!

        assertEquals(5, metrics.observations)
        assertEquals(-15.0, metrics.totalReturnPercent, 0.001)
        assertEquals(-22.73, metrics.maxDrawdownPercent, 0.01)
        assertEquals(-19.05, metrics.worstDayPercent, 0.01)
        assertEquals(16.67, metrics.bestDayPercent, 0.01)
        assertEquals(50.0, metrics.positiveDayRatioPercent, 0.01)
        assertTrue(metrics.annualizedVolatilityPercent > 0.0)
    }

    @Test
    fun `window ignores older drawdowns`() {
        val history = buildList {
            add(FundPoint(start, 100.0))
            for (day in 1..199) add(FundPoint(start.plusDays(day.toLong()), 100.0))
            add(FundPoint(start.plusDays(200), 50.0))
            for (day in 201..400) add(FundPoint(start.plusDays(day.toLong()), 50.0))
        }

        val shortWindow = FundStabilityEngine.metrics(history, 30)!!
        val longWindow = FundStabilityEngine.metrics(history, 365)!!

        assertEquals(0.0, shortWindow.maxDrawdownPercent, 0.001)
        assertEquals(0.0, shortWindow.totalReturnPercent, 0.001)
        assertEquals(-50.0, longWindow.maxDrawdownPercent, 0.01)
        assertEquals(-50.0, longWindow.totalReturnPercent, 0.01)
    }

    @Test
    fun `smooth fund scores higher than a jumpy one`() {
        val smooth = (0..365).map { 100.0 * Math.pow(1.0005, it.toDouble()) }
        val jumpy = listOf(100.0, 130.0, 80.0, 120.0, 70.0, 110.0)

        val stableEvaluation = evaluation("smooth", smooth)
        val jumpyEvaluation = evaluation("jumpy", jumpy)

        assertTrue(stableEvaluation.stability.score > jumpyEvaluation.stability.score)
        assertEquals(FundStabilityTier.VERY_STABLE, stableEvaluation.stability.tier)
        assertEquals(FundStabilityTier.VOLATILE, jumpyEvaluation.stability.tier)
        assertEquals(100.0, stableEvaluation.metrics.positiveDayRatioPercent, 0.001)
    }

    @Test
    fun `score keeps an orderly balanced fund in the stable range`() {
        // +47% with a single -2.83% drawdown and 97.6% positive days approximates
        // Adcap Balanceado III, the evaluator's reference fund.
        val values = mutableListOf<Double>()
        var value = 100.0
        repeat(364) { day ->
            value *= when (day) {
                120 -> 0.9717
                else -> 1.00105
            }
            values += value
        }
        val evaluation = evaluation("balanceado", values, windowDays = 365)

        assertEquals(FundStabilityTier.STABLE, evaluation.stability.tier)
        assertTrue(evaluation.stability.score in 65.0..80.0)
    }

    @Test
    fun `ranking prioritises stability over return`() {
        val stable = evaluation("stable", (0..60).map { 100.0 + it * 0.01 })
        val profitableButJumpy = evaluation("jumpy", listOf(100.0, 110.0, 90.0, 120.0, 80.0, 130.0))

        val byStability = FundStabilityEngine.rank(
            listOf(profitableButJumpy, stable),
            FundSortOption.STABILITY,
        )
        val byReturn = FundStabilityEngine.rank(
            listOf(profitableButJumpy, stable),
            FundSortOption.RETURN,
        )
        val byDrawdown = FundStabilityEngine.rank(
            listOf(profitableButJumpy, stable),
            FundSortOption.DRAWDOWN,
        )
        val byVolatility = FundStabilityEngine.rank(
            listOf(profitableButJumpy, stable),
            FundSortOption.VOLATILITY,
        )

        assertEquals("stable", byStability.first().name)
        assertEquals("jumpy", byReturn.first().name)
        assertEquals("stable", byDrawdown.first().name)
        assertEquals("stable", byVolatility.first().name)
    }

    @Test
    fun `comparison flags a fund that falls less than the benchmark`() {
        val benchmark = evaluation("benchmark", listOf(100.0, 102.0, 101.0, 103.0))
        val saferFund = evaluation("safer", listOf(100.0, 101.0, 100.5, 102.0))

        val comparison = FundStabilityEngine.compare(saferFund, benchmark)

        assertTrue(comparison.saferThanBenchmark)
        assertTrue(comparison.stabilityDifference > 0.0)
        assertTrue(comparison.drawdownDifferencePp > 0.0)
        assertTrue(comparison.verdict.contains("Más estable"))
    }

    @Test
    fun `simulation grows the initial amount with the real vcp path`() {
        val history = series((0..30).map { 100.0 + it })
        val simulation = FundStabilityEngine.simulate(
            history = history,
            initialCents = 1_000_000,
            windowDays = 30,
        )!!

        assertEquals(1_300_000L, simulation.finalCents)
        assertEquals(300_000L, simulation.gainCents)
        assertEquals(30.0, simulation.returnPercent, 0.001)
        assertEquals(0.0, simulation.maxDrawdownPercent, 0.001)
        assertEquals(31, simulation.values.size)
        assertNotNull(simulation.startDate)
        assertNotNull(simulation.endDate)
    }

    @Test
    fun `short histories do not produce metrics or simulations`() {
        val singlePoint = series(listOf(100.0))

        assertNull(FundStabilityEngine.metrics(singlePoint, 30))
        assertNull(FundStabilityEngine.simulate(singlePoint, 1_000_000, 30))
        assertNull(
            FundStabilityEngine.evaluate(
                FundData("one", null, singlePoint),
                30,
            ),
        )
    }

    @Test
    fun `tier thresholds are ordered from very stable to volatile`() {
        assertEquals(FundStabilityTier.VERY_STABLE, FundStabilityEngine.tierFor(85.0))
        assertEquals(FundStabilityTier.STABLE, FundStabilityEngine.tierFor(70.0))
        assertEquals(FundStabilityTier.MODERATE, FundStabilityEngine.tierFor(55.0))
        assertEquals(FundStabilityTier.SENSITIVE, FundStabilityEngine.tierFor(40.0))
        assertEquals(FundStabilityTier.VOLATILE, FundStabilityEngine.tierFor(20.0))
    }

    @Test
    fun `filters keep only funds that match the stability criteria`() {
        val stable = evaluation("stable", (0..60).map { 100.0 + it * 0.01 })
        val jumpy = evaluation("jumpy", listOf(100.0, 130.0, 80.0, 120.0, 70.0, 110.0))

        assertTrue(FundStabilityEngine.matchesFilter(stable, FundFilterOption.ALL))
        assertTrue(FundStabilityEngine.matchesFilter(stable, FundFilterOption.NO_DRAWDOWN))
        assertTrue(FundStabilityEngine.matchesFilter(stable, FundFilterOption.CONSISTENT))

        assertTrue(FundStabilityEngine.matchesFilter(jumpy, FundFilterOption.ALL))
        assertTrue(!FundStabilityEngine.matchesFilter(jumpy, FundFilterOption.NO_DRAWDOWN))
        assertTrue(!FundStabilityEngine.matchesFilter(jumpy, FundFilterOption.CONSISTENT))
    }

    @Test
    fun `stability threshold is optional and respects the score`() {
        val stable = evaluation("stable", (0..120).map { 100.0 * (1.0 + it * 0.0001) })
        val jumpy = evaluation("jumpy", listOf(100.0, 115.0, 95.0, 110.0, 90.0, 105.0))

        assertTrue(FundStabilityEngine.matchesThreshold(stable, 0.0))
        assertTrue(FundStabilityEngine.matchesThreshold(jumpy, 0.0))
        assertTrue(
            FundStabilityEngine.matchesThreshold(stable, stable.stability.score - 0.1),
        )
        assertTrue(
            !FundStabilityEngine.matchesThreshold(stable, stable.stability.score + 5.0),
        )
        assertTrue(!FundStabilityEngine.matchesThreshold(jumpy, 70.0))
    }

    private fun entry(
        name: String,
        return7: Double? = null,
        return30: Double? = null,
        return365: Double? = null,
        volatility: Double? = 8.0,
        maxDrop: Double? = -3.0,
        positiveDays: Double? = 95.0,
    ) = FundCatalogEntry(
        name = name,
        manager = "Gestora",
        type = "MIX",
        currency = "ARS",
        return7 = return7,
        return30 = return30,
        return365 = return365,
        volatility90 = volatility,
        maxDrop90 = maxDrop,
        positiveDays90 = positiveDays,
    )

    @Test
    fun `summary evaluation follows the selected return horizon`() {
        val fund = entry(name = "f", return7 = 0.5, return30 = 2.0, return365 = 40.0)

        val weekly = FundStabilityEngine.evaluateFromSummary(fund, FundReturnHorizon.WEEK)
        val monthly = FundStabilityEngine.evaluateFromSummary(fund, FundReturnHorizon.MONTH)
        val yearly = FundStabilityEngine.evaluateFromSummary(fund, FundReturnHorizon.YEAR)

        assertTrue(weekly.fromSummary)
        assertEquals(90, weekly.windowDays)
        assertEquals(0.5, weekly.metrics.totalReturnPercent, 0.001)
        assertEquals(2.0, monthly.metrics.totalReturnPercent, 0.001)
        assertEquals(40.0, yearly.metrics.totalReturnPercent, 0.001)
        assertEquals(-3.0, yearly.metrics.maxDrawdownPercent, 0.001)
        assertTrue(yearly.stability.hasData)
    }

    @Test
    fun `summary evaluation flags a missing return for the horizon`() {
        val fund = entry(name = "f", return7 = null, return365 = 22.0)
        val weekly = FundStabilityEngine.evaluateFromSummary(fund, FundReturnHorizon.WEEK)

        assertTrue(!weekly.metrics.returnAvailable)
        assertEquals(0.0, weekly.metrics.totalReturnPercent, 0.001)
    }

    @Test
    fun `classes without 90 day metrics get no stability score`() {
        val fund = entry(name = "nuevo", volatility = null)
        val evaluation = FundStabilityEngine.evaluateFromSummary(fund, FundReturnHorizon.YEAR)

        assertTrue(!evaluation.stability.hasData)
        assertEquals(0.0, evaluation.stability.score, 0.001)
        assertEquals(FundStabilityTier.NO_DATA, evaluation.stability.tier)
        assertTrue(!FundStabilityEngine.matchesThreshold(evaluation, 1.0))
        assertTrue(!FundStabilityEngine.matchesFilter(evaluation, FundFilterOption.NO_DRAWDOWN))
    }

    @Test
    fun `catalog ranking by return puts funds without the return last`() {
        val calm = entry(name = "calm", return365 = 10.0, volatility = 1.0, maxDrop = -0.2)
        val profitable = entry(name = "profitable", return365 = 55.0, volatility = 25.0, maxDrop = -20.0)
        val noReturn = entry(name = "no-return", return365 = null)

        val ranked = FundStabilityEngine.rankCatalog(
            entries = listOf(calm, profitable, noReturn),
            horizon = FundReturnHorizon.YEAR,
            sort = FundSortOption.RETURN,
        )

        assertEquals(listOf("profitable", "calm", "no-return"), ranked.map { it.name })
    }

    @Test
    fun `catalog ranking applies the optional stability floor`() {
        val calm = entry(name = "calm", return365 = 30.0, volatility = 0.5, maxDrop = 0.0, positiveDays = 100.0)
        val jumpy = entry(name = "jumpy", return365 = 60.0, volatility = 30.0, maxDrop = -25.0, positiveDays = 40.0)

        val withoutFloor = FundStabilityEngine.rankCatalog(
            entries = listOf(calm, jumpy),
            horizon = FundReturnHorizon.YEAR,
            sort = FundSortOption.RETURN,
        )
        val withFloor = FundStabilityEngine.rankCatalog(
            entries = listOf(calm, jumpy),
            horizon = FundReturnHorizon.YEAR,
            sort = FundSortOption.RETURN,
            minStability = 70.0,
        )

        assertEquals(listOf("jumpy", "calm"), withoutFloor.map { it.name })
        assertEquals(listOf("calm"), withFloor.map { it.name })
        assertTrue(
            FundStabilityEngine.evaluateFromSummary(calm, FundReturnHorizon.YEAR)
                .stability.score >= 70.0,
        )
        assertTrue(
            FundStabilityEngine.evaluateFromSummary(jumpy, FundReturnHorizon.YEAR)
                .stability.score < 70.0,
        )
    }

    @Test
    fun `summary score stays in the same range as the daily score`() {
        val calmSummary = FundStabilityEngine.summaryScore(
            maxDrawdownPercent = 0.0,
            annualizedVolatilityPercent = 0.5,
            positiveDayRatioPercent = 100.0,
        )
        val jumpySummary = FundStabilityEngine.summaryScore(
            maxDrawdownPercent = -30.0,
            annualizedVolatilityPercent = 35.0,
            positiveDayRatioPercent = 40.0,
        )

        assertEquals(FundStabilityTier.VERY_STABLE, calmSummary.tier)
        assertEquals(FundStabilityTier.VOLATILE, jumpySummary.tier)
        assertTrue(calmSummary.score > jumpySummary.score)
    }
}
