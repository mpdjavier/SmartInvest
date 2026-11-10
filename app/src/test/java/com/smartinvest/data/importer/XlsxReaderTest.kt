package com.smartinvest.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class XlsxReaderTest {

    private fun workbookZip(): ByteArray {
        val workbook = """
            <?xml version="1.0" encoding="UTF-8"?>
            <workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
              <sheets>
                <sheet name="Gastos" sheetId="1" r:id="rId1"/>
              </sheets>
            </workbook>
        """.trimIndent()

        val relationships = """
            <?xml version="1.0" encoding="UTF-8"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
            </Relationships>
        """.trimIndent()

        val sharedStrings = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <si><t>Fecha</t></si>
              <si><t>Descripcion</t></si>
              <si><t>Monto</t></si>
              <si><t>07/11/2026</t></si>
            </sst>
        """.trimIndent()

        val sheet = """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1">
                  <c r="A1" t="s"><v>0</v></c>
                  <c r="B1" t="s"><v>1</v></c>
                  <c r="C1" t="s"><v>2</v></c>
                </row>
                <row r="2">
                  <c r="A2" t="s"><v>3</v></c>
                  <c r="B2" t="inlineStr"><is><t>Regalo</t></is></c>
                  <c r="C2"><v>19666.5</v></c>
                </row>
                <row r="3">
                  <c r="C3"><v>42</v></c>
                </row>
              </sheetData>
            </worksheet>
        """.trimIndent()

        val entries = mapOf(
            "xl/workbook.xml" to workbook,
            "xl/_rels/workbook.xml.rels" to relationships,
            "xl/sharedStrings.xml" to sharedStrings,
            "xl/worksheets/sheet1.xml" to sheet,
        )

        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }

    @Test
    fun `reads shared strings inline strings numbers and column gaps`() {
        val sheets = XlsxReader.read(workbookZip())

        assertEquals(1, sheets.size)
        val sheet = sheets.first()
        assertEquals("Gastos", sheet.name)
        assertEquals(listOf("Fecha", "Descripcion", "Monto"), sheet.rows[0])
        assertEquals(listOf("07/11/2026", "Regalo", "19666.5"), sheet.rows[1])
        assertEquals(listOf("", "", "42"), sheet.rows[2])
    }

    @Test
    fun `rejects files without workbook xml`() {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("hello.txt"))
            zip.write("not an xlsx".toByteArray())
            zip.closeEntry()
        }

        val failure = runCatching { XlsxReader.read(output.toByteArray()) }
        assertTrue(failure.isFailure)
    }
}
