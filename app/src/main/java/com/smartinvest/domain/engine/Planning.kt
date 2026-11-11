package com.smartinvest.domain.engine

import com.smartinvest.core.money.MoneyMath
import com.smartinvest.domain.model.AllocationBucket
import com.smartinvest.domain.model.AppSettings
import com.smartinvest.domain.model.SalaryAllocationLine
import com.smartinvest.domain.model.SalaryPlan
import java.time.YearMonth

/**
 * Salary flow:
 * net salary − configurable 24/7 liquidity = investable remainder.
 *
 * The remainder follows the 5-line scheme. The peso sleeve is split between the
 * carry FCI (T+1) and fixed-rate letters; the dollar sleeve between direct MEP
 * and sovereign bonds as the floor. The peso/dollar split comes from the
 * risk-adjusted target of the carry semaphore when available.
 */
object SalaryAllocationPlanner {

    fun plan(
        month: YearMonth,
        netSalaryCents: Long,
        householdMonthlySpendCents: Long,
        liquidEmergencyCents: Long,
        settings: AppSettings,
        targetPesoPercent: Double = settings.rebalanceTargetPesoPercent.toDouble(),
    ): SalaryPlan {
        val salary = netSalaryCents.coerceAtLeast(0)
        val cushion = householdMonthlySpendCents.coerceAtLeast(0)
        val configuredLiquidity = settings.salaryLiquidityCents.coerceAtLeast(0)
        val liquidity = if (configuredLiquidity > 0L) {
            configuredLiquidity.coerceAtMost(salary)
        } else {
            cushion.coerceAtMost(salary)
        }
        val investable = (salary - liquidity).coerceAtLeast(0)
        val deficit = (cushion - salary).coerceAtLeast(0)

        val pesoPercent = targetPesoPercent.coerceIn(0.0, 100.0)
        val pesoCents = (investable * pesoPercent / 100.0).toLong().coerceIn(0L, investable)
        val dollarCents = investable - pesoCents

        val pesoWeights = safeWeights(settings.salaryFciPercent, settings.salaryLecapPercent)
        val dollarWeights = safeWeights(settings.salaryMepPercent, settings.salaryBondPercent)
        val pesoParts = MoneyMath.allocateByWeights(pesoCents, pesoWeights)
        val dollarParts = MoneyMath.allocateByWeights(dollarCents, dollarWeights)

        val pesoDisplay = displayPercents(pesoWeights, pesoCents, investable)
        val dollarDisplay = displayPercents(dollarWeights, dollarCents, investable)

        val lines = listOf(
            SalaryAllocationLine(
                bucket = AllocationBucket.LIQUIDITY,
                percent = percentOf(liquidity, salary),
                amountCents = liquidity,
                isFixedAmount = true,
            ),
            SalaryAllocationLine(
                bucket = AllocationBucket.CARRY_FCI,
                percent = pesoDisplay[0],
                amountCents = pesoParts[0],
            ),
            SalaryAllocationLine(
                bucket = AllocationBucket.FIXED_RATE,
                percent = pesoDisplay[1],
                amountCents = pesoParts[1],
            ),
            SalaryAllocationLine(
                bucket = AllocationBucket.MEP,
                percent = dollarDisplay[0],
                amountCents = dollarParts[0],
            ),
            SalaryAllocationLine(
                bucket = AllocationBucket.SOVEREIGN_BOND,
                percent = dollarDisplay[1],
                amountCents = dollarParts[1],
            ),
        )

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

    private fun safeWeights(first: Int, second: Int): List<Int> {
        val clean = listOf(first.coerceAtLeast(0), second.coerceAtLeast(0))
        return if (clean.sum() > 0) clean else listOf(1, 1)
    }

    /** Shows each line as a share of the investable remainder (sums to ~100%). */
    private fun displayPercents(weights: List<Int>, sleeveCents: Long, investable: Long): List<Int> {
        if (investable <= 0L) return weights.map { 0 }
        val total = weights.sum().coerceAtLeast(1)
        return weights.map { weight -> percentOf(sleeveCents * weight, investable.toLong() * total) }
    }

    private fun percentOf(partCents: Long, totalCents: Long): Int =
        if (totalCents <= 0L) 0 else ((partCents * 100.0 / totalCents) + 0.5).toInt().coerceIn(0, 100)
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
