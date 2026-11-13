package com.smartinvest.domain.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
