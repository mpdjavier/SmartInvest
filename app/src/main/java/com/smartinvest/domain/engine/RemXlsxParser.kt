package com.smartinvest.domain.engine

import com.smartinvest.data.importer.XlsxReader
import com.smartinvest.data.importer.XlsxSheet
import com.smartinvest.domain.model.RemDefaults
import com.smartinvest.domain.model.RemMonthPoint
import com.smartinvest.domain.model.RemSnapshot
import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/**
 * Extracts the monthly medians from the official BCRA REM workbook.
 *
 * The XLSX has three blocks used by the comparator:
 * - "Precios minoristas (IPC ...)" with `var. % mensual` rows.
 * - "Tipo de cambio nominal" with `$/USD` rows.
 * - "Tasa de interés (TAMAR)" with `TNA; %` rows.
 *
 * Column B holds an Excel serial date (end of month), column C the concept and
 * column D the median. Rows after the monthly path (annual summaries such as
 * "próx. 12 meses" or "2027") stop each block.
 */
object RemXlsxParser {

    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
    private val MONTHS = mapOf(
        "enero" to 1,
        "febrero" to 2,
        "marzo" to 3,
        "abril" to 4,
        "mayo" to 5,
        "junio" to 6,
        "julio" to 7,
        "agosto" to 8,
        "septiembre" to 9,
        "setiembre" to 9,
        "octubre" to 10,
        "noviembre" to 11,
        "diciembre" to 12,
    )

    fun parse(bytes: ByteArray): RemSnapshot = parseSheets(XlsxReader.read(bytes))

    internal fun parseSheets(sheets: List<XlsxSheet>): RemSnapshot {
        val sheet = sheets.firstOrNull { sheet ->
            sheet.rows.any { row -> row.any { it.contains("Precios minoristas", ignoreCase = true) } }
        } ?: error("No se encontró la hoja de resultados del REM (BCRA).")

        val rows = sheet.rows
        val title = rows.asSequence()
            .mapNotNull { it.getOrNull(1) }
            .firstOrNull { it.contains("Relevamiento de Expectativas", ignoreCase = true) }
            .orEmpty()
            .trim()

        val ipc = readBlock(rows, header = "Precios minoristas", label = "mensual")
        val fx = readBlock(rows, header = "Tipo de cambio nominal", label = "usd")
        val tamar = readBlock(rows, header = "Tasa de interés", label = "tna")

        require(ipc.isNotEmpty()) { "No se encontraron proyecciones mensuales de inflación en el REM." }
        require(fx.isNotEmpty()) { "No se encontraron proyecciones de tipo de cambio en el REM." }

        val months = (ipc.keys + fx.keys + tamar.keys).sorted()
        val points = months.map { month ->
            RemMonthPoint(
                month = month.toString(),
                inflationMonthlyPercent = ipc[month] ?: 0.0,
                officialFx = fx[month] ?: 0.0,
                tamarTnaPercent = tamar[month] ?: 0.0,
            )
        }

        return RemSnapshot(
            publishedAt = parsePublishedAt(title),
            reference = title.ifBlank { "REM · BCRA" },
            sourceUrl = RemDefaults.SOURCE_URL,
            points = points,
        )
    }

    private fun readBlock(
        rows: List<List<String>>,
        header: String,
        label: String,
    ): Map<YearMonth, Double> {
        val normalizedHeader = normalize(header)
        val start = rows.indexOfFirst { row ->
            row.any { normalize(it).contains(normalizedHeader) }
        }
        if (start < 0) return emptyMap()

        val result = linkedMapOf<YearMonth, Double>()
        var collecting = false
        for (index in start + 1 until rows.size) {
            val row = rows[index]
            val month = excelDateToMonth(row.getOrNull(1).orEmpty())
            if (month == null) {
                if (collecting) break
                continue
            }
            collecting = true
            if (!normalize(row.getOrNull(2).orEmpty()).contains(normalize(label))) continue
            val median = parseNumber(row.getOrNull(3).orEmpty()) ?: continue
            result.putIfAbsent(month, median)
        }
        return result
    }

    /** Converts an Excel serial date or an ISO/Latin date into its month. */
    internal fun excelDateToMonth(raw: String): YearMonth? {
        val clean = raw.trim()
        if (clean.isEmpty()) return null
        clean.toDoubleOrNull()?.let { serial ->
            if (serial in 40_000.0..60_000.0) {
                return YearMonth.from(EXCEL_EPOCH.plusDays(serial.toLong()))
            }
        }
        for (pattern in listOf("dd/MM/yyyy", "yyyy-MM-dd", "dd-MM-yyyy")) {
            val date = runCatching {
                LocalDate.parse(clean, java.time.format.DateTimeFormatter.ofPattern(pattern))
            }.getOrNull()
            if (date != null) return YearMonth.from(date)
        }
        return null
    }

    private fun parsePublishedAt(title: String): String {
        val normalized = normalize(title)
        val match = Regex(
            "(enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|octubre|noviembre|diciembre)\\s+(\\d{4})",
        ).find(normalized) ?: return ""
        val month = MONTHS[match.groupValues[1]] ?: return ""
        val year = match.groupValues[2].toIntOrNull() ?: return ""
        // The REM is published on the 5th of the following month.
        return LocalDate.of(year, month, 1).plusMonths(1).plusDays(4).toString()
    }

    private fun parseNumber(raw: String): Double? {
        val clean = raw.trim()
            .replace("%", "")
            .replace("$", "")
            .replace("\u00A0", "")
            .replace(" ", "")
        if (clean.isEmpty()) return null
        val normalized = when {
            clean.contains(',') && clean.contains('.') -> clean.replace(".", "").replace(',', '.')
            clean.contains(',') -> clean.replace(',', '.')
            else -> clean
        }
        return normalized.toDoubleOrNull()
    }

    private fun normalize(raw: String): String =
        Normalizer.normalize(raw.lowercase(Locale.forLanguageTag("es-AR")), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .trim()
}
