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

    @Test
    fun `parses galicia mastercard black credit card statement with due date next closing and total a pagar`() {
        val text = """
            Resumen N° 027032099478
            Tarjeta Crédito MASTERCARD BLACK
            NU&EZ,RICARDO JAVIER CONSUMIDOR FINAL CUIT Banco: 30-50000173-5
            CRIS STOMO LVAREZ 4778, SAN MIGUEL DE TUCUMA, 04000 N° de Socio: 2220604-0-4 Sucursal: 374
            Resumen de tarjeta de credito MASTERCARD BLACK Página 1 / 5
            20261112068602202H
            247.282,15
            -1,01
            15-Oct-26 23-Oct-26 12-Nov-26 20-Nov-26 17-Dic-26 28-Dic-26
             
            PAGO MINIMO LÍMITES
            En pesos
            ${'$'} 16.260,00
            De compras en un pago y en cuotas
            ${'$'} 14.000.000,00
            De financiación
            ${'$'} 14.000.000,00
            CONSOLIDADO PESOS DÓLARES
            SALDO ANTERIOR 483.865,41 -1,01
            23-Oct-26 SU PAGO -483.865,41 -483.865,41
             SALDO PENDIENTE 0,00 -1,01
             TOTAL CONSUMOS DEL MES 244.833,81 0,00
            SUBTOTAL 244.833,81 -1,01
            IMPUESTO DE SELLOS 2.448,34
            TOTAL A PAGAR 247.282,15 -1,01
        """.trimIndent()

        val receipt = ReceiptParser.parse(text)

        assertEquals(ReceiptKind.CARD_STATEMENT, receipt.kind)
        assertEquals("MASTERCARD BLACK", receipt.cardName)
        assertEquals("Resumen MASTERCARD BLACK", receipt.description)
        assertEquals(LocalDate.of(2026, 11, 20), receipt.dueDate)
        assertEquals(LocalDate.of(2026, 12, 17), receipt.nextClosingDate)
        assertEquals(LocalDate.of(2026, 11, 12), receipt.currentClosingDate)
        assertEquals(LocalDate.of(2026, 12, 28), receipt.nextDueDate)
        assertEquals(24_728_215L, receipt.amountCents)
        assertEquals(LocalDate.of(2026, 11, 20), receipt.date)
        assertEquals("Banco Galicia", receipt.originInstitution)
        assertEquals("027032099478", receipt.reference)
    }
}
