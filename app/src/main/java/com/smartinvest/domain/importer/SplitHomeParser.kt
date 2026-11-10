package com.smartinvest.domain.importer

import com.smartinvest.core.money.MoneyParser
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

enum class SplitHomePayer {
    /** "J": the current app user ("Vos"). */
    PRIMARY,

    /** "M": the other household member ("Mamá"). */
    SECONDARY,

    /** "Fondo": paid by the common fund. */
    FUND,
}

data class SplitHomeExpense(
    val date: LocalDate,
    val description: String,
    val amountCents: Long,
    val payer: SplitHomePayer,
    val categoryName: String,
    /** Number of installments of the purchase; 1 for a regular expense. */
    val installments: Int = 1,
    /** True when the source row was "Cuota 1/N" and [amountCents] is one installment. */
    val isInstallmentPurchase: Boolean = false,
) {
    val totalCents: Long get() = if (isInstallmentPurchase) amountCents * installments else amountCents
}

data class SplitHomeParseResult(
    val expenses: List<SplitHomeExpense> = emptyList(),
    val fundInitialCents: Long = 0,
    val warnings: List<String> = emptyList(),
    val errors: List<String> = emptyList(),
    val skippedRows: Int = 0,
) {
    val isValid: Boolean get() = errors.isEmpty() && expenses.isNotEmpty()

    val totalCents: Long get() = expenses.sumOf { it.amountCents }
    val fundPurchaseCents: Long get() = expenses.filter { it.payer == SplitHomePayer.FUND }.sumOf { it.amountCents }
    val installmentCount: Int get() = expenses.count { it.isInstallmentPurchase }
    val generatedInstallments: Int get() = expenses.sumOf { if (it.isInstallmentPurchase) it.installments else 0 }
    val categories: List<String> get() = expenses.map { it.categoryName }.distinct()
}

/**
 * Parses the two sheets exported by SplitHome ("Gastos" and "Fondo Comun").
 *
 * Installments: the report lists one row per purchase with the text
 * "(Cuota 1/6)" and the amount of a single installment. The parser stores the
 * installment amount and count; the importer builds the full schedule.
 */
object SplitHomeParser {

    private val installmentRegex =
        Regex("""\(\s*cuota\s+(\d+)\s*/\s*(\d+)\s*\)""", RegexOption.IGNORE_CASE)

    private val dateFormats = listOf(
        DateTimeFormatter.ofPattern("d/M/yyyy"),
        DateTimeFormatter.ofPattern("d-M-yyyy"),
        DateTimeFormatter.ofPattern("yyyy-M-d"),
    )

    private const val EXCEL_EPOCH_OFFSET_DAYS = 25569L // 1899-12-30 -> 1970-01-01

    fun parse(sheets: Map<String, List<List<String>>>): SplitHomeParseResult {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()

        val expensesSheet = sheets.entries.firstOrNull { normalize(it.key).contains("gasto") }?.value
        if (expensesSheet == null) {
            return SplitHomeParseResult(errors = listOf("No encontré la hoja \"Gastos\" en el archivo."))
        }

        val headerIndex = expensesSheet.indexOfFirst { row -> row.any { normalize(it) == "fecha" } }
        if (headerIndex < 0) {
            return SplitHomeParseResult(errors = listOf("La hoja \"Gastos\" no tiene encabezados reconocibles."))
        }

        val header = expensesSheet[headerIndex].map(::normalize)
        val dateColumn = header.indexOfFirst { it.startsWith("fecha") }
        val descriptionColumn = header.indexOfFirst { it.startsWith("descrip") || it == "detalle" }
        val amountColumn = header.indexOfFirst { it == "monto" || it == "importe" }
        val payerColumn = header.indexOfFirst { it.startsWith("pagado") || it == "pago" }
        val categoryColumn = header.indexOfFirst { it.startsWith("categoria") || it == "rubro" }

        val missingColumns = buildList {
            if (dateColumn < 0) add("Fecha")
            if (descriptionColumn < 0) add("Descripcion")
            if (amountColumn < 0) add("Monto")
            if (payerColumn < 0) add("Pagado por")
        }
        if (missingColumns.isNotEmpty()) {
            return SplitHomeParseResult(
                errors = listOf("Faltan columnas en \"Gastos\": ${missingColumns.joinToString(", ")}."),
            )
        }

        val expenses = mutableListOf<SplitHomeExpense>()
        var skipped = 0
        expensesSheet.drop(headerIndex + 1).forEachIndexed { index, row ->
            val line = headerIndex + index + 2
            if (row.all { it.isBlank() }) return@forEachIndexed

            val date = parseDate(row.getOrNull(dateColumn))
            val amount = parseAmount(row.getOrNull(amountColumn))
            val payer = parsePayer(row.getOrNull(payerColumn))
            val rawDescription = row.getOrNull(descriptionColumn).orEmpty().trim()

            if (date == null || amount == null || payer == null || rawDescription.isBlank()) {
                skipped++
                warnings += "Fila $line ignorada: ${describeProblem(date, amount, payer, rawDescription)}."
                return@forEachIndexed
            }

            val category = row.getOrNull(categoryColumn)?.trim().orEmpty().ifBlank { "Otros" }
            val match = installmentRegex.find(rawDescription)
            val installmentNumber = match?.groupValues?.getOrNull(1)?.toIntOrNull()
            val installmentTotal = match?.groupValues?.getOrNull(2)?.toIntOrNull()
            val isFirstInstallment = installmentNumber == 1 &&
                installmentTotal != null &&
                installmentTotal in 2..60

            if (match != null && !isFirstInstallment) {
                warnings += "Fila $line: \"$rawDescription\" tiene una cuota que no es la 1/N; " +
                    "se importa como gasto único."
            }

            expenses += SplitHomeExpense(
                date = date,
                description = if (isFirstInstallment) {
                    rawDescription.replace(match!!.value, "").trim().ifBlank { rawDescription }
                } else {
                    rawDescription
                },
                amountCents = amount,
                payer = payer,
                categoryName = category,
                installments = if (isFirstInstallment) installmentTotal!! else 1,
                isInstallmentPurchase = isFirstInstallment,
            )
        }

        if (expenses.isEmpty()) {
            errors += "No hay gastos válidos para importar."
        }

        val fundSheet = sheets.entries.firstOrNull { normalize(it.key).contains("fondo") }?.value
        val fundInitial = fundSheet?.let { rows ->
            rows.firstOrNull { row -> normalize(row.firstOrNull().orEmpty()).startsWith("fondo inicial") }
                ?.let { row -> row.drop(1).firstNotNullOfOrNull(::parseAmount) }
        } ?: 0L
        if (fundSheet == null) {
            warnings += "No encontré la hoja \"Fondo Comun\": se importa el fondo con saldo inicial 0."
        } else if (fundInitial <= 0) {
            warnings += "No pude leer el saldo inicial del fondo; se importa en 0."
        }

        return SplitHomeParseResult(
            expenses = expenses,
            fundInitialCents = fundInitial,
            warnings = warnings,
            errors = errors,
            skippedRows = skipped,
        )
    }

    /** Lowercase, no accents, trimmed: used for headers, payers and categories. */
    fun normalize(value: String): String = value.trim().lowercase()
        .replace("á", "a")
        .replace("é", "e")
        .replace("í", "i")
        .replace("ó", "o")
        .replace("ú", "u")
        .replace("ü", "u")
        .replace("ñ", "n")

    fun parsePayer(raw: String?): SplitHomePayer? = when (normalize(raw.orEmpty())) {
        "j", "vos", "yo", "usuario", "javier" -> SplitHomePayer.PRIMARY
        "m", "mama", "madre" -> SplitHomePayer.SECONDARY
        "fondo", "fondo comun", "comun" -> SplitHomePayer.FUND
        else -> null
    }

    private fun parseDate(raw: String?): LocalDate? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        dateFormats.forEach { formatter ->
            runCatching { LocalDate.parse(value, formatter) }.getOrNull()?.let { return it }
        }
        // Excel serial date (e.g. 46342) when the cell is numeric.
        value.toDoubleOrNull()?.let { serial ->
            if (serial in 1.0..80_000.0) {
                return LocalDate.ofEpochDay(serial.toLong() - EXCEL_EPOCH_OFFSET_DAYS)
            }
        }
        return null
    }

    private fun parseAmount(raw: String?): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        if (value.matches(Regex("""\d+(\.\d+)?[eE][+-]?\d+"""))) {
            return runCatching {
                BigDecimal(value)
                    .setScale(2, java.math.RoundingMode.HALF_UP)
                    .movePointRight(2)
                    .longValueExact()
            }.getOrNull()?.takeIf { it > 0 }
        }
        return MoneyParser.parseToCents(value)?.takeIf { it > 0 }
    }

    private fun describeProblem(
        date: LocalDate?,
        amount: Long?,
        payer: SplitHomePayer?,
        description: String,
    ): String = buildList {
        if (date == null) add("fecha inválida")
        if (amount == null) add("monto inválido")
        if (payer == null) add("pagador desconocido")
        if (description.isBlank()) add("descripción vacía")
    }.joinToString(", ")
}
