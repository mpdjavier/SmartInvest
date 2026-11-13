package com.smartinvest.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReceiptParserTest {

    @Test
    fun `parses mercado pago transfer receipt`() {
        val text = """
            Comprobante de transferencia
            10/noviembre/2026 9:35
            $ 188.461,75
            Ricardo Javier Nuñez
            Motivo: Varios
            Origen: Mercado Pago CVU 0000003100096540822738
            Destino: Banco De Galicia CBU 0070374930004010723910
            Operación 187743598373
            Código ORD6LEN8M40L7J30NM1Y30
        """.trimIndent()

        val receipt = ReceiptParser.parse(text)

        assertEquals(ReceiptKind.TRANSFER, receipt.kind)
        assertEquals(18_846_175L, receipt.amountCents)
        assertEquals(LocalDate.of(2026, 11, 10), receipt.date)
        assertEquals("Mercado Pago", receipt.originInstitution)
        assertEquals("Banco Galicia", receipt.destinationInstitution)
        assertEquals("187743598373", receipt.reference)
        assertTrue(receipt.likelyOwnTransfer)
        assertTrue(receipt.description.contains("Mercado Pago"))
        assertTrue(receipt.warnings.any { it.contains("cuentas propias") })
    }

    @Test
    fun `parses galicia service payment receipt`() {
        val text = """
            Banco Galicia
            Comprobante de pago de servicios
            Fecha: 10/11/2026
            Importe: $ 15.105,73
            Empresa: SM Tucumán CISI
            Nro. operación: 998877
        """.trimIndent()

        val receipt = ReceiptParser.parse(text)

        assertEquals(ReceiptKind.EXPENSE, receipt.kind)
        assertEquals(1_510_573L, receipt.amountCents)
        assertEquals(LocalDate.of(2026, 11, 10), receipt.date)
        assertEquals("SM Tucumán CISI", receipt.serviceName)
        assertEquals("SM Tucumán CISI", receipt.description)
        assertEquals("Banco Galicia", receipt.originInstitution)
        assertTrue(!receipt.likelyOwnTransfer)
    }

    @Test
    fun `keeps unknown receipts editable with warnings`() {
        val receipt = ReceiptParser.parse("Documento sin datos reconocibles")

        assertEquals(ReceiptKind.UNKNOWN, receipt.kind)
        assertNull(receipt.amountCents)
        assertNull(receipt.date)
        assertNull(receipt.originInstitution)
        assertTrue(receipt.warnings.any { it.contains("importe") })
        assertTrue(receipt.warnings.any { it.contains("fecha") })
        assertTrue(!receipt.canSave)
    }

    @Test
    fun `parses app mobile service payment with label on the previous line`() {
        val text = """
            App Mobile
            Pago de SM Tucumán CISI
            Nro° Trans
            493481871301
            Fecha
            10/11/2026
            Hora
            09:38
            Por un importe de
            ${'$'}15105.73
            Medio de pago
            CA ${'$'} 401072393741
        """.trimIndent()

        val receipt = ReceiptParser.parse(text)

        assertEquals(ReceiptKind.EXPENSE, receipt.kind)
        assertEquals(1_510_573L, receipt.amountCents)
        assertEquals(LocalDate.of(2026, 11, 10), receipt.date)
        assertEquals("SM Tucumán CISI", receipt.description)
        assertEquals("SM Tucumán CISI", receipt.serviceName)
        assertEquals("493481871301", receipt.reference)
        assertTrue(!receipt.likelyOwnTransfer)
    }

    @Test
    fun `supports text dates with month names`() {
        val text = """
            Transferencia realizada
            3 de diciembre de 2026
            Monto $ 25.000,00
        """.trimIndent()

        val receipt = ReceiptParser.parse(text)

        assertEquals(LocalDate.of(2026, 12, 3), receipt.date)
        assertEquals(2_500_000L, receipt.amountCents)
    }
}
