package com.smartinvest.domain.engine

import com.smartinvest.core.money.MoneyMath
import com.smartinvest.domain.model.AllocationBucket
import com.smartinvest.domain.model.AppSettings
import com.smartinvest.domain.model.SalaryAllocationLine
import com.smartinvest.domain.model.SalaryPlan
import java.time.YearMonth

/**
 * Salary flow:
 * net salary − real household cushion = investable remainder.
 * The remainder is split between FCI T+0, fixed-rate letters and MEP coverage.
 */
object SalaryAllocationPlanner {

    fun plan(
        month: YearMonth,
        netSalaryCents: Long,
        householdMonthlySpendCents: Long,
        liquidEmergencyCents: Long,
        settings: AppSettings,
    ): SalaryPlan {
        val salary = netSalaryCents.coerceAtLeast(0)
        val cushion = householdMonthlySpendCents.coerceAtLeast(0)
        val investable = (salary - cushion).coerceAtLeast(0)
        val deficit = (cushion - salary).coerceAtLeast(0)

        val rawWeights = settings.salaryWeights
        val weights = if (rawWeights.sum() > 0) rawWeights else listOf(50, 30, 20)

        val displayPercents = MoneyMath.allocateByWeights(100, weights)
        val amounts = MoneyMath.allocateByWeights(investable, weights)

        val buckets = listOf(
            AllocationBucket.FCI_T0,
            AllocationBucket.LECAP,
            AllocationBucket.MEP,
        )
        val lines = buckets.mapIndexed { index, bucket ->
            SalaryAllocationLine(
                bucket = bucket,
                percent = displayPercents.getOrElse(index) { 0L }.toInt(),
                amountCents = amounts.getOrElse(index) { 0L },
            )
        }

        val coverage = if (householdMonthlySpendCents > 0L) {
            liquidEmergencyCents.toDouble() / householdMonthlySpendCents.toDouble()
        } else {
            0.0
        }

        return SalaryPlan(
            month = month,
            netSalaryCents = salary,
            householdCushionCents = cushion,
            investableCents = investable,
            deficitCents = deficit,
            allocations = lines,
            emergencyLiquidCents = liquidEmergencyCents,
            emergencyCoverageMonths = coverage,
        )
    }
}

object EmergencyRedemptionPlanner {

    fun simulate(
        requestedCents: Long,
        sources: List<com.smartinvest.domain.model.LiquiditySource>,
    ): com.smartinvest.domain.model.RedemptionPlan {
        val requested = requestedCents.coerceAtLeast(0)
        if (requested == 0L) {
            return com.smartinvest.domain.model.RedemptionPlan(
                requestedCents = 0,
                legs = emptyList(),
                coveredCents = 0,
                shortfallCents = 0,
                maxSettlementDays = 0,
            )
        }

        val ordered = sources
            .filter { it.availableCents > 0 }
            .sortedWith(compareBy({ it.tier.order }, { it.settlementDays }))

        var remaining = requested
        val legs = mutableListOf<com.smartinvest.domain.model.RedemptionLeg>()
        for (source in ordered) {
            if (remaining <= 0) break
            val take = minOf(remaining, source.availableCents)
            if (take > 0) {
                legs += com.smartinvest.domain.model.RedemptionLeg(
                    sourceName = source.name,
                    tier = source.tier,
                    amountCents = take,
                    settlementDays = source.settlementDays,
                    note = source.note,
                )
                remaining -= take
            }
        }

        return com.smartinvest.domain.model.RedemptionPlan(
            requestedCents = requested,
            legs = legs,
            coveredCents = requested - remaining,
            shortfallCents = remaining,
            maxSettlementDays = legs.maxOfOrNull { it.settlementDays } ?: 0,
        )
    }
}
