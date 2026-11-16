package com.smartinvest.data.importer

import com.smartinvest.core.money.MoneyParser
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Tolerant, dependency-free text parser for bank / wallet receipts and credit card statements.
 *
 * Receipts differ a lot between issuers, so the parser never fails: it extracts what it
 * can and returns warnings for the rest. The import screen always shows every value as
 * an editable field before saving.
 */
object ReceiptParser {

    private val FLAGS = RegexOption.IGNORE_CASE
    private val localeAr: Locale = Locale.forLanguageTag("es-AR")
    private val genericConcepts = setOf("servicio", "servicios", "compra", "pago", "varios")

    private val currencyAmountRegex = Regex("""(?:\$|\bARS\b)\s*([0-9][0-9.,]*)""", FLAGS)
    private val labeledAmountRegex = Regex(
        """\b(importe|monto|total(?:\s+a\s+pagar)?|pagado)\b\s*[^\d\n]{0,10}(\d[0-9.,]*)""",
        FLAGS,
    )
    private val totalAPagarRegex = Regex(
        """\btotal\s+a\s+pagar\b\s*[:\$]?\s*([0-9]{1,3}(?:\.[0-9]{3})*,[0-9]{2}|\d+[.,]\d{2})""",
        FLAGS,
    )
    private val standaloneFormattedAmountRegex = Regex(
        """^\s*\$?\s*([0-9]{1,3}(?:\.[0-9]{3})+,[0-9]{2})\s*$""",
    )
    private val cardHeaderRegex = Regex(
        """(?:tarjeta\s+(?:de\s+)?cr[eé]dito|resumen\s+de\s+tarjeta\s+de\s+cr[eé]dito)\s+([A-ZÁÉÍÓÚÑa-záéíóúñ0-9 ]+?)(?:\s+p[aá]gina\b|\s*$)""",
        FLAGS,
    )
    private val resumenNumberRegex = Regex(
        """\bresumen\s+n[°ºo.]*\s*([0-9]{6,})\b""",
        FLAGS,
    )
    /** Label alone on a line: "Por un importe de" / "Importe" / "Monto". */
    private val amountLabelOnlyRegex = Regex(
        """^\s*(?:por\s+un\s+)?(importe|monto|total)(?:\s+a\s+pagar|\s+en\s+pesos)?(?:\s+de)?\s*:?\s*$""",
        FLAGS,
    )
    private val singleIdRegex = Regex("""^[A-Za-z0-9][A-Za-z0-9\-]{3,}$""")
    private val dateDmyRegex = Regex("""\b(\d{1,2})/(\d{1,2})/(\d{2,4})\b""")
    private val dateHyphenMonthRegex = Regex(
        """\b(\d{1,2})[-/]([a-zA-ZáéíóúÁÉÍÓÚ]{3,10})[-/](\d{2,4})\b""",
        FLAGS,
    )
    private val dateTextRegex = Regex(
        """\b(\d{1,2})\s*(?:de\s+)?([a-zA-ZáéíóúÁÉÍÓÚ]+)\s*(?:de\s+)?(\d{4})\b""",
        FLAGS,
    )
    private val dateMonthNameRegex = Regex("""\b(\d{1,2})/([a-zA-ZáéíóúÁÉÍÓÚ]+)/(\d{2,4})\b""", FLAGS)
    private val isoDateRegex = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")

    private val serviceLabelRegex = Regex(
        """\b(empresa|servicio|prestador|comercio|raz[oó]n\s+social|beneficiario|destinatario)\b\s*[:\-]?\s*(.+)""",
        FLAGS,
    )
    private val paymentConceptRegex = Regex(
        """^\s*(?:pago|transferencia|d[eé]bito)\s+(?:de|a|para)\s+(.+)$""",
        FLAGS,
    )
    private val leadingPagoDeRegex = Regex(
        """^\s*pago\s+de(?:\s+servicios?)?\s*[:\-]?\s*""",
        FLAGS,
    )
    private val referenceLabelRegex = Regex(
        """\b(nro\.?\s*°?|n[uú]mero|operaci[oó]n|c[oó]digo|comprobante|transacci[oó]n)\b""",
        FLAGS,
    )
    private val referenceNoise = setOf(
        "trans", "transf", "transferencia", "operacion", "operación", "numero", "número",
        "comprobante", "codigo", "código", "nro", "n°", "de", "del",
    )

    private val monthNames = mapOf(
        "ene" to 1,
        "enero" to 1,
        "feb" to 2,
        "febrero" to 2,
        "mar" to 3,
        "marzo" to 3,
        "abr" to 4,
        "abril" to 4,
        "may" to 5,
        "mayo" to 5,
        "jun" to 6,
        "junio" to 6,
        "jul" to 7,
        "julio" to 7,
        "ago" to 8,
        "agosto" to 8,
        "sep" to 9,
        "set" to 9,
        "sept" to 9,
        "septiembre" to 9,
        "setiembre" to 9,
        "oct" to 10,
        "octubre" to 10,
        "nov" to 11,
        "noviembre" to 11,
        "dic" to 12,
        "dec" to 12,
        "diciembre" to 12,
    )

    private data class KnownInstitution(val canonical: String, val aliases: List<String>)

    private val institutions = listOf(
        KnownInstitution("Mercado Pago", listOf("mercado pago", "mercadopago")),
        KnownInstitution("Ualá", listOf("ualá", "uala")),
        KnownInstitution("Personal Pay", listOf("personal pay", "personalpay")),
        KnownInstitution("Naranja X", listOf("naranja x", "naranjax")),
        KnownInstitution("Brubank", listOf("brubank")),
        KnownInstitution("Banco Galicia", listOf("banco de galicia", "banco galicia", "bancogalicia", "galicia", "30-50000173-5")),
        KnownInstitution("Banco Santander", listOf("santander")),
        KnownInstitution("BBVA", listOf("bbva", "banco francés", "banco frances")),
        KnownInstitution("Banco Nación", listOf("banco nación", "banco nacion", "bna")),
        KnownInstitution("Banco Provincia", listOf("banco provincia", "cuenta dni")),
        KnownInstitution("Banco Macro", listOf("banco macro", "macro")),
        KnownInstitution("Banco Ciudad", listOf("banco ciudad")),
        KnownInstitution("Banco Supervielle", listOf("supervielle")),
        KnownInstitution("Banco Credicoop", listOf("credicoop")),
        KnownInstitution("ICBC", listOf("icbc")),
        KnownInstitution("HSBC", listOf("hsbc")),
        KnownInstitution("Cocos Capital", listOf("cocos")),
        KnownInstitution("Balanz", listOf("balanz")),
        KnownInstitution("Allaria", listOf("allaria")),
        KnownInstitution("Adcap", listOf("adcap")),
        KnownInstitution("InvertirOnline", listOf("invertironline", "iol")),
        KnownInstitution("Portfolio Personal", listOf("portfolio personal", "ppi")),
    )

    private data class StatementCycleDates(
        val currentClosingDate: LocalDate? = null,
        val dueDate: LocalDate? = null,
        val nextClosingDate: LocalDate? = null,
        val nextDueDate: LocalDate? = null,
    )

    fun parse(rawText: String): ParsedReceipt {
        val normalizedText = rawText.replace('\u00A0', ' ')
        val lines = normalizedText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val kind = detectKind(normalizedText, lines)
        if (kind == ReceiptKind.CARD_STATEMENT) {
            return parseCardStatement(rawText, normalizedText, lines)
        }

        val amount = extractAmount(lines)
        val date = extractDate(lines)
        val originLine = labeledLine(lines, "origen") ?: labeledLine(lines, "cuenta origen")
        val destinationLine = labeledLine(lines, "destino") ?: labeledLine(lines, "cuenta destino")
        val origin = originLine?.let { institutionIn(it) } ?: institutionIn(normalizedText)
        val destination = destinationLine?.let { institutionIn(it) }
        val labeledService = serviceLabelRegex.find(normalizedText)?.groupValues?.getOrNull(2)
            ?.trim()
            ?.take(90)
            ?.takeIf { it.isNotBlank() }
        val conceptLine = lines.firstOrNull { line ->
            val concept = paymentConceptRegex.find(line)?.groupValues?.getOrNull(1)?.trim()
            !concept.isNullOrBlank() && concept.lowercase(localeAr) !in genericConcepts
        }
        val conceptName = conceptLine
            ?.let { paymentConceptRegex.find(it)?.groupValues?.getOrNull(1)?.trim() }
            ?.take(90)
            ?.takeIf { it.isNotBlank() }
        val service = (labeledService ?: conceptName)
            ?.let(::stripPagoDePrefix)
            ?.takeIf { it.isNotBlank() }
        val reference = extractReference(lines)

        val explicitOwnTransfer = Regex(
            """cuentas?\s+propias?|mis\s+cuentas|entre\s+cuentas""",
            FLAGS,
        ).containsMatchIn(normalizedText)
        val likelyOwnTransfer = explicitOwnTransfer ||
            (kind == ReceiptKind.TRANSFER && origin != null && destination != null && origin != destination)

        val rawDescription = when {
            kind == ReceiptKind.TRANSFER && origin != null && destination != null ->
                "Transferencia $origin → $destination"
            kind == ReceiptKind.TRANSFER -> "Transferencia"
            service != null -> service
            conceptLine != null -> conceptLine.trim()
            destination != null -> "Pago a $destination"
            origin != null -> "Pago desde $origin"
            else -> "Gasto desde comprobante"
        }
        val description = stripPagoDePrefix(rawDescription).ifBlank { rawDescription }

        val warnings = buildList {
            if (amount == null) add("No se detectó el importe. Ingresalo manualmente.")
            if (date == null) add("No se detectó la fecha. Elegila manualmente.")
            if (likelyOwnTransfer) {
                add(
                    "Parece una transferencia entre cuentas propias. Si es así, no la registres " +
                        "como gasto del hogar: solo mueve plata entre tus cuentas.",
                )
            }
            if (kind == ReceiptKind.UNKNOWN) {
                add("No se pudo clasificar el comprobante. Revisá la descripción y el importe.")
            }
            add("Verificá los datos antes de guardar: el texto del comprobante puede variar entre emisores.")
        }

        return ParsedReceipt(
            kind = kind,
            description = description,
            amountCents = amount,
            date = date,
            originInstitution = origin,
            destinationInstitution = destination,
            serviceName = service,
            reference = reference,
            likelyOwnTransfer = likelyOwnTransfer,
            warnings = warnings,
            rawText = rawText,
        )
    }

    private fun parseCardStatement(
        rawText: String,
        normalizedText: String,
        lines: List<String>,
    ): ParsedReceipt {
        val cardName = extractCardName(lines, normalizedText)
        val institution = institutionIn(normalizedText)
        val amount = extractStatementAmount(lines, normalizedText)
        val cycle = extractStatementCycleDates(lines)
        val dueDate = cycle.dueDate ?: extractDate(lines)
        val nextClosingDate = cycle.nextClosingDate
        val reference = resumenNumberRegex.find(normalizedText)?.groupValues?.getOrNull(1)
            ?: extractReference(lines)
        val description = if (!cardName.isNullOrBlank()) {
            "Resumen $cardName"
        } else {
            "Resumen de tarjeta"
        }

        val warnings = buildList {
            if (amount == null) add("No se detectó el monto a pagar del resumen. Ingresalo manualmente.")
            if (dueDate == null) add("No se detectó la fecha de vencimiento. Elegila manualmente.")
            if (nextClosingDate == null) add("No se detectó la fecha de próximo cierre. Podés elegirla manualmente.")
            add("Se registrarán las alertas de vencimiento y próximo cierre junto con el monto a pagar.")
        }

        return ParsedReceipt(
            kind = ReceiptKind.CARD_STATEMENT,
            description = description,
            amountCents = amount,
            date = dueDate,
            originInstitution = institution,
            destinationInstitution = null,
            serviceName = cardName,
            reference = reference,
            likelyOwnTransfer = false,
            warnings = warnings,
            rawText = rawText,
            cardName = cardName,
            dueDate = dueDate,
            nextClosingDate = nextClosingDate,
            currentClosingDate = cycle.currentClosingDate,
            nextDueDate = cycle.nextDueDate,
        )
    }

    private fun extractCardName(lines: List<String>, fullText: String): String? {
        for (line in lines) {
            val match = cardHeaderRegex.find(line) ?: continue
            val candidate = match.groupValues.getOrNull(1)?.trim().orEmpty()
            if (candidate.isNotBlank()) return candidate.uppercase(localeAr)
        }
        val knownBrands = listOf(
            "MASTERCARD BLACK",
            "MASTERCARD PLATINUM",
            "MASTERCARD GOLD",
            "MASTERCARD",
            "VISA SIGNATURE",
            "VISA PLATINUM",
            "VISA GOLD",
            "VISA",
            "AMERICAN EXPRESS",
            "AMEX",
        )
        val upper = fullText.uppercase(localeAr)
        return knownBrands.firstOrNull { it in upper }
    }

    private fun extractStatementAmount(lines: List<String>, fullText: String): Long? {
        // 1. Explicit "TOTAL A PAGAR 247.282,15" line.
        for (line in lines) {
            totalAPagarRegex.find(line)?.groupValues?.getOrNull(1)?.let(::amountCents)?.let {
                if (it > 0L) return it
            }
        }
        totalAPagarRegex.find(fullText)?.groupValues?.getOrNull(1)?.let(::amountCents)?.let {
            if (it > 0L) return it
        }

        // 2. "Total en pesos" or "Total a pagar" followed by the amount on the next line.
        lines.forEachIndexed { idx, line ->
            val lower = line.lowercase(localeAr)
            if (lower == "total en pesos" || lower == "total a pagar") {
                for (offset in 1..3) {
                    val next = lines.getOrNull(idx + offset) ?: break
                    standaloneFormattedAmountRegex.find(next)?.groupValues?.getOrNull(1)?.let(::amountCents)?.let {
                        if (it > 0L) return it
                    }
                }
            }
        }

        // 3. Standalone formatted amount right before the 6-date billing cycle line (Galicia hero box).
        val cycleIdx = lines.indexOfFirst { findAllDatesInLine(it).size >= 4 }
        if (cycleIdx > 0) {
            for (i in 0 until cycleIdx) {
                standaloneFormattedAmountRegex.find(lines[i])?.groupValues?.getOrNull(1)?.let(::amountCents)?.let {
                    if (it > 0L) return it
                }
            }
        }

        return extractAmount(lines)
    }

    private fun extractStatementCycleDates(lines: List<String>): StatementCycleDates {
        // 1. Look for a 6-date billing cycle line:
        //    [0] Cierre anterior, [1] Vencimiento anterior, [2] Cierre actual,
        //    [3] Vencimiento actual, [4] Próximo cierre, [5] Próximo vencimiento.
        for (line in lines) {
            val dates = findAllDatesInLine(line)
            if (dates.size >= 6) {
                return StatementCycleDates(
                    currentClosingDate = dates[2],
                    dueDate = dates[3],
                    nextClosingDate = dates[4],
                    nextDueDate = dates[5],
                )
            }
            if (dates.size == 4) {
                return StatementCycleDates(
                    currentClosingDate = dates[0],
                    dueDate = dates[1],
                    nextClosingDate = dates[2],
                    nextDueDate = dates[3],
                )
            }
        }

        // 2. Look for labeled dates ("Vencimiento actual", "Vencimiento", "Próximo cierre").
        var dueDate: LocalDate? = null
        var nextClosing: LocalDate? = null
        var currentClosing: LocalDate? = null
        lines.forEachIndexed { idx, line ->
            val lower = line.lowercase(localeAr)
            val dateHere = parseDateIn(line) ?: lines.getOrNull(idx + 1)?.let(::parseDateIn)
            if (dateHere != null) {
                when {
                    ("próximo cierre" in lower || "proximo cierre" in lower || "cierre próximo" in lower || "cierre proximo" in lower) && nextClosing == null ->
                        nextClosing = dateHere
                    ("vencimiento actual" in lower || ("vencimiento" in lower && "anterior" !in lower && "próximo" !in lower && "proximo" !in lower)) && dueDate == null ->
                        dueDate = dateHere
                    ("cierre actual" in lower || ("cierre" in lower && "anterior" !in lower && "próximo" !in lower && "proximo" !in lower)) && currentClosing == null ->
                        currentClosing = dateHere
                }
            }
        }
        return StatementCycleDates(
            currentClosingDate = currentClosing,
            dueDate = dueDate,
            nextClosingDate = nextClosing,
        )
    }

    private fun findAllDatesInLine(line: String): List<LocalDate> {
        val results = mutableListOf<Pair<Int, LocalDate>>()
        dateHyphenMonthRegex.findAll(line).forEach { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@forEach
            val month = monthNumber(match.groupValues[2]) ?: return@forEach
            val yearRaw = match.groupValues[3].toIntOrNull() ?: return@forEach
            val year = if (yearRaw < 100) 2000 + yearRaw else yearRaw
            safeDate(day, month, year)?.let { results += match.range.first to it }
        }
        dateDmyRegex.findAll(line).forEach { match ->
            if (results.any { it.first == match.range.first }) return@forEach
            val day = match.groupValues[1].toIntOrNull() ?: return@forEach
            val month = match.groupValues[2].toIntOrNull() ?: return@forEach
            val yearRaw = match.groupValues[3].toIntOrNull() ?: return@forEach
            val year = if (yearRaw < 100) 2000 + yearRaw else yearRaw
            safeDate(day, month, year)?.let { results += match.range.first to it }
        }
        return results.sortedBy { it.first }.map { it.second }
    }

    private fun detectKind(text: String, lines: List<String>): ReceiptKind {
        val normalized = text.lowercase(localeAr)
        val hasStatementKeywords = "resumen de tarjeta" in normalized ||
            "tarjeta crédito" in normalized ||
            "tarjeta de crédito" in normalized ||
            "tarjeta de credito" in normalized ||
            ("total a pagar" in normalized && ("pago minimo" in normalized || "pago mínimo" in normalized || "detalle del consumo" in normalized || "cuotas a vencer" in normalized))
        val hasSixDateCycle = lines.any { findAllDatesInLine(it).size >= 6 }
        return when {
            hasStatementKeywords || hasSixDateCycle -> ReceiptKind.CARD_STATEMENT
            "transferencia" in normalized -> ReceiptKind.TRANSFER
            listOf("comprobante de pago", "pago de servicio", "importe", "factura", "servicio")
                .any { it in normalized } -> ReceiptKind.EXPENSE
            else -> ReceiptKind.UNKNOWN
        }
    }

    private fun extractAmount(lines: List<String>): Long? {
        // 1. Label and amount on the same line: "Importe: $ 15.105,73".
        val sameLine = lines.mapNotNull { line ->
            if (line.looksLikeNonAmountLine()) return@mapNotNull null
            labeledAmountRegex.find(line)?.groupValues?.getOrNull(2)?.let(::amountCents)
        }.filter { it > 0 }
        if (sameLine.isNotEmpty()) return sameLine.maxOrNull()

        // 2. Label on its own line and amount on the next one: "Por un importe de" + "$15105.73".
        lines.forEachIndexed { index, line ->
            if (!amountLabelOnlyRegex.matches(line)) return@forEachIndexed
            for (offset in 1..3) {
                val candidate = lines.getOrNull(index + offset) ?: break
                if (candidate.looksLikeNonAmountLine()) break
                amountFromLine(candidate)?.let { return it }
            }
        }

        // 3. Any "$" amount, ignoring account / transaction / balance numbers.
        val candidates = lines.flatMap { line ->
            if (line.looksLikeNonAmountLine()) return@flatMap emptyList()
            currencyAmountRegex.findAll(line).mapNotNull { match ->
                val token = match.groupValues.getOrNull(1).orEmpty()
                if (token.looksLikeIdNumber()) null else amountCents(token)
            }.toList()
        }
        return candidates.maxOrNull()
    }

    /** First plausible money token of a line, skipping long digit runs used as IDs. */
    private fun amountFromLine(line: String): Long? {
        if (line.looksLikeNonAmountLine()) return null
        currencyAmountRegex.findAll(line).forEach { match ->
            val token = match.groupValues.getOrNull(1).orEmpty()
            if (!token.looksLikeIdNumber()) {
                amountCents(token)?.let { return it }
            }
        }
        return null
    }

    private fun amountCents(raw: String): Long? {
        val cleaned = raw.trim().trimEnd('.', ',')
        if (cleaned.isEmpty()) return null
        return MoneyParser.parseToCents(cleaned)
    }

    /**
     * Account, CBU/CVU and transaction numbers are long digit runs without decimals.
     * Real receipt amounts either have cents or are short enough to be money.
     */
    private fun String.looksLikeIdNumber(): Boolean =
        length >= 9 && !contains(',') && !contains('.')

    private fun String.looksLikeNonAmountLine(): Boolean {
        val lower = lowercase(localeAr)
        return listOf(
            "saldo", "cbu", "cvu", "operaci", "comprobante", "nro", "n°", "cuil", "cuit",
            "medio de pago", "cuenta", "trans", "alias",
        ).any { it in lower }
    }

    private fun extractDate(lines: List<String>): LocalDate? {
        val preferred = lines.firstOrNull {
            val lower = it.lowercase(localeAr)
            "fecha" in lower || "emisión" in lower || "emision" in lower
        }
        val candidates = listOfNotNull(preferred) + lines
        candidates.forEach { line ->
            parseDateIn(line)?.let { return it }
        }
        return null
    }

    private fun parseDateIn(text: String): LocalDate? {
        isoDateRegex.find(text)?.let { match ->
            runCatching {
                LocalDate.of(
                    match.groupValues[1].toInt(),
                    match.groupValues[2].toInt(),
                    match.groupValues[3].toInt(),
                )
            }.getOrNull()?.let { return it }
        }
        dateDmyRegex.find(text)?.let { match ->
            val day = match.groupValues[1].toIntOrNull() ?: return@let
            val month = match.groupValues[2].toIntOrNull() ?: return@let
            val year = match.groupValues[3].toIntOrNull() ?: return@let
            safeDate(day, month, if (year < 100) 2000 + year else year)?.let { return it }
        }
        dateHyphenMonthRegex.find(text)?.let { match ->
            val month = monthNumber(match.groupValues[2]) ?: return@let
            safeDate(
                match.groupValues[1].toIntOrNull() ?: return@let,
                month,
                match.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: return@let,
            )?.let { return it }
        }
        dateMonthNameRegex.find(text)?.let { match ->
            val month = monthNumber(match.groupValues[2]) ?: return@let
            safeDate(
                match.groupValues[1].toIntOrNull() ?: return@let,
                month,
                match.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it } ?: return@let,
            )?.let { return it }
        }
        dateTextRegex.find(text)?.let { match ->
            val month = monthNumber(match.groupValues[2]) ?: return@let
            safeDate(
                match.groupValues[1].toIntOrNull() ?: return@let,
                month,
                match.groupValues[3].toIntOrNull() ?: return@let,
            )?.let { return it }
        }
        return null
    }

    private fun safeDate(day: Int, month: Int, year: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()

    /**
     * Reference / transaction number. Handles both "Operación 123456" and the
     * two-line shape used by bank receipts: "Nro° Trans" followed by the number.
     */
    private fun extractReference(lines: List<String>): String? {
        lines.forEachIndexed { index, line ->
            val label = referenceLabelRegex.find(line) ?: return@forEachIndexed
            val rest = line.substring(label.range.last + 1)
                .trim()
                .trimStart(':', '#', '-', ' ')
            val inline = rest.split(' ', ':')
                .lastOrNull { token ->
                    singleIdRegex.matches(token) && token.lowercase(localeAr) !in referenceNoise
                }
            if (inline != null) return inline
            for (offset in 1..2) {
                val candidate = lines.getOrNull(index + offset)?.trim().orEmpty()
                if (singleIdRegex.matches(candidate) && candidate.lowercase(localeAr) !in referenceNoise) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun monthNumber(raw: String): Int? {
        val key = raw.lowercase(localeAr)
            .replace("á", "a")
            .replace("é", "e")
            .replace("í", "i")
            .replace("ó", "o")
            .replace("ú", "u")
            .trimEnd('.')
        return monthNames[key] ?: monthNames[key.take(3)]
    }

    private fun labeledLine(lines: List<String>, label: String): String? =
        lines.firstOrNull { line ->
            val lower = line.lowercase(localeAr)
            lower.startsWith(label) || lower.contains("$label:")
        }

    private fun institutionIn(text: String): String? {
        val lower = text.lowercase(localeAr)
        return institutions.firstOrNull { known ->
            known.aliases.any { alias -> lower.contains(alias) }
        }?.canonical
    }

    /** Used by tests to keep the supported labels visible. */
    internal fun supportedInstitutions(): List<String> = institutions.map { it.canonical }

    internal fun stripPagoDePrefix(text: String): String =
        text.replace(leadingPagoDeRegex, "").trim()

    internal fun formatDate(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.forLanguageTag("es-AR")))
}
