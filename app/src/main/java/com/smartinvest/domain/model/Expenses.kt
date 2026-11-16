package com.smartinvest.domain.model

import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** Inclusive month range used by the expense search and the reports. */
data class YearMonthRange(
    val from: YearMonth,
    val to: YearMonth,
) {
    fun contains(month: YearMonth): Boolean = !month.isBefore(from) && !month.isAfter(to)

    companion object {
        val ALL: YearMonthRange = YearMonthRange(YearMonth.of(1970, 1), YearMonth.of(2200, 12))
    }
}

/** Quick period presets shared by the search screen and the reports. */
enum class ExpensePeriod(val label: String, val fileSlug: String) {
    CURRENT_MONTH("Este mes", "mes-actual"),
    LAST_3_MONTHS("Últimos 3 meses", "ultimos-3-meses"),
    CURRENT_YEAR("Este año", "anio-actual"),
    ALL("Todo el historial", "historial");

    fun range(today: YearMonth = YearMonth.now()): YearMonthRange = when (this) {
        CURRENT_MONTH -> YearMonthRange(today, today)
        LAST_3_MONTHS -> YearMonthRange(today.minusMonths(2), today)
        CURRENT_YEAR -> YearMonthRange(YearMonth.of(today.year, 1), YearMonth.of(today.year, 12))
        ALL -> YearMonthRange.ALL
    }
}

/**
 * A single expense as it impacts one month.
 *
 * Cash-like expenses appear once, in their own month. Credit purchases appear once
 * per installment, with [month] set to the installment due month and [impactCents]
 * to the installment amount. [totalCents] is always the full purchase.
 */
data class ExpenseRecord(
    val expenseId: Long,
    val installmentId: Long? = null,
    val description: String,
    val notes: String = "",
    val date: LocalDate,
    val month: YearMonth,
    val totalCents: Long,
    val impactCents: Long,
    val categoryId: Long,
    val paidByMemberId: Long,
    val paymentMethod: ExpensePaymentMethod,
    val creditCardId: Long? = null,
    val installmentNumber: Int? = null,
    val installmentCount: Int? = null,
    val installmentPaid: Boolean? = null,
) {
    val isInstallment: Boolean get() = installmentNumber != null

    val installmentLabel: String?
        get() = if (installmentNumber != null && installmentCount != null) {
            "Cuota $installmentNumber/$installmentCount"
        } else {
            null
        }
}

/**
 * Filters applied to [ExpenseRecord]s.
 *
 * Empty ids (null) mean "no filter". The free-text query matches description, notes,
 * category, member, card and payment method, ignoring case and accents.
 */
data class ExpenseFilter(
    val query: String = "",
    val categoryId: Long? = null,
    val paymentMethod: ExpensePaymentMethod? = null,
    val memberId: Long? = null,
    val period: ExpensePeriod = ExpensePeriod.CURRENT_MONTH,
) {
    val hasActiveFilters: Boolean
        get() = query.isNotBlank() || categoryId != null ||
            paymentMethod != null || memberId != null

    fun matches(
        record: ExpenseRecord,
        categoryName: String,
        memberName: String,
        cardName: String?,
    ): Boolean {
        if (categoryId != null && record.categoryId != categoryId) return false
        if (paymentMethod != null && record.paymentMethod != paymentMethod) return false
        if (memberId != null && record.paidByMemberId != memberId) return false
        val term = query.normalized()
        if (term.isEmpty()) return true
        val haystack = buildString {
            append(record.description)
            append(' ')
            append(record.notes)
            append(' ')
            append(record.installmentLabel.orEmpty())
            append(' ')
            append(categoryName)
            append(' ')
            append(memberName)
            append(' ')
            append(cardName.orEmpty())
            append(' ')
            append(record.paymentMethod.label)
        }.normalized()
        return haystack.contains(term)
    }
}

/** One aggregated slice (category, payment method or member). */
data class ExpenseBucket(
    val key: String,
    val label: String,
    val totalCents: Long,
    val count: Int,
) {
    fun shareOf(total: Long): Double =
        if (total <= 0L) 0.0 else totalCents.toDouble() * 100.0 / total.toDouble()
}

data class ExpenseMonthBucket(
    val month: YearMonth,
    val totalCents: Long,
    val count: Int,
)

/**
 * Ready-to-render summary of the expenses in a period.
 * All amounts are already the monthly impact (installments use the installment value).
 */
data class ExpenseReport(
    val totalCents: Long = 0L,
    val count: Int = 0,
    val monthsConsidered: Int = 0,
    val averagePerMonthCents: Long = 0L,
    val averageTicketCents: Long = 0L,
    val directCents: Long = 0L,
    val installmentCents: Long = 0L,
    val installmentCount: Int = 0,
    val largest: ExpenseRecord? = null,
    val byCategory: List<ExpenseBucket> = emptyList(),
    val byPaymentMethod: List<ExpenseBucket> = emptyList(),
    val byMember: List<ExpenseBucket> = emptyList(),
    val monthly: List<ExpenseMonthBucket> = emptyList(),
    val topExpenses: List<ExpenseRecord> = emptyList(),
) {
    val isEmpty: Boolean get() = count == 0
}

private val accentMarks = Regex("\\p{M}+")

private fun String.normalized(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(accentMarks, "")
        .lowercase(Locale.ROOT)
        .trim()
