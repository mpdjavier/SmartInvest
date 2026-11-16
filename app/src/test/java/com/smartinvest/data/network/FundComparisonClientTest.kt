package com.smartinvest.data.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FundComparisonClientTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
    }

    @Test
    fun `parses catalog entries used by the evaluator`() {
        val raw = """
            {
              "status": "ok",
              "count": 2,
              "funds": [
                {
                  "nombre": "Adcap Balanceado III - Clase A",
                  "nombreBase": "Adcap Balanceado III",
                  "gestora": "Adcap",
                  "tipo": "MIX",
                  "moneda": "ARS",
                  "categoriaOficial": "Renta mixta",
                  "patrimonio": 12933241435.53,
                  "vcpHoy": 5000.1,
                  "fechaVcp": "2026-11-13",
                  "r30": 2.9124,
                  "r365": 47.02,
                  "volat": 1.4,
                  "maxDrop": 0,
                  "positiveDays": 100,
                  "spark": [1, 2, 3]
                },
                {
                  "nombre": "   ",
                  "gestora": "Ignorada"
                }
              ]
            }
        """.trimIndent()

        val catalog = FundComparisonClient.parseCatalog(raw, json)

        assertEquals(1, catalog.size)
        val entry = catalog.single()
        assertEquals("Adcap Balanceado III - Clase A", entry.name)
        assertEquals("Adcap", entry.manager)
        assertEquals("MIX", entry.type)
        assertEquals("ARS", entry.currency)
        assertEquals("Renta mixta", entry.category)
        assertEquals(2.9124, entry.return30!!, 0.0001)
        assertEquals(47.02, entry.return365!!, 0.0001)
        assertEquals(1.4, entry.volatility90!!, 0.0001)
        assertEquals(LocalDate.of(2026, 11, 13), entry.vcpDate)
        assertEquals(5_000.1, entry.vcp, 0.001)
    }

    @Test
    fun `parses and sorts daily history skipping invalid points`() {
        val raw = """
            {
              "nombre": "Adcap Balanceado III - Clase A",
              "count": 5,
              "saltos": 2,
              "history": [
                { "fecha": "2026-11-13", "vcp": 110.5 },
                { "fecha": "2026-11-11", "vcp": 100.0 },
                { "fecha": "2026-11-12", "vcp": 0 },
                { "fecha": "no-es-fecha", "vcp": 5.0 },
                { "fecha": "2026-11-14", "vcp": null }
              ]
            }
        """.trimIndent()

        val history = FundComparisonClient.parseHistory(raw, json)

        assertEquals(2, history.size)
        assertEquals(LocalDate.of(2026, 11, 11), history.first().date)
        assertEquals(100.0, history.first().value, 0.001)
        assertEquals(LocalDate.of(2026, 11, 13), history.last().date)
        assertEquals(110.5, history.last().value, 0.001)
    }

    @Test
    fun `empty response yields empty lists`() {
        assertTrue(FundComparisonClient.parseCatalog("", json).isEmpty())
        assertTrue(FundComparisonClient.parseHistory("", json).isEmpty())
    }

    @Test
    fun `history without points yields an empty list`() {
        val raw = """{"nombre":"X","count":0,"history":[]}"""
        assertTrue(FundComparisonClient.parseHistory(raw, json).isEmpty())
        assertNull(FundComparisonClient.parseHistory(raw, json).firstOrNull())
    }
}
