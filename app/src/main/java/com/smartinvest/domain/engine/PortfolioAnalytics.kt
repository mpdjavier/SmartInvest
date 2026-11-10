package com.smartinvest.domain.engine

import com.smartinvest.domain.model.PerformanceWindow
import com.smartinvest.domain.model.PortfolioAnalytics
import com.smartinvest.domain.model.PortfolioSnapshot
import com.smartinvest.domain.model.PortfolioValuation
import java.time.LocalDate

object PortfolioAnalyticsEngine {

    fun calculate(
        valuation: PortfolioValuation,
        snapshots: List<PortfolioSnapshot>,
        today: LocalDate,
    ): PortfolioAnalytics {
        val ordered = snapshots.sortedBy { it.date }
        val endValue = valuation.totalArsCents

        val week = window(
            label = "7 días",
            days = 7,
            today = today,
            endValue = endValue,
            snapshots = ordered,
        )
        val month = window(
            label = "30 días",
            days = 30,
            today = today,
            endValue = endValue,
            snapshots = ordered,
        )
        val ytd = window(
            label = "YTD",
            days = null,
            startDate = LocalDate.of(today.year, 1, 1),
            today = today,
            endValue = endValue,
            snapshots = ordered,
        )

        val baseline30 = baselineAtOrBefore(ordered, today.minusDays(30))
        val trailingGain = if (baseline30 != null) endValue - baseline30.totalArsCents else 0L
        val perDay = if (baseline30 != null) {
            (trailingGain / 30.0).toLong()
        } else {
            0L
        }

        return PortfolioAnalytics(
            totalArsCents = endValue,
            change7d = week,
            change30d = month,
            ytd = ytd,
            dailyInterestCents = valuation.dailyInterestCents,
            monthlyInterestCents = valuation.monthlyInterestCents,
            trailing30dGainCents = trailingGain,
            trailing30dGainPerDayCents = perDay,
            series = ordered,
        )
    }

    private fun window(
        label: String,
        days: Int?,
        today: LocalDate,
        endValue: Long,
        snapshots: List<PortfolioSnapshot>,
        startDate: LocalDate? = days?.let { today.minusDays(it.toLong()) },
    ): PerformanceWindow {
        val start = startDate
        val baseline = start?.let { baselineAtOrBefore(snapshots, it) }
        val percent = baseline?.let { base ->
            if (base.totalArsCents > 0L) {
                (endValue - base.totalArsCents) * 100.0 / base.totalArsCents
            } else {
                null
            }
        }
        return PerformanceWindow(
            label = label,
            startDate = baseline?.date ?: start,
            startValueCents = baseline?.totalArsCents,
            endDate = today,
            endValueCents = endValue,
            percent = percent,
            gainCents = baseline?.let { endValue - it.totalArsCents },
        )
    }

    private fun baselineAtOrBefore(
        snapshots: List<PortfolioSnapshot>,
        date: LocalDate,
    ): PortfolioSnapshot? = snapshots.lastOrNull { !it.date.isAfter(date) }

    /** Last [days] snapshots for the chart, always chronological. */
    fun seriesForChart(
        snapshots: List<PortfolioSnapshot>,
        today: LocalDate,
        days: Int = 90,
    ): List<PortfolioSnapshot> {
        val from = today.minusDays(days.toLong())
        return snapshots.filter { !it.date.isBefore(from) }.sortedBy { it.date }
    }
}
