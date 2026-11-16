package com.smartinvest.domain.engine

import com.smartinvest.domain.model.CreditCard
import com.smartinvest.domain.model.ExpenseBucket
import com.smartinvest.domain.model.ExpenseFilter
import com.smartinvest.domain.model.ExpenseMonthBucket
import com.smartinvest.domain.model.ExpensePaymentMethod
import com.smartinvest.domain.model.ExpenseRecord
import com.smartinvest.domain.model.ExpenseReport
import com.smartinvest.domain.model.HouseholdCategory
import com.smartinvest.domain.model.HouseholdMember

/**
 * Pure aggregation for expense search and reports. No Android or database types,
 * so the numbers shown on screen and exported to CSV always come from the same rules.
 */
object ExpenseReportEngine {

    fun filter(
        records: List<ExpenseRecord>,
        filter: ExpenseFilter,
        categories: List<HouseholdCategory>,
        members: List<HouseholdMember>,
        cards: List<CreditCard>,
    ): List<ExpenseRecord> {
        if (!filter.hasActiveFilters) return records
        val categoryNames = categories.associate { it.id to "${it.emoji} ${it.name}" }
        val memberNames = members.associate { it.id to it.name }
        val cardNames = cards.associate { it.id to it.name }
        return records.filter { record ->
            filter.matches(
                record = record,
                categoryName = categoryNames[record.categoryId].orEmpty(),
                memberName = memberNames[record.paidByMemberId].orEmpty(),
                cardName = record.creditCardId?.let(cardNames::get),
            )
        }
    }

    fun build(
        records: List<ExpenseRecord>,
        categories: Map<Long, HouseholdCategory>,
        members: Map<Long, HouseholdMember>,
    ): ExpenseReport {
        if (records.isEmpty()) return ExpenseReport()

        val total = records.sumOf { it.impactCents }
        val monthBuckets = monthBuckets(records)
        val installments = records.filter { it.isInstallment }
        val direct = records.filterNot { it.isInstallment }

        return ExpenseReport(
            totalCents = total,
            count = records.size,
            monthsConsidered = monthBuckets.size,
            averagePerMonthCents = if (monthBuckets.isEmpty()) 0L else total / monthBuckets.size,
            averageTicketCents = if (records.isEmpty()) 0L else total / records.size,
            directCents = direct.sumOf { it.impactCents },
            installmentCents = installments.sumOf { it.impactCents },
            installmentCount = installments.size,
            largest = records.maxByOrNull { it.impactCents },
            byCategory = buckets(
                records = records,
                total = total,
                key = { it.categoryId.toString() },
                label = { record ->
                    categories[record.categoryId]?.let { "${it.emoji} ${it.name}" } ?: "Sin categoría"
                },
            ),
            byPaymentMethod = buckets(
                records = records,
                total = total,
                key = { it.paymentMethod.name },
                label = { it.paymentMethod.label },
            ),
            byMember = buckets(
                records = records,
                total = total,
                key = { it.paidByMemberId.toString() },
                label = { record ->
                    members[record.paidByMemberId]?.name ?: "Integrante eliminado"
                },
            ),
            monthly = monthBuckets,
            topExpenses = records
                .sortedWith(compareByDescending<ExpenseRecord> { it.impactCents }.thenByDescending { it.date })
                .distinctBy { it.expenseId }
                .take(5),
        )
    }

    fun monthBuckets(records: List<ExpenseRecord>): List<ExpenseMonthBucket> =
        records
            .groupBy { it.month }
            .map { (month, rows) ->
                ExpenseMonthBucket(
                    month = month,
                    totalCents = rows.sumOf { it.impactCents },
                    count = rows.size,
                )
            }
            .sortedByDescending { it.month }

    /** Maps a bucket total back to its share of [total] (0..100). */
    fun sharePercent(part: Long, total: Long): Double =
        if (total <= 0L) 0.0 else part.toDouble() * 100.0 / total.toDouble()

    private fun buckets(
        records: List<ExpenseRecord>,
        total: Long,
        key: (ExpenseRecord) -> String,
        label: (ExpenseRecord) -> String,
    ): List<ExpenseBucket> = records
        .groupBy(key)
        .map { (bucketKey, rows) ->
            ExpenseBucket(
                key = bucketKey,
                label = label(rows.first()),
                totalCents = rows.sumOf { it.impactCents },
                count = rows.size,
            )
        }
        .sortedWith(compareByDescending<ExpenseBucket> { it.totalCents }.thenBy { it.label })

    /** Kept for callers that want the label of a method without building a bucket. */
    fun paymentMethodLabel(method: ExpensePaymentMethod): String = method.label
}
