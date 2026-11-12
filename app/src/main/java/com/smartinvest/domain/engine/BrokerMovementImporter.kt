package com.smartinvest.domain.engine

import com.smartinvest.core.money.MoneyFormatter
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.domain.model.CurrencyCode
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class HoldingMovement(
    val date: LocalDate,
    /** Positive for contributions/purchases (SSMF, compra), negative for redemptions/sales (LRSM, venta). */
    val signedAmountCents: Long,
    val operationCode: String = "",
    val species: String = "",
    val quantity: Double? = null,
    val price: Double? = null,
)

data class BrokerImportSummary(
    val movements: List<HoldingMovement>,
    val totalSubscribedCents: Long,
    val totalRedeemedCents: Long,
    val netInvestedCents: Long,
) {
    fun toNotesBlock(currency: CurrencyCode): String {
        val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        return movements
            .sortedBy { it.date }
            .joinToString("\n") { mov ->
                val sign = if (mov.signedAmountCents >= 0L) "+" else "-"
                val absCents = kotlin.math.abs(mov.signedAmountCents)
                val formatted = if (currency == CurrencyCode.USD) {
                    MoneyFormatter.usd(absCents)
                } else {
                    MoneyFormatter.ars(absCents)
                }
                val opTag = if (mov.operationCode.isNotBlank()) " (${mov.operationCode})" else ""
                "${mov.date.format(fmt)}: $sign$formatted$opTag"
            }
    }
}

object BrokerMovementImporter {

    private val DATE_DD_MM_YYYY = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val DATE_DD_MM_YY = DateTimeFormatter.ofPattern("dd/MM/yy")
    private val NOTE_LINE_REGEX = Regex(
        """^(\d{2}/\d{2}/\d{2,4})\s*:\s*([+-−])\s*(?:US\$|\$|USD|ARS)?\s*([\d.,]+)""",
    )

    /**
     * Exact 12 movements exported from Eco Valores (Comitente 213858) for
     * `4070-ADCAP BALANCEADO 3 Clase A` (`cafci:1402:4070`).
     */
    val ecoValoresAdcapBal3Movements: List<HoldingMovement> = listOf(
        HoldingMovement(LocalDate.of(2026, 7, 17), 5_000_000_00L, "SSMF", "4070-ADCAP BALANCEADO 3 Clase A"),
        HoldingMovement(LocalDate.of(2026, 7, 21), 10_002_000_00L, "SSMF", "4070-ADCAP BALANCEADO 3 Clase A"),
        HoldingMovement(LocalDate.of(2026, 7, 28), 25_018_456_53L, "SSMF", "4070-ADCAP BALANCEADO 3 Clase A"),
        HoldingMovement(LocalDate.of(2026, 8, 6), 10_033_052_00L, "SSMF", "4070-ADCAP BALANCEADO 3 Clase A"),
        HoldingMovement(LocalDate.of(2026, 8, 10), 4_500_000_00L, "SSMF", "4070-ADCAP BALANCEADO 3 Clase A"),
        HoldingMovement(LocalDate.of(2026, 8, 12), 2_767_807_00L, "SSMF", "4070-ADCAP BALANCEADO 3 Clase A"),
        HoldingMovement(LocalDate.of(2026, 8, 31), 875_000_00L, "SSMF", "4070-ADCAP BALANCEADO 3 Clase A"),
        HoldingMovement(LocalDate.of(2026, 10, 7), 7_853_437_82L, "SSMF", "4070-ADCAP BALANCEADO 3 Clase A"),
        HoldingMovement(LocalDate.of(2026, 10, 15), -200_182_38L, "LRSM", "4070-ADCAP BALANCEADO 3 Clase A", -37342.38, 5360.729),
        HoldingMovement(LocalDate.of(2026, 10, 21), -600_600_53L, "LRSM", "4070-ADCAP BALANCEADO 3 Clase A", -111415.65, 5390.630),
        HoldingMovement(LocalDate.of(2026, 11, 2), 400_000_00L, "SSMF", "4070-ADCAP BALANCEADO 3 Clase A"),
        HoldingMovement(LocalDate.of(2026, 11, 11), 2_333_253_66L, "SSMF", "4070-ADCAP BALANCEADO 3 Clase A"),
    )

    fun summarize(movements: List<HoldingMovement>): BrokerImportSummary {
        val sorted = movements.sortedBy { it.date }
        val subscribed = sorted.filter { it.signedAmountCents > 0L }.sumOf { it.signedAmountCents }
        val redeemed = sorted.filter { it.signedAmountCents < 0L }.sumOf { -it.signedAmountCents }
        return BrokerImportSummary(
            movements = sorted,
            totalSubscribedCents = subscribed,
            totalRedeemedCents = redeemed,
            netInvestedCents = subscribed - redeemed,
        )
    }

    /**
     * Parses an exported broker file (Eco Valores HTML `.xls` or CSV/TSV) and extracts
     * capital movements matching [speciesFilter] (e.g. "4070", "ADCAP", "S30N6", "SPY", "AO27").
     */
    fun parseBrokerFile(rawContent: String, speciesFilter: String = ""): BrokerImportSummary {
        val clean = rawContent.trimStart('\uFEFF').trim()
        if (clean.isEmpty()) return summarize(emptyList())
        val movements = if (clean.contains("<table", ignoreCase = true) || clean.contains("<tr", ignoreCase = true)) {
            parseHtmlTableMovements(clean, speciesFilter)
        } else {
            parseCsvMovements(clean, speciesFilter)
        }
        return summarize(movements)
    }

    private fun parseHtmlTableMovements(html: String, speciesFilter: String): List<HoldingMovement> {
        val doc = Jsoup.parse(html)
        val results = mutableListOf<HoldingMovement>()
        val filterTokens = filterTokensFor(speciesFilter)

        for (table in doc.select("table")) {
            val rows = table.select("tr")
            if (rows.isEmpty()) continue
            var headerMap: Map<String, Int>? = null

            for (row in rows) {
                val cells = row.select("th, td").map { it.text().replace('\u00A0', ' ').trim() }
                if (cells.size < 5) continue

                if (headerMap == null) {
                    val lower = cells.map { it.lowercase() }
                    val opIdx = lower.indexOfFirst { it.contains("operaci") || it == "tipo" || it == "concepto" }
                    val impIdx = lower.indexOfFirst { it.contains("importe") || it.contains("monto") || it.contains("neto") }
                    if (opIdx >= 0 && impIdx >= 0) {
                        headerMap = mapOf(
                            "liquida" to lower.indexOfFirst { it.contains("liquida") },
                            "operado" to lower.indexOfFirst { it.contains("operado") || it.contains("fecha") },
                            "operacion" to opIdx,
                            "especie" to lower.indexOfFirst { it.contains("especie") || it.contains("instrumento") || it.contains("activo") },
                            "cantidad" to lower.indexOfFirst { it.contains("cantidad") || it.contains("nominal") || it.contains("cuotaparte") },
                            "precio" to lower.indexOfFirst { it.contains("precio") || it.contains("cotizaci") },
                            "importe" to impIdx,
                        )
                    }
                    continue
                }

                val map = headerMap
                val opCode = cellAt(cells, map["operacion"]).uppercase()
                val species = cellAt(cells, map["especie"])
                if (species.isBlank()) continue
                if (filterTokens.isNotEmpty() && filterTokens.none { token -> species.uppercase().contains(token) }) {
                    continue
                }

                // Skip caución rows (CLCC / CCTE / ESPVS) when importing investment holdings.
                if (opCode in setOf("CLCC", "CCTE", "COBR", "PAGO", "S.A.")) continue

                val operadoStr = cellAt(cells, map["operado"]).ifBlank { cellAt(cells, map["liquida"]) }
                val date = parseFlexibleDate(operadoStr) ?: continue
                val rawAmountCents = parseSignedCents(cellAt(cells, map["importe"])) ?: continue
                if (rawAmountCents == 0L) continue

                // In cash account statements (Cuenta Corriente de Pesos):
                // - SSMF / Compra is a cash outflow (negative in statement) -> positive contribution (+abs) to the holding.
                // - LRSM / Venta is a cash inflow (positive in statement) -> negative withdrawal (-abs) from the holding.
                val signedForHolding = when {
                    opCode.startsWith("SSMF") || opCode.startsWith("SUS") || opCode.startsWith("COMP") || opCode.startsWith("CPA") ->
                        kotlin.math.abs(rawAmountCents)
                    opCode.startsWith("LRSM") || opCode.startsWith("RES") || opCode.startsWith("VENT") || opCode.startsWith("VTA") ->
                        -kotlin.math.abs(rawAmountCents)
                    else -> -rawAmountCents
                }

                val qty = parseDecimal(cellAt(cells, map["cantidad"]))
                val price = parseDecimal(cellAt(cells, map["precio"]))

                results += HoldingMovement(
                    date = date,
                    signedAmountCents = signedForHolding,
                    operationCode = opCode,
                    species = species,
                    quantity = qty,
                    price = price,
                )
            }
        }
        return results
    }

    private fun parseCsvMovements(csv: String, speciesFilter: String): List<HoldingMovement> {
        val lines = csv.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return emptyList()
        val sep = if (lines.first().count { it == ';' } >= lines.first().count { it == ',' }) ';' else ','
        val results = mutableListOf<HoldingMovement>()
        val filterTokens = filterTokensFor(speciesFilter)
        var headerMap: Map<String, Int>? = null

        for (line in lines) {
            val cells = line.split(sep).map { it.trim().trim('"') }
            if (cells.size < 3) continue
            if (headerMap == null) {
                val lower = cells.map { it.lowercase() }
                val impIdx = lower.indexOfFirst { it.contains("importe") || it.contains("monto") || it.contains("neto") }
                val dateIdx = lower.indexOfFirst { it.contains("operado") || it.contains("fecha") || it.contains("liquida") }
                if (impIdx >= 0 && dateIdx >= 0) {
                    headerMap = mapOf(
                        "fecha" to dateIdx,
                        "operacion" to lower.indexOfFirst { it.contains("operaci") || it.contains("tipo") || it.contains("concepto") },
                        "especie" to lower.indexOfFirst { it.contains("especie") || it.contains("instrumento") || it.contains("ticker") },
                        "importe" to impIdx,
                    )
                }
                continue
            }
            val map = headerMap
            val species = cellAt(cells, map["especie"])
            if (filterTokens.isNotEmpty() && species.isNotBlank() && filterTokens.none { species.uppercase().contains(it) }) {
                continue
            }
            val opCode = cellAt(cells, map["operacion"]).uppercase()
            if (opCode in setOf("CLCC", "CCTE", "COBR", "PAGO", "S.A.")) continue
            val date = parseFlexibleDate(cellAt(cells, map["fecha"])) ?: continue
            val rawAmountCents = parseSignedCents(cellAt(cells, map["importe"])) ?: continue
            if (rawAmountCents == 0L) continue
            val signedForHolding = when {
                opCode.startsWith("SSMF") || opCode.startsWith("SUS") || opCode.startsWith("COMP") || opCode.startsWith("CPA") ->
                    kotlin.math.abs(rawAmountCents)
                opCode.startsWith("LRSM") || opCode.startsWith("RES") || opCode.startsWith("VENT") || opCode.startsWith("VTA") ->
                    -kotlin.math.abs(rawAmountCents)
                else -> -rawAmountCents
            }
            results += HoldingMovement(
                date = date,
                signedAmountCents = signedForHolding,
                operationCode = opCode,
                species = species,
            )
        }
        return results
    }

    /**
     * Parses movement log entries stored in `Holding.notes` (format: `dd/MM/yyyy: +$ 1.000,00`).
     */
    fun parseMovementNotes(notes: String): List<HoldingMovement> {
        if (notes.isBlank()) return emptyList()
        return notes.lineSequence()
            .mapNotNull { line ->
                val match = NOTE_LINE_REGEX.find(line.trim()) ?: return@mapNotNull null
                val date = parseFlexibleDate(match.groupValues[1]) ?: return@mapNotNull null
                val sign = if (match.groupValues[2] == "+" ) 1L else -1L
                val cents = MoneyParser.parseToCents(match.groupValues[3]) ?: return@mapNotNull null
                if (cents <= 0L) return@mapNotNull null
                HoldingMovement(date = date, signedAmountCents = sign * cents)
            }
            .sortedBy { it.date }
            .toList()
    }

    /**
     * Given the current end-of-period valuation [currentValueCents] and the dated movements
     * recorded in [notes], returns the effective capital that was invested on [date] by
     * backing out net contributions/withdrawals that occurred strictly after [date].
     */
    fun effectiveValueCentsOnDate(
        currentValueCents: Long,
        notes: String,
        date: LocalDate,
    ): Long {
        if (currentValueCents <= 0L || notes.isBlank()) return currentValueCents
        val movements = parseMovementNotes(notes)
        if (movements.isEmpty()) return currentValueCents
        val netAfterDate = movements
            .filter { it.date.isAfter(date) }
            .sumOf { it.signedAmountCents }
        return (currentValueCents - netAfterDate).coerceAtLeast(0L)
    }

    private fun filterTokensFor(rawFilter: String): List<String> {
        val clean = rawFilter.trim().uppercase()
        if (clean.isEmpty()) return emptyList()
        return buildList {
            if (clean.startsWith("CAFCI:")) {
                val parts = clean.split(':')
                if (parts.size >= 3) add(parts[2]) // classId, e.g. 4070
                if (parts.size >= 2) add(parts[1]) // fundId, e.g. 1402
            } else if (clean.startsWith("RAVA:")) {
                add(clean.removePrefix("RAVA:"))
            } else {
                add(clean)
            }
        }.filter { it.isNotBlank() }
    }

    private fun cellAt(cells: List<String>, index: Int?): String =
        if (index != null && index in cells.indices) cells[index] else ""

    internal fun parseFlexibleDate(raw: String): LocalDate? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return runCatching { LocalDate.parse(text, DATE_DD_MM_YYYY) }.getOrNull()
            ?: runCatching { LocalDate.parse(text, DATE_DD_MM_YY) }.getOrNull()
            ?: runCatching { LocalDate.parse(text) }.getOrNull()
    }

    internal fun parseSignedCents(raw: String): Long? {
        val clean = raw.trim()
        if (clean.isEmpty()) return null
        val negative = clean.startsWith("-") || clean.startsWith("−") || (clean.startsWith("(") && clean.endsWith(")"))
        val unsigned = clean.trimStart('-', '−', '+', '(', '$', ' ').trimEnd(')', ' ')
        val cents = MoneyParser.parseToCents(unsigned) ?: return null
        return if (negative) -cents else cents
    }

    private fun parseDecimal(raw: String): Double? {
        val clean = raw.trim().replace(" ", "")
        if (clean.isEmpty()) return null
        val normalized = when {
            clean.contains(',') && clean.contains('.') -> clean.replace(".", "").replace(',', '.')
            clean.contains(',') -> clean.replace(',', '.')
            else -> clean
        }
        return normalized.toDoubleOrNull()
    }
}
