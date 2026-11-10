package com.smartinvest.domain.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SplitHomeParserTest {

    private fun sampleSheets(): Map<String, List<List<String>>> = mapOf(
        "Gastos" to listOf(
            listOf("Fecha", "Descripcion", "Monto", "Pagado por", "Categoria"),
            listOf("07/11/2026", "Regalo Sabrina", "19666.0", "M", "Otros"),
            listOf("20/11/2026", "Cocina Vicky (Cuota 1/6)", "12404.38", "J", "Otros"),
            listOf("07/11/2026", "Seguro Auto Papá", "67236.94", "Fondo", "Servicios"),
            listOf("", "", "", "", ""),
            listOf("no-es-fecha", "Roto", "abc", "X", "Otros"),
        ),
        "Fondo Comun" to listOf(
            listOf("Fondo Inicial:", "1778466.21"),
            listOf("Fecha", "Descripcion", "Tipo", "Monto"),
            listOf("07/11/2026", "Seguro Auto Papá", "GASTO_FONDO", "67236.94"),
        ),
    )

    @Test
    fun `parses expenses payers and fund initial balance`() {
        val result = SplitHomeParser.parse(sampleSheets())

        assertTrue(result.isValid)
        assertEquals(3, result.expenses.size)
        assertEquals(1, result.skippedRows)
        assertTrue(result.warnings.any { it.contains("Fila 6") })

        val gift = result.expenses.first { it.description == "Regalo Sabrina" }
        assertEquals(LocalDate.of(2026, 11, 7), gift.date)
        assertEquals(1_966_600L, gift.amountCents)
        assertEquals(SplitHomePayer.SECONDARY, gift.payer)
        assertEquals("Otros", gift.categoryName)

        val fund = result.expenses.first { it.description == "Seguro Auto Papá" }
        assertEquals(SplitHomePayer.FUND, fund.payer)
        assertEquals(6_723_694L, fund.amountCents)

        assertEquals(177_846_621L, result.fundInitialCents)
        assertEquals(9_930_732L, result.totalCents)
        assertEquals(6_723_694L, result.fundPurchaseCents)
    }

    @Test
    fun `installment row keeps the installment amount and strips the suffix`() {
        val result = SplitHomeParser.parse(sampleSheets())
        val purchase = result.expenses.first { it.isInstallmentPurchase }

        assertEquals("Cocina Vicky", purchase.description)
        assertEquals(1_240_438L, purchase.amountCents)
        assertEquals(6, purchase.installments)
        assertEquals(7_442_628L, purchase.totalCents)
        assertEquals(1, result.installmentCount)
        assertEquals(6, result.generatedInstallments)
        assertEquals(SplitHomePayer.PRIMARY, purchase.payer)
    }

    @Test
    fun `finds headers after a preamble and normalizes accents`() {
        val sheets = mapOf(
            "GASTOS" to listOf(
                listOf("Reporte exportado", "", "", "", ""),
                listOf("FECHA", "Descripción", "Monto", "Pagado por", "Categoría"),
                listOf("2026-09-10", "Maceta", "34300", "Vos", ""),
            ),
        )

        val result = SplitHomeParser.parse(sheets)

        assertTrue(result.isValid)
        assertEquals(1, result.expenses.size)
        assertEquals(LocalDate.of(2026, 9, 10), result.expenses.first().date)
        assertEquals(3_430_000L, result.expenses.first().amountCents)
        assertEquals("Otros", result.expenses.first().categoryName)
        assertTrue(result.warnings.any { it.contains("Fondo") })
    }

    @Test
    fun `accepts excel serial dates`() {
        val sheets = mapOf(
            "Gastos" to listOf(
                listOf("Fecha", "Descripcion", "Monto", "Pagado por", "Categoria"),
                listOf("25569", "Año nuevo", "100", "J", "Otros"),
            ),
        )

        val result = SplitHomeParser.parse(sheets)

        assertTrue(result.isValid)
        assertEquals(LocalDate.of(1970, 1, 1), result.expenses.first().date)
        assertEquals(10_000L, result.expenses.first().amountCents)
    }

    @Test
    fun `missing expenses sheet is an error`() {
        val result = SplitHomeParser.parse(mapOf("Fondo Comun" to emptyList()))

        assertFalse(result.isValid)
        assertTrue(result.errors.any { it.contains("Gastos") })
    }

    @Test
    fun `payer aliases are normalized`() {
        assertEquals(SplitHomePayer.PRIMARY, SplitHomeParser.parsePayer("J"))
        assertEquals(SplitHomePayer.PRIMARY, SplitHomeParser.parsePayer("vos"))
        assertEquals(SplitHomePayer.SECONDARY, SplitHomeParser.parsePayer("Mamá"))
        assertEquals(SplitHomePayer.FUND, SplitHomeParser.parsePayer("FONDO"))
        assertNull(SplitHomeParser.parsePayer("otro"))
    }
}
