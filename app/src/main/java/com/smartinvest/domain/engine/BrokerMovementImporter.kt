package com.smartinvest.domain.engine

import com.smartinvest.core.money.MoneyFormatter
import com.smartinvest.core.money.MoneyParser
import com.smartinvest.data.importer.XlsxReader
import com.smartinvest.domain.model.CurrencyCode
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

data class HoldingMovement(
    val date: LocalDate,
    /** Positive for contributions/purchases (SSMF, COMPRA), negative for redemptions/sales (LRSM, VENTA). */
    val signedAmountCents: Long,
    val operationCode: String = "",
    val species: String = "",
    /** Signed quantity delta (positive for purchases, negative for sales/redemptions). */
    val quantity: Double? = null,
    val price: Double? = null,
)

data class BrokerImportSummary(
    val movements: List<HoldingMovement>,
    val totalSubscribedCents: Long,
    val totalRedeemedCents: Long,
    val netInvestedCents: Long,
    val netQuantity: Double = 0.0,
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
                val qtyTag = mov.quantity?.takeIf { kotlin.math.abs(it) > 1e-6 }?.let { q ->
                    val qSign = if (q >= 0.0) "+" else "-"
                    val qAbs = formatPlainNumber(kotlin.math.abs(q))
                    " [cant: $qSign$qAbs]"
                }.orEmpty()
                "${mov.date.format(fmt)}: $sign$formatted$opTag$qtyTag"
            }
    }

    private fun formatPlainNumber(value: Double): String =
        if (value % 1.0 == 0.0) {
            String.format(Locale.US, "%.0f", value)
        } else {
            String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
        }
}

object BrokerMovementImporter {

    private val EXCEL_EPOCH: LocalDate = LocalDate.of(1899, 12, 30)
    private val DATE_DD_MM_YYYY = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val DATE_DD_MM_YY = DateTimeFormatter.ofPattern("dd/MM/yy")
    private val NOTE_LINE_REGEX = Regex(
        """^(\d{2}/\d{2}/\d{2,4})\s*:\s*([+-−])\s*(?:US\$|\$|USD|ARS)?\s*([\d.,]+)(?:.*?\[cant:\s*([+-]?\d+(?:[.,]\d+)?)\])?""",
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

    /**
     * Active movements exported from IEB+ (Comitente 315018, `IEB-2026-11-12-toda-la-actividad-315018.xlsx`)
     * for `SPY` (672 CEDEARs, cost $ 13.263.659,67).
     */
    val iebSpyMovements: List<HoldingMovement> = listOf(
        HoldingMovement(LocalDate.of(2026, 6, 9), 983_394_59L, "COMPRA", "SPY", 52.0, 18900.0),
        HoldingMovement(LocalDate.of(2026, 6, 10), 239_344_72L, "COMPRA", "SPY", 13.0, 18400.0),
        HoldingMovement(LocalDate.of(2026, 6, 11), 235_832_60L, "COMPRA", "SPY", 13.0, 18130.0),
        HoldingMovement(LocalDate.of(2026, 7, 29), 8_657_024_34L, "COMPRA", "SPY", 443.0, 19530.0),
        HoldingMovement(LocalDate.of(2026, 8, 31), 205_023_96L, "COMPRA", "SPY", 10.0, 20490.0),
        HoldingMovement(LocalDate.of(2026, 10, 7), 2_943_039_46L, "COMPRA", "SPY", 141.0, 20860.0),
    )

    /**
     * Active movements exported from IEB+ (Comitente 315018) for `S30N6`
     * (2.964.509 VN, cost $ 3.653.297,51).
     */
    val iebS30n6Movements: List<HoldingMovement> = listOf(
        HoldingMovement(LocalDate.of(2026, 8, 12), 1_455_261_05L, "COMPRA", "S30N6", 1207904.0, 120.477),
        HoldingMovement(LocalDate.of(2026, 8, 31), 234_657_62L, "COMPRA", "S30N6", 192300.0, 122.0256),
        HoldingMovement(LocalDate.of(2026, 10, 7), 1_963_378_84L, "COMPRA", "S30N6", 1564305.0, 125.510),
    )

    /**
     * Active movement exported from IEB+ (Comitente 315018) for `T15E7`
     * (504.061 VN, cost $ 777.740,20).
     */
    val iebT15e7Movements: List<HoldingMovement> = listOf(
        HoldingMovement(LocalDate.of(2026, 11, 11), 777_740_20L, "COMPRA", "T15E7", 504061.0, 154.291),
    )

    /**
     * Active movement exported from IEB+ (Comitente 315018) for `AO27`
     * (481 VN bought in ARS for $ 776.642,02 @ $ 161.460 c/100 VN).
     */
    val iebAo27Movements: List<HoldingMovement> = listOf(
        HoldingMovement(LocalDate.of(2026, 11, 11), 776_642_02L, "COMPRA", "AO27", 481.0, 161460.0),
    )

    fun presetMovementsFor(symbol: String, marketKey: String): List<HoldingMovement>? {
        val sym = symbol.trim().uppercase()
        val key = marketKey.trim().uppercase()
        return when {
            key.contains("1402:4070") || sym.contains("ADCAP") || sym.contains("BAL") -> ecoValoresAdcapBal3Movements
            sym == "SPY" || key == "RAVA:SPY" -> iebSpyMovements
            sym == "S30N6" || key == "RAVA:S30N6" -> iebS30n6Movements
            sym == "T15E7" || key == "RAVA:T15E7" -> iebT15e7Movements
            sym.startsWith("AO27") || key.startsWith("RAVA:AO27") -> iebAo27Movements
            else -> null
        }
    }

    fun summarize(movements: List<HoldingMovement>): BrokerImportSummary {
        val active = filterActiveLotMovements(movements)
        val subscribed = active.filter { it.signedAmountCents > 0L }.sumOf { it.signedAmountCents }
        val redeemed = active.filter { it.signedAmountCents < 0L }.sumOf { -it.signedAmountCents }
        val netQty = active.sumOf { it.quantity ?: 0.0 }.coerceAtLeast(0.0)
        return BrokerImportSummary(
            movements = active,
            totalSubscribedCents = subscribed,
            totalRedeemedCents = redeemed,
            netInvestedCents = subscribed - redeemed,
            netQuantity = netQty,
        )
    }

    /**
     * When a security has full buy/sell quantity history (such as IEB+ exports where an older
     * SPY lot of 56 shares was sold in February before the current 672-share position started
     * in June), resets the accumulator whenever a sale brings the running quantity to `<= 0`.
     */
    private fun filterActiveLotMovements(movements: List<HoldingMovement>): List<HoldingMovement> {
        val sorted = movements.sortedBy { it.date }
        val allHaveQuantity = sorted.isNotEmpty() && sorted.all { it.quantity != null && kotlin.math.abs(it.quantity) > 1e-6 }
        if (!allHaveQuantity) return sorted

        val active = mutableListOf<HoldingMovement>()
        var runningQty = 0.0
        for (mov in sorted) {
            val q = mov.quantity ?: 0.0
            runningQty += q
            if (runningQty <= 1e-4) {
                runningQty = 0.0
                active.clear()
            } else {
                active += mov
            }
        }
        return active
    }

    /**
     * Parses a broker file from raw bytes (supports `.xlsx` ZIP workbooks from IEB+,
     * `.xls` HTML tables from Eco Valores, and `.csv` / `.tsv` files).
     */
    fun parseBrokerFileBytes(bytes: ByteArray, speciesFilter: String = ""): BrokerImportSummary {
        if (bytes.isEmpty()) return summarize(emptyList())
        if (isZipArchive(bytes)) {
            val sheets = runCatching { XlsxReader.read(bytes) }.getOrNull().orEmpty()
            val movements = sheets.flatMap { sheet -> parseTableRows(sheet.rows, speciesFilter) }
            return summarize(movements)
        }
        val text = bytes.toString(Charsets.UTF_8)
        return parseBrokerFile(text, speciesFilter)
    }

    /**
     * Parses an exported broker text file (Eco Valores HTML `.xls` or CSV/TSV) and extracts
     * capital movements matching [speciesFilter] (e.g. "4070", "ADCAP", "S30N6", "SPY", "AO27", "T15E7").
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

    /**
     * Extracts all open positions grouped by ticker/species from a multi-asset broker file
     * (such as IEB+'s `toda-la-actividad.xlsx` or Eco Valores's `Movimientos de Pesos.xls`).
     */
    fun parseAllOpenPositions(bytes: ByteArray): Map<String, BrokerImportSummary> {
        val allMovements = if (isZipArchive(bytes)) {
            val sheets = runCatching { XlsxReader.read(bytes) }.getOrNull().orEmpty()
            sheets.flatMap { sheet -> parseTableRows(sheet.rows, "") }
        } else {
            val text = bytes.toString(Charsets.UTF_8).trimStart('\uFEFF').trim()
            if (text.contains("<table", ignoreCase = true) || text.contains("<tr", ignoreCase = true)) {
                parseHtmlTableMovements(text, "")
            } else {
                parseCsvMovements(text, "")
            }
        }
        return allMovements
            .groupBy { it.species.trim().uppercase() }
            .filterKeys { it.isNotBlank() && it !in setOf("CAUCION", "DOLAR", "ESPVS") }
            .mapValues { (_, movs) -> summarize(movs) }
            .filterValues { it.movements.isNotEmpty() && it.netInvestedCents > 0L }
    }

    private fun isZipArchive(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    private fun parseHtmlTableMovements(html: String, speciesFilter: String): List<HoldingMovement> {
        val doc = Jsoup.parse(html)
        val results = mutableListOf<HoldingMovement>()
        for (table in doc.select("table")) {
            val rows = table.select("tr").map { row ->
                row.select("th, td").map { it.text().replace('\u00A0', ' ').trim() }
            }
            results += parseTableRows(rows, speciesFilter)
        }
        return results
    }

    private fun parseCsvMovements(csv: String, speciesFilter: String): List<HoldingMovement> {
        val lines = csv.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size < 2) return emptyList()
        val sep = if (lines.first().count { it == ';' } >= lines.first().count { it == ',' }) ';' else ','
        val rows = lines.map { line -> line.split(sep).map { it.trim().trim('"') } }
        return parseTableRows(rows, speciesFilter)
    }

    internal fun parseTableRows(rows: List<List<String>>, speciesFilter: String): List<HoldingMovement> {
        val results = mutableListOf<HoldingMovement>()
        val filterTokens = filterTokensFor(speciesFilter)
        var headerMap: Map<String, Int>? = null

        for (cells in rows) {
            if (cells.size < 3) continue
            if (headerMap == null) {
                val lower = cells.map { it.lowercase() }
                val opIdx = lower.indexOfFirst { it.contains("operaci") || it == "tipo" || it == "concepto" }
                val impIdx = lower.indexOfFirst {
                    it.contains("importe ars") || it == "importe" || it.contains("monto") || it.contains("neto")
                }
                val dateIdx = lower.indexOfFirst {
                    it.contains("operado") || it.contains("fecha emisi") || it.contains("fecha") || it.contains("liquida")
                }
                if (opIdx >= 0 && impIdx >= 0 && dateIdx >= 0) {
                    headerMap = mapOf(
                        "liquida" to lower.indexOfFirst { it.contains("liquida") },
                        "operado" to dateIdx,
                        "operacion" to opIdx,
                        "especie" to lower.indexOfFirst {
                            it.contains("especie") || it.contains("referencia") || it.contains("instrumento") || it.contains("ticker")
                        },
                        "cantidad" to lower.indexOfFirst { it.contains("cantidad") || it.contains("nominal") || it.contains("cuotaparte") },
                        "precio" to lower.indexOfFirst { it.contains("precio") || it.contains("cotizaci") },
                        "importe" to impIdx,
                    )
                }
                continue
            }

            val map = headerMap
            val rawOp = cellAt(cells, map["operacion"]).uppercase().trim()
            val species = cellAt(cells, map["especie"]).trim()
            if (species.isBlank()) continue
            val upperSpecies = species.uppercase()
            if (upperSpecies in setOf("CAUCION", "DOLAR", "ESPVS")) continue
            if (filterTokens.isNotEmpty() && filterTokens.none { token ->
                    upperSpecies == token || upperSpecies.contains(token) || token.contains(upperSpecies)
                }
            ) {
                continue
            }

            // Only include actual investment subscription/redemption/buy/sell operations.
            val isBuyOrSub = rawOp.startsWith("SSMF") ||
                rawOp.contains("SUSCRIP") ||
                rawOp.startsWith("COMPRA") ||
                rawOp.startsWith("CPA")
            val isSellOrRedeem = rawOp.startsWith("LRSM") ||
                rawOp.contains("RESCATE") ||
                rawOp.startsWith("VENTA") ||
                rawOp.startsWith("VTA")
            if (!isBuyOrSub && !isSellOrRedeem) continue

            val operadoStr = cellAt(cells, map["operado"]).ifBlank { cellAt(cells, map["liquida"]) }
            val date = parseFlexibleDate(operadoStr) ?: continue
            val rawAmountCents = parseSignedCents(cellAt(cells, map["importe"])) ?: continue
            if (rawAmountCents == 0L) continue

            val signedForHolding = if (isBuyOrSub) {
                kotlin.math.abs(rawAmountCents)
            } else {
                -kotlin.math.abs(rawAmountCents)
            }

            val rawQty = parseDecimal(cellAt(cells, map["cantidad"]))
            val signedQty = rawQty?.let { q ->
                if (isBuyOrSub) kotlin.math.abs(q) else -kotlin.math.abs(q)
            }
            val price = parseDecimal(cellAt(cells, map["precio"]))
            val shortOp = when {
                rawOp.startsWith("SSMF") || rawOp.contains("SUSCRIP") -> "SSMF"
                rawOp.startsWith("LRSM") || rawOp.contains("RESCATE") -> "LRSM"
                isBuyOrSub -> "COMPRA"
                else -> "VENTA"
            }

            results += HoldingMovement(
                date = date,
                signedAmountCents = signedForHolding,
                operationCode = shortOp,
                species = species,
                quantity = signedQty,
                price = price,
            )
        }
        return results
    }

    /**
     * Parses movement log entries stored in `Holding.notes`
     * (format: `dd/MM/yyyy: +$ 1.000,00 (COMPRA) [cant: +52]`).
     */
    fun parseMovementNotes(notes: String): List<HoldingMovement> {
        if (notes.isBlank()) return emptyList()
        return notes.lineSequence()
            .mapNotNull { line ->
                val match = NOTE_LINE_REGEX.find(line.trim()) ?: return@mapNotNull null
                val date = parseFlexibleDate(match.groupValues[1]) ?: return@mapNotNull null
                val sign = if (match.groupValues[2] == "+") 1L else -1L
                val cents = MoneyParser.parseToCents(match.groupValues[3]) ?: return@mapNotNull null
                if (cents <= 0L) return@mapNotNull null
                val qty = match.groupValues.getOrNull(4)?.takeIf { it.isNotBlank() }?.let(::parseDecimal)
                HoldingMovement(date = date, signedAmountCents = sign * cents, quantity = qty)
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
        val totalNetMovements = movements.sumOf { it.signedAmountCents }
        if (date.isBefore(movements.first().date) && totalNetMovements >= (currentValueCents * 0.80).toLong()) {
            return 0L
        }
        val netAfterDate = movements
            .filter { it.date.isAfter(date) }
            .sumOf { it.signedAmountCents }
        return (currentValueCents - netAfterDate).coerceAtLeast(0L)
    }

    /**
     * Given the current [currentQuantity] and the dated movements recorded in [notes],
     * returns the effective quantity held on [date].
     */
    fun effectiveQuantityOnDate(
        currentQuantity: Double,
        currentValueCents: Long,
        notes: String,
        date: LocalDate,
    ): Double {
        if (currentQuantity <= 0.0 || notes.isBlank()) return currentQuantity
        val movements = parseMovementNotes(notes)
        if (movements.isEmpty()) return currentQuantity
        val afterMovements = movements.filter { it.date.isAfter(date) }
        val hasExplicitQuantities = movements.any { it.quantity != null && kotlin.math.abs(it.quantity) > 1e-6 }
        if (hasExplicitQuantities) {
            val totalNetQty = movements.sumOf { it.quantity ?: 0.0 }
            if (date.isBefore(movements.first().date) && totalNetQty >= currentQuantity * 0.80) {
                return 0.0
            }
            if (afterMovements.isEmpty()) return currentQuantity
            val qtyAfter = afterMovements.sumOf { it.quantity ?: 0.0 }
            return (currentQuantity - qtyAfter).coerceAtLeast(0.0)
        }
        if (currentValueCents > 0L) {
            val effVal = effectiveValueCentsOnDate(currentValueCents, notes, date)
            return (currentQuantity * (effVal.toDouble() / currentValueCents.toDouble())).coerceAtLeast(0.0)
        }
        return currentQuantity
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
                val sym = clean.removePrefix("RAVA:")
                add(sym)
                if (sym.endsWith("D") && sym.length > 3) add(sym.removeSuffix("D"))
            } else {
                add(clean)
                if (clean.endsWith("D") && clean.length > 3) add(clean.removeSuffix("D"))
            }
        }.filter { it.isNotBlank() }
    }

    private fun cellAt(cells: List<String>, index: Int?): String =
        if (index != null && index in cells.indices) cells[index] else ""

    internal fun parseFlexibleDate(raw: String): LocalDate? {
        val text = raw.trim()
        if (text.isEmpty() || text == "-") return null
        // Excel serial date (e.g. "46337.125" or "46182")
        text.toDoubleOrNull()?.let { serial ->
            if (serial in 30_000.0..80_000.0) {
                return EXCEL_EPOCH.plusDays(serial.toLong())
            }
        }
        return runCatching { LocalDate.parse(text, DATE_DD_MM_YYYY) }.getOrNull()
            ?: runCatching { LocalDate.parse(text, DATE_DD_MM_YY) }.getOrNull()
            ?: runCatching { LocalDate.parse(text.take(10)) }.getOrNull()
    }

    internal fun parseSignedCents(raw: String): Long? {
        val clean = raw.trim()
        if (clean.isEmpty() || clean == "-") return null
        val negative = clean.startsWith("-") || clean.startsWith("−") || (clean.startsWith("(") && clean.endsWith(")"))
        val unsigned = clean.trimStart('-', '−', '+', '(', '$', ' ').trimEnd(')', ' ')
        // Distinguish plain float strings from XLSX (e.g. "983394.59") vs Argentine formatted ("983.394,59")
        val cents = if (unsigned.contains('.') && !unsigned.contains(',')) {
            val dotAfter = unsigned.substringAfterLast('.')
            if (dotAfter.length != 3) {
                unsigned.toDoubleOrNull()?.let { kotlin.math.round(it * 100.0).toLong() }
            } else {
                MoneyParser.parseToCents(unsigned)
            }
        } else {
            MoneyParser.parseToCents(unsigned)
        } ?: return null
        return if (negative) -cents else cents
    }

    private fun parseDecimal(raw: String): Double? {
        val clean = raw.trim().replace(" ", "")
        if (clean.isEmpty() || clean == "-") return null
        val normalized = when {
            clean.contains(',') && clean.contains('.') -> clean.replace(".", "").replace(',', '.')
            clean.contains(',') -> clean.replace(',', '.')
            else -> clean
        }
        return normalized.toDoubleOrNull()
    }
}
