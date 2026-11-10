package com.smartinvest.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class InstallmentEngineTest {

    @Test
    fun `splits total without losing cents`() {
        val schedule = InstallmentEngine.schedule(
            totalCents = 100_00,
            installments = 3,
            firstInstallmentMonth = YearMonth.of(2025, 1),
        )
        assertEquals(3, schedule.size)
        assertEquals(100_00L, schedule.sumOf { it.amountCents })
        assertEquals(listOf(3334L, 3333L, 3333L), schedule.map { it.amountCents })
    }

    @Test
    fun `imputes only the installment for the current month`() {
        val schedule = InstallmentEngine.schedule(
            totalCents = 120_000_00,
            installments = 12,
            firstInstallmentMonth = YearMonth.of(2025, 3),
        )
        val march = InstallmentEngine.imputedForMonth(schedule, YearMonth.of(2025, 3))
        assertEquals(10_000_00L, march)
        val april = InstallmentEngine.imputedForMonth(schedule, YearMonth.of(2025, 4))
        assertEquals(10_000_00L, april)
        assertTrue(InstallmentEngine.imputedForMonth(schedule, YearMonth.of(2026, 3)) == 0L)
    }

    @Test
    fun `card cycle moves the purchase after closing to next month`() {
        val first = CardCycleResolver.firstInstallmentMonth(
            purchaseDate = LocalDate.of(2025, 1, 26),
            closingDay = 25,
        )
        assertEquals(YearMonth.of(2025, 2), first)

        val sameCycle = CardCycleResolver.firstInstallmentMonth(
            purchaseDate = LocalDate.of(2025, 1, 25),
            closingDay = 25,
        )
        assertEquals(YearMonth.of(2025, 1), sameCycle)
    }

    @Test
    fun `due date follows closing when due day is smaller`() {
        val due = CardCycleResolver.statementDueDate(
            statementMonth = YearMonth.of(2025, 1),
            closingDay = 25,
            dueDay = 10,
        )
        assertEquals(LocalDate.of(2025, 2, 10), due)
    }
}
