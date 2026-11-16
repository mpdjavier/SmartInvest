package com.smartinvest.domain.engine

import com.smartinvest.core.money.MoneyFormatter
import com.smartinvest.core.time.DateUtils
import com.smartinvest.domain.model.CreditCard
import com.smartinvest.domain.model.ExpenseRecord
import com.smartinvest.domain.model.ExpenseReport
import com.smartinvest.domain.model.HouseholdCategory
import com.smartinvest.domain.model.HouseholdMember
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the exportable versions of an expense report.
 *
 * The CSV uses `;` as separator and comma decimals so it opens directly in Excel /
 * Google Sheets with the Argentine locale. The summary is a plain-text block ready
 * to share over WhatsApp or e-mail.
 */
object ExpenseReportExporter {

    private val csvDate = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val locale = Locale.forLanguageTag("es-AR")

    fun csv(
        records: List<ExpenseRecord>,
        categories: Map<Long, HouseholdCategory>,
        members: Map<Long, HouseholdMember>,
        cards: Map<Long, CreditCard>,
    ): String {
        val header = listOf(
            "Fecha",
            "Mes imputado",
            "Descripción",
            "Categoría",
            "Integrante",
            "Medio de pago",
            "Tarjeta",
            "Cuota",
            "Total compra",
            "Monto del mes",
            "Notas",
        )
        val body = records
            .sortedWith(compareBy<ExpenseRecord> { it.date }.thenBy { it.expenseId })
            .map { record ->
                listOf(
                    record.date.format(csvDate),
                    record.month.toString(),
                    record.description,
                    categories[record.categoryId]?.let { "${it.emoji} ${it.name}" } ?: "Sin categoría",
                    members[record.paidByMemberId]?.name ?: "Integrante eliminado",
                    record.paymentMethod.label,
                    record.creditCardId?.let { cards[it]?.name }.orEmpty(),
                    record.installmentLabel.orEmpty(),
                    amount(record.totalCents),
                    amount(record.impactCents),
                    record.notes.replace("\n", " ").replace("\r", " "),
                )
            }
        val totalRow = listOf(
            "",
            "",
            "TOTAL",
            "",
            "",
            "",
            "",
            "",
            "",
            amount(records.sumOf { it.impactCents }),
            "",
        )
        return buildString {
            append('\uFEFF')
            appendLine(header.joinToString(";") { escape(it) })
            body.forEach { row -> appendLine(row.joinToString(";") { escape(it) }) }
            appendLine(totalRow.joinToString(";") { escape(it) })
        }
    }

    fun summary(
        report: ExpenseReport,
        periodLabel: String,
    ): String = buildString {
        appendLine("SMARTINVEST · REPORTE DE GASTOS")
        appendLine("Período: $periodLabel")
        appendLine()
        appendLine("Total: ${MoneyFormatter.ars(report.totalCents)}")
        appendLine("Movimientos: ${report.count} (${report.monthsConsidered} meses)")
        appendLine("Promedio por mes: ${MoneyFormatter.ars(report.averagePerMonthCents)}")
        appendLine("Ticket promedio: ${MoneyFormatter.ars(report.averageTicketCents)}")
        report.largest?.let {
            appendLine("Mayor gasto: ${it.description} · ${MoneyFormatter.ars(it.impactCents)}")
        }
        appendLine()
        if (report.byCategory.isNotEmpty()) {
            appendLine("POR CATEGORÍA")
            report.byCategory.forEach { bucket ->
                appendLine(
                    "· ${bucket.label}: ${MoneyFormatter.ars(bucket.totalCents)} " +
                        "(${percent(bucket.shareOf(report.totalCents))})",
                )
            }
            appendLine()
        }
        if (report.byPaymentMethod.isNotEmpty()) {
            appendLine("POR MEDIO DE PAGO")
            report.byPaymentMethod.forEach { bucket ->
                appendLine(
                    "· ${bucket.label}: ${MoneyFormatter.ars(bucket.totalCents)} " +
                        "(${percent(bucket.shareOf(report.totalCents))})",
                )
            }
            appendLine()
        }
        if (report.byMember.isNotEmpty()) {
            appendLine("POR INTEGRANTE")
            report.byMember.forEach { bucket ->
                appendLine("· ${bucket.label}: ${MoneyFormatter.ars(bucket.totalCents)}")
            }
            appendLine()
        }
        if (report.monthly.size > 1) {
            appendLine("EVOLUCIÓN MENSUAL")
            report.monthly.sortedBy { it.month }.forEach { bucket ->
                appendLine("· ${DateUtils.monthLabel(bucket.month)}: ${MoneyFormatter.ars(bucket.totalCents)}")
            }
        }
        append("Generado con SmartInvest")
    }

    private fun amount(cents: Long): String =
        BigDecimal.valueOf(cents, 2).toPlainString().replace('.', ',')

    private fun percent(value: Double): String =
        String.format(locale, "%.1f%%", value)

    /** Quotes fields containing the separator, quotes or line breaks. */
    private fun escape(value: String): String {
        val clean = value.trim()
        return if (clean.contains(';') || clean.contains('"') || clean.contains('\n') || clean.contains('\r')) {
            '"' + clean.replace("\"", "\"\"") + '"'
        } else {
            clean
        }
    }
}
