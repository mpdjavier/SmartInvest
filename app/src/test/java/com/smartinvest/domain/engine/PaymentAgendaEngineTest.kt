package com.smartinvest.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class PaymentAgendaEngineTest {

    @Test
    fun `takes previous payment date by default and detects due soon status`() {
        val expenses = listOf(
            HistoricalExpenseRecord(
                id = 1,
                description = "Pago de SM Tucumán CISI",
                amountCents = 15_105_73L,
                date = LocalDate.of(2026, 10, 10),
                categoryId = 2L,
            ),
        )

        val summary = PaymentAgendaEngine.buildForMonth(
            month = YearMonth.of(2026, 11),
            today = LocalDate.of(2026, 11, 8),
            expenses = expenses,
            serviceCategoryIds = setOf(2L),
            templates = emptyList(),
        )

        assertEquals(1, summary.rows.size)
        val row = summary.rows.first()
        assertEquals("SM Tucumán CISI", row.name)
        assertEquals(LocalDate.of(2026, 11, 10), row.scheduledDate)
        assertEquals(LocalDate.of(2026, 10, 10), row.previousPaymentDate)
        assertEquals(15_105_73L, row.amountCents)
        assertFalse(row.isPaid)
        assertEquals(PaymentAgendaStatus.DUE_SOON, row.status)
        assertEquals(2L, row.daysUntilDue)
    }

    @Test
    fun `allows modifying scheduled date and adding custom agenda items`() {
        val expenses = listOf(
            HistoricalExpenseRecord(
                id = 1,
                description = "SM Tucumán CISI",
                amountCents = 15_105_73L,
                date = LocalDate.of(2026, 10, 10),
                categoryId = 2L,
            ),
        )
        val templates = listOf(
            PaymentAgendaTemplate(
                key = PaymentAgendaEngine.normalizeKey("SM Tucumán CISI"),
                name = "SM Tucumán CISI",
                customDayOfMonth = 15,
                estimatedAmountCents = 16_000_00L,
                alertEnabled = true,
                alertDaysBefore = 5,
                monthDateOverrides = mapOf("2026-11" to "2026-11-18"),
            ),
            PaymentAgendaTemplate(
                key = PaymentAgendaEngine.normalizeKey("Internet Fibra"),
                name = "Internet Fibra",
                customDayOfMonth = 20,
                estimatedAmountCents = 28_500_00L,
                alertEnabled = true,
                alertDaysBefore = 3,
            ),
        )

        val summary = PaymentAgendaEngine.buildForMonth(
            month = YearMonth.of(2026, 11),
            today = LocalDate.of(2026, 11, 13),
            expenses = expenses,
            serviceCategoryIds = setOf(2L),
            templates = templates,
        )

        assertEquals(2, summary.rows.size)
        val cisi = summary.rows.first { it.name == "SM Tucumán CISI" }
        val internet = summary.rows.first { it.name == "Internet Fibra" }

        assertEquals(LocalDate.of(2026, 11, 18), cisi.scheduledDate)
        assertEquals(16_000_00L, cisi.amountCents)
        assertEquals(PaymentAgendaStatus.DUE_SOON, cisi.status)

        assertEquals(LocalDate.of(2026, 11, 20), internet.scheduledDate)
        assertEquals(28_500_00L, internet.amountCents)
        assertEquals(PaymentAgendaStatus.UPCOMING, internet.status)
        assertTrue(internet.alertEnabled)
    }

    @Test
    fun `calendar only projects recurring expenses and agenda templates`() {
        val expenses = listOf(
            HistoricalExpenseRecord(
                id = 1,
                description = "Alquiler",
                amountCents = 450_000_00L,
                date = LocalDate.of(2026, 10, 5),
                categoryId = 1L,
                isRecurring = true,
            ),
            HistoricalExpenseRecord(
                id = 2,
                description = "Luz",
                amountCents = 22_000_00L,
                date = LocalDate.of(2026, 10, 12),
                categoryId = 1L,
                isRecurring = false,
            ),
        )

        val summary = PaymentAgendaEngine.buildForMonth(
            month = YearMonth.of(2026, 11),
            today = LocalDate.of(2026, 11, 1),
            expenses = expenses,
            serviceCategoryIds = setOf(1L),
            templates = emptyList(),
            recurringOnly = true,
        )

        assertEquals(1, summary.rows.size)
        val rent = summary.rows.first()
        assertEquals("Alquiler", rent.name)
        assertEquals(LocalDate.of(2026, 11, 5), rent.scheduledDate)
        assertEquals(450_000_00L, rent.amountCents)
        assertFalse(rent.isPaid)
    }

    @Test
    fun `manual paid month marks a recurring row paid without an expense`() {
        val key = PaymentAgendaEngine.normalizeKey("Alquiler")
        val templates = listOf(
            PaymentAgendaTemplate(
                key = key,
                name = "Alquiler",
                customDayOfMonth = 5,
                manualPaidMonths = listOf("2026-11"),
            ),
        )

        val summary = PaymentAgendaEngine.buildForMonth(
            month = YearMonth.of(2026, 11),
            today = LocalDate.of(2026, 11, 1),
            expenses = emptyList(),
            serviceCategoryIds = emptySet(),
            templates = templates,
            recurringOnly = true,
        )

        val row = summary.rows.single()
        assertTrue(row.isPaid)
        assertNull(row.paidExpenseId)
        assertEquals(LocalDate.of(2026, 11, 5), row.paidDate)
        assertEquals(1, summary.paidCount)
        assertEquals(0, summary.pendingCount)
    }

    @Test
    fun `bimonthly recurring expense is projected only on its cadence`() {
        val expenses = listOf(
            HistoricalExpenseRecord(
                id = 1,
                description = "Seguro del auto",
                amountCents = 85_000_00L,
                date = LocalDate.of(2026, 1, 10),
                categoryId = 1L,
                isRecurring = true,
                recurrenceMonths = 2,
            ),
        )

        fun summary(month: YearMonth) = PaymentAgendaEngine.buildForMonth(
            month = month,
            today = month.atDay(1),
            expenses = expenses,
            serviceCategoryIds = emptySet(),
            templates = emptyList(),
            recurringOnly = true,
        )

        val january = summary(YearMonth.of(2026, 1)).rows.single()
        assertEquals(LocalDate.of(2026, 1, 10), january.scheduledDate)
        assertEquals(2, january.intervalMonths)
        assertTrue(summary(YearMonth.of(2026, 2)).rows.isEmpty())
        assertEquals(
            LocalDate.of(2026, 3, 10),
            summary(YearMonth.of(2026, 3)).rows.single().scheduledDate,
        )
        assertTrue(summary(YearMonth.of(2026, 4)).rows.isEmpty())
        assertEquals(
            LocalDate.of(2026, 5, 10),
            summary(YearMonth.of(2026, 5)).rows.single().scheduledDate,
        )
    }

    @Test
    fun `a payment registered outside the cadence remains visible`() {
        val expenses = listOf(
            HistoricalExpenseRecord(
                id = 1,
                description = "Seguro del auto",
                amountCents = 85_000_00L,
                date = LocalDate.of(2026, 1, 10),
                categoryId = 1L,
                isRecurring = true,
                recurrenceMonths = 2,
            ),
            // Paid early in February even though the cadence would skip that month.
            HistoricalExpenseRecord(
                id = 2,
                description = "Seguro del auto",
                amountCents = 87_000_00L,
                date = LocalDate.of(2026, 2, 3),
                categoryId = 1L,
                isRecurring = true,
                recurrenceMonths = 2,
            ),
        )

        fun summary(month: YearMonth) = PaymentAgendaEngine.buildForMonth(
            month = month,
            today = month.atDay(1),
            expenses = expenses,
            serviceCategoryIds = emptySet(),
            templates = emptyList(),
            recurringOnly = true,
        )

        val february = summary(YearMonth.of(2026, 2)).rows.single()
        assertTrue(february.isPaid)
        assertEquals(LocalDate.of(2026, 2, 3), february.paidDate)
        assertEquals(87_000_00L, february.amountCents)
        assertTrue(summary(YearMonth.of(2026, 4)).rows.isEmpty())
    }

    @Test
    fun `manual agenda item respects intervalMonths from its start month`() {
        val templates = listOf(
            PaymentAgendaTemplate(
                key = PaymentAgendaEngine.normalizeKey("Seguro de vida"),
                name = "Seguro de vida",
                customDayOfMonth = 12,
                estimatedAmountCents = 30_000_00L,
                intervalMonths = 3,
                startMonth = "2026-02",
            ),
        )

        fun summary(month: YearMonth) = PaymentAgendaEngine.buildForMonth(
            month = month,
            today = month.atDay(1),
            expenses = emptyList(),
            serviceCategoryIds = emptySet(),
            templates = templates,
            recurringOnly = true,
        )

        assertEquals(
            LocalDate.of(2026, 2, 12),
            summary(YearMonth.of(2026, 2)).rows.single().scheduledDate,
        )
        assertTrue(summary(YearMonth.of(2026, 3)).rows.isEmpty())
        assertTrue(summary(YearMonth.of(2026, 4)).rows.isEmpty())
        assertEquals(
            LocalDate.of(2026, 5, 12),
            summary(YearMonth.of(2026, 5)).rows.single().scheduledDate,
        )
    }
}
