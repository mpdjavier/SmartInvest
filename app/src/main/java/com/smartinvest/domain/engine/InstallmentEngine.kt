package com.smartinvest.domain.engine

import com.smartinvest.core.money.MoneyMath
import java.time.LocalDate
import java.time.YearMonth

/** Manual card cycle configuration: closing day and due day are user-defined. */
object CardCycleResolver {

    fun closingDate(month: YearMonth, closingDay: Int): LocalDate {
        val day = closingDay.coerceIn(1, month.lengthOfMonth())
        return month.atDay(day)
    }

    /**
     * Purchases made after the closing date are charged to the next statement.
     * Purchases on the closing day still belong to the current cycle.
     */
    fun firstInstallmentMonth(purchaseDate: LocalDate, closingDay: Int): YearMonth {
        val purchaseMonth = YearMonth.from(purchaseDate)
        val closing = closingDate(purchaseMonth, closingDay)
        return if (purchaseDate.isAfter(closing)) purchaseMonth.plusMonths(1) else purchaseMonth
    }

    /**
     * Due date for a statement. If the due day is on/before the closing day the
     * payment is due the following month (e.g. closes 25, due 10).
     */
    fun statementDueDate(statementMonth: YearMonth, closingDay: Int, dueDay: Int): LocalDate {
        val closing = closingDate(statementMonth, closingDay)
        val sameMonthDue = closingDate(statementMonth, dueDay)
        return if (sameMonthDue.isAfter(closing)) {
            sameMonthDue
        } else {
            closingDate(statementMonth.plusMonths(1), dueDay)
        }
    }

    fun nextClosingDate(today: LocalDate, closingDay: Int): LocalDate {
        val thisMonth = closingDate(YearMonth.from(today), closingDay)
        return if (!today.isAfter(thisMonth)) thisMonth else closingDate(YearMonth.from(today).plusMonths(1), closingDay)
    }

    fun nextDueDate(today: LocalDate, dueDay: Int): LocalDate {
        val thisMonth = closingDate(YearMonth.from(today), dueDay)
        return if (!today.isAfter(thisMonth)) thisMonth else closingDate(YearMonth.from(today).plusMonths(1), dueDay)
    }
}

data class InstallmentSlice(
    val number: Int,
    val count: Int,
    val dueMonth: YearMonth,
    val amountCents: Long,
)

/**
 * Purchase-in-installments engine. The current month is only charged the
 * installment amount, never the full purchase. Cents are distributed without loss.
 */
object InstallmentEngine {

    const val MIN_INSTALLMENTS = 1
    const val MAX_INSTALLMENTS = 60

    fun schedule(
        totalCents: Long,
        installments: Int,
        firstInstallmentMonth: YearMonth,
    ): List<InstallmentSlice> {
        require(totalCents > 0) { "total must be positive" }
        require(installments in MIN_INSTALLMENTS..MAX_INSTALLMENTS) { "invalid installment count" }
        val amounts = MoneyMath.allocate(totalCents, installments)
        return amounts.mapIndexed { index, amount ->
            InstallmentSlice(
                number = index + 1,
                count = installments,
                dueMonth = firstInstallmentMonth.plusMonths(index.toLong()),
                amountCents = amount,
            )
        }
    }

    /** Total imputed to a given month by a schedule. */
    fun imputedForMonth(schedule: List<InstallmentSlice>, month: YearMonth): Long =
        schedule.filter { it.dueMonth == month }.sumOf { it.amountCents }
}
