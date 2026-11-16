package com.smartinvest.domain.engine

import com.smartinvest.domain.model.CreditCard
import com.smartinvest.domain.model.ExpenseFilter
import com.smartinvest.domain.model.ExpensePaymentMethod
import com.smartinvest.domain.model.ExpenseRecord
import com.smartinvest.domain.model.HouseholdCategory
import com.smartinvest.domain.model.HouseholdMember
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class ExpenseReportEngineTest {

    private val categories = listOf(
        category(10, "Supermercado", "🛒"),
        category(20, "Auto", "🚗"),
    )
    private val members = listOf(
        member(1, "Vos"),
        member(2, "Mamá"),
    )

    @Test
    fun `filters by text category payment method and member`() {
        val records = listOf(
            record(
                id = 1,
                description = "Compra semanal",
                categoryId = 10,
                memberId = 1,
                method = ExpensePaymentMethod.CASH,
                month = YearMonth.of(2026, 11),
                impactCents = 100_00L,
            ),
            record(
                id = 2,
                description = "Nafta",
                categoryId = 20,
                memberId = 2,
                method = ExpensePaymentMethod.CREDIT,
                month = YearMonth.of(2026, 12),
                impactCents = 500_00L,
                installmentNumber = 1,
                installmentCount = 3,
            ),
        )

        fun filtered(filter: ExpenseFilter) =
            ExpenseReportEngine.filter(records, filter, categories, members, emptyList())

        assertEquals(1, filtered(ExpenseFilter(query = "compra")).size)
        assertEquals(1, filtered(ExpenseFilter(query = "SUPERMERCADO")).size)
        assertEquals(1, filtered(ExpenseFilter(query = "nafta")).size)
        assertEquals(1, filtered(ExpenseFilter(query = "efectivo")).size)
        assertEquals(1, filtered(ExpenseFilter(categoryId = 20)).size)
        assertEquals(1, filtered(ExpenseFilter(memberId = 2)).size)
        assertEquals(1, filtered(ExpenseFilter(paymentMethod = ExpensePaymentMethod.CREDIT)).size)
        assertEquals(2, filtered(ExpenseFilter()).size)
        assertTrue(ExpenseFilter(query = "x").hasActiveFilters)
    }

    @Test
    fun `builds totals monthly buckets and top expenses`() {
        val records = listOf(
            record(
                id = 1,
                description = "Súper",
                categoryId = 10,
                memberId = 1,
                method = ExpensePaymentMethod.CASH,
                month = YearMonth.of(2026, 11),
                impactCents = 100_000_00L,
            ),
            record(
                id = 2,
                description = "Heladera",
                categoryId = 10,
                memberId = 2,
                method = ExpensePaymentMethod.CREDIT,
                month = YearMonth.of(2026, 11),
                impactCents = 50_000_00L,
                totalCents = 300_000_00L,
                installmentNumber = 2,
                installmentCount = 6,
            ),
            record(
                id = 3,
                description = "Nafta",
                categoryId = 20,
                memberId = 1,
                method = ExpensePaymentMethod.TRANSFER,
                month = YearMonth.of(2026, 10),
                impactCents = 30_000_00L,
            ),
        )

        val report = ExpenseReportEngine.build(
            records = records,
            categories = categories.associateBy { it.id },
            members = members.associateBy { it.id },
        )

        assertEquals(180_000_00L, report.totalCents)
        assertEquals(3, report.count)
        assertEquals(2, report.monthsConsidered)
        assertEquals(90_000_00L, report.averagePerMonthCents)
        assertEquals(60_000_00L, report.averageTicketCents)
        assertEquals(130_000_00L, report.directCents)
        assertEquals(50_000_00L, report.installmentCents)
        assertEquals(1, report.installmentCount)
        assertEquals("Súper", report.largest?.description)

        assertEquals(listOf(150_000_00L, 30_000_00L), report.monthly.map { it.totalCents })
        assertEquals(YearMonth.of(2026, 11), report.monthly.first().month)

        assertEquals("Supermercado", report.byCategory.first().label.substringAfter(' '))
        assertEquals(150_000_00L, report.byCategory.first().totalCents)
        assertEquals(3, report.byPaymentMethod.size)
        assertEquals(2, report.byMember.size)
        assertEquals(3, report.topExpenses.size)
        assertEquals(100_000_00L, report.topExpenses.first().impactCents)
    }

    @Test
    fun `csv escapes separators quotes and newlines and keeps month totals`() {
        val records = listOf(
            record(
                id = 1,
                description = "Compra; \"especial\"",
                categoryId = 10,
                memberId = 1,
                method = ExpensePaymentMethod.CASH,
                month = YearMonth.of(2026, 11),
                impactCents = 1_234_56L,
                notes = "primera linea\nsegunda",
            ),
        )

        val csv = ExpenseReportExporter.csv(
            records = records,
            categories = categories.associateBy { it.id },
            members = members.associateBy { it.id },
            cards = emptyMap<Long, CreditCard>(),
        )

        assertTrue(csv.startsWith("\uFEFFFecha;Mes imputado"))
        assertTrue(csv.contains("\"Compra; \"\"especial\"\"\""))
        assertTrue(csv.contains("1234,56"))
        assertTrue(csv.contains("TOTAL"))
        assertTrue(csv.contains("🛒 Supermercado"))
        assertTrue(csv.contains("primera linea segunda"))
    }

    @Test
    fun `summary lists categories and period`() {
        val report = ExpenseReportEngine.build(
            records = listOf(
                record(
                    id = 1,
                    description = "Súper",
                    categoryId = 10,
                    memberId = 1,
                    method = ExpensePaymentMethod.CASH,
                    month = YearMonth.of(2026, 11),
                    impactCents = 100_000_00L,
                ),
            ),
            categories = categories.associateBy { it.id },
            members = members.associateBy { it.id },
        )

        val text = ExpenseReportExporter.summary(report, "Este mes")

        assertTrue(text.contains("Período: Este mes"))
        assertTrue(text.contains("POR CATEGORÍA"))
        assertTrue(text.contains("Supermercado"))
        assertTrue(text.endsWith("Generado con SmartInvest"))
    }

    private fun record(
        id: Long,
        description: String,
        categoryId: Long,
        memberId: Long,
        method: ExpensePaymentMethod,
        month: YearMonth,
        impactCents: Long,
        totalCents: Long = impactCents,
        installmentNumber: Int? = null,
        installmentCount: Int? = null,
        notes: String = "",
    ): ExpenseRecord = ExpenseRecord(
        expenseId = id,
        installmentId = installmentNumber?.let { id + 1000 },
        description = description,
        notes = notes,
        date = month.atDay(5),
        month = month,
        totalCents = totalCents,
        impactCents = impactCents,
        categoryId = categoryId,
        paidByMemberId = memberId,
        paymentMethod = method,
        creditCardId = if (method == ExpensePaymentMethod.CREDIT) 1L else null,
        installmentNumber = installmentNumber,
        installmentCount = installmentCount,
    )

    private fun category(id: Long, name: String, emoji: String) = HouseholdCategory(
        id = id,
        name = name,
        emoji = emoji,
        colorHex = "#38BDF8",
        isArchived = false,
    )

    private fun member(id: Long, name: String) = HouseholdMember(
        id = id,
        name = name,
        isCurrentUser = id == 1L,
        colorHex = "#22C55E",
        isActive = true,
    )
}
