package com.smartinvest.domain.engine

import com.smartinvest.data.importer.XlsxSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class RemXlsxParserTest {

    private fun rows(): List<List<String>> = listOf(
        listOf("", "Relevamiento de Expectativas de Mercado (REM) - BCRA - Octubre 2026"),
        listOf("", "Precios minoristas (IPC nivel general-Nacional; INDEC)"),
        listOf("", "Período", "Referencia", "Mediana"),
        listOf("", "46356", "var. % mensual", "1.7"),
        listOf("", "46387", "var. % mensual", "1.84"),
        listOf("", "2026", "var. % i.a.; dic-26", "30.36"),
        listOf("", "Tipo de cambio nominal"),
        listOf("", "Período", "Referencia", "Mediana"),
        listOf("", "46356", "\$/USD", "1558,48"),
        listOf("", "46387", "\$/USD", "1590"),
        listOf("", "próx. 12 meses", "\$/USD; oct-27", "1984.9"),
        listOf("", "Tasa de interés (TAMAR)"),
        listOf("", "Período", "Referencia", "Mediana"),
        listOf("", "46356", "TNA; %", "23.9"),
        listOf("", "46387", "TNA; %", "24"),
    )

    @Test
    fun `reads monthly ipc fx and tamar medians`() {
        val snapshot = RemXlsxParser.parseSheets(
            listOf(XlsxSheet(name = "Cuadros de resultados", rows = rows())),
        )

        assertEquals(2, snapshot.points.size)
        assertEquals(YearMonth.of(2026, 11), snapshot.points[0].yearMonth)
        assertEquals(1.7, snapshot.points[0].inflationMonthlyPercent, 0.0001)
        assertEquals(1558.48, snapshot.points[0].officialFx, 0.0001)
        assertEquals(23.9, snapshot.points[0].tamarTnaPercent, 0.0001)

        assertEquals(1.84, snapshot.points[1].inflationMonthlyPercent, 0.0001)
        assertEquals(1590.0, snapshot.points[1].officialFx, 0.0001)
        assertEquals(24.0, snapshot.points[1].tamarTnaPercent, 0.0001)
    }

    @Test
    fun `derives the publication date from the rem title`() {
        val snapshot = RemXlsxParser.parseSheets(
            listOf(XlsxSheet(name = "Cuadros de resultados", rows = rows())),
        )

        assertEquals("2026-11-05", snapshot.publishedAt)
        assertTrue(snapshot.reference.contains("Octubre 2026"))
    }

    @Test
    fun `converts excel serial dates`() {
        assertEquals(YearMonth.of(2026, 11), RemXlsxParser.excelDateToMonth("46356"))
        assertEquals(YearMonth.of(2027, 4), RemXlsxParser.excelDateToMonth("46507"))
        assertEquals(YearMonth.of(2026, 11), RemXlsxParser.excelDateToMonth("30/11/2026"))
        assertEquals(null, RemXlsxParser.excelDateToMonth("próx. 12 meses"))
    }
}
