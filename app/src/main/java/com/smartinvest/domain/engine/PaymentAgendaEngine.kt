package com.smartinvest.domain.engine

import com.smartinvest.data.importer.ReceiptParser
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.min

@Serializable
data class PaymentAgendaTemplate(
    val key: String,
    val name: String,
    /** User-configured day of month (1..31). Null means "use the day of the previous payment". */
    val customDayOfMonth: Int? = null,
    val estimatedAmountCents: Long = 0L,
    val alertEnabled: Boolean = true,
    val alertDaysBefore: Int = 3,
    val notes: String = "",
    val isDeleted: Boolean = false,
    /** Per-month custom date overrides ("YYYY-MM" -> "YYYY-MM-DD"). */
    val monthDateOverrides: Map<String, String> = emptyMap(),
    /** Months manually marked as paid without a linked expense row ("YYYY-MM"). */
    val manualPaidMonths: List<String> = emptyList(),
)

data class HistoricalExpenseRecord(
    val id: Long,
    val description: String,
    val amountCents: Long,
    val date: LocalDate,
    val categoryId: Long,
)

enum class PaymentAgendaStatus(val label: String) {
    OVERDUE("Vencido"),
    DUE_SOON("Próximo a vencer"),
    UPCOMING("Agendado"),
    PAID("Pagado"),
}

data class PaymentAgendaRow(
    val key: String,
    val name: String,
    val scheduledDate: LocalDate,
    val previousPaymentDate: LocalDate?,
    val previousPaymentAmountCents: Long?,
    val amountCents: Long,
    val isPaid: Boolean,
    val paidDate: LocalDate?,
    val paidExpenseId: Long?,
    val alertEnabled: Boolean,
    val alertDaysBefore: Int,
    val notes: String,
    val status: PaymentAgendaStatus,
    val daysUntilDue: Long,
)

data class PaymentAgendaMonthSummary(
    val month: YearMonth,
    val rows: List<PaymentAgendaRow>,
    val totalScheduledCents: Long,
    val paidCount: Int,
    val pendingCount: Int,
    val dueOrOverdueCount: Int,
)

object PaymentAgendaEngine {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encodeTemplates(templates: List<PaymentAgendaTemplate>): String =
        json.encodeToString(templates)

    fun decodeTemplates(raw: String): List<PaymentAgendaTemplate> {
        val clean = raw.trim()
        if (clean.isEmpty()) return emptyList()
        return runCatching { json.decodeFromString<List<PaymentAgendaTemplate>>(clean) }
            .getOrDefault(emptyList())
    }

    fun normalizeKey(rawName: String): String {
        val stripped = ReceiptParser.stripPagoDePrefix(rawName).ifBlank { rawName.trim() }
        val normalized = Normalizer.normalize(stripped.lowercase(Locale.forLanguageTag("es-AR")), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        return normalized.ifBlank { stripped.lowercase(Locale.ROOT).trim() }
    }

    fun cleanDisplayName(rawName: String): String =
        ReceiptParser.stripPagoDePrefix(rawName).ifBlank { rawName.trim() }

    /**
     * Builds the payment agenda for [month]:
     * - Automatically includes all payments made in service/recurring categories (`serviceCategoryIds`),
     *   defaulting the scheduled date in [month] to the day-of-month of the previous payment.
     * - Merges user edits and manually added items from [templates].
     */
    fun buildForMonth(
        month: YearMonth,
        today: LocalDate,
        expenses: List<HistoricalExpenseRecord>,
        serviceCategoryIds: Set<Long>,
        templates: List<PaymentAgendaTemplate>,
    ): PaymentAgendaMonthSummary {
        val templateMap = templates.associateBy { it.key }
        val templateKeys = templateMap.keys

        val relevantExpenses = expenses.filter { exp ->
            exp.categoryId in serviceCategoryIds || normalizeKey(exp.description) in templateKeys
        }

        val expensesByKey = relevantExpenses.groupBy { normalizeKey(it.description) }
        val allKeys = (expensesByKey.keys + templateKeys).filter { it.isNotBlank() }

        val rows = mutableListOf<PaymentAgendaRow>()
        val monthStr = month.toString()

        for (key in allKeys) {
            val template = templateMap[key]
            if (template?.isDeleted == true) continue

            val history = expensesByKey[key].orEmpty().sortedBy { it.date }
            val paidInMonth = history.filter { YearMonth.from(it.date) == month }.maxByOrNull { it.date }
            val priorPayments = history.filter { YearMonth.from(it.date).isBefore(month) }
            val referencePayment = priorPayments.maxByOrNull { it.date }
                ?: paidInMonth
                ?: history.maxByOrNull { it.date }

            // If an item was only paid once in a future month after [month] and has no template, skip it for past months.
            if (template == null && priorPayments.isEmpty() && paidInMonth == null) continue

            val displayName = template?.name?.takeIf { it.isNotBlank() }
                ?: referencePayment?.description?.let(::cleanDisplayName)
                ?: key

            val defaultDay = referencePayment?.date?.dayOfMonth ?: 10
            val scheduledDate = template?.monthDateOverrides?.get(monthStr)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: run {
                    val day = (template?.customDayOfMonth ?: defaultDay).coerceIn(1, 31)
                    month.atDay(min(day, month.lengthOfMonth()))
                }

            val isPaid = paidInMonth != null || (template?.manualPaidMonths?.contains(monthStr) == true)
            val paidDate = paidInMonth?.date ?: if (isPaid) scheduledDate else null
            val amountCents = when {
                paidInMonth != null -> paidInMonth.amountCents
                (template?.estimatedAmountCents ?: 0L) > 0L -> template!!.estimatedAmountCents
                referencePayment != null -> referencePayment.amountCents
                else -> 0L
            }

            val alertEnabled = template?.alertEnabled ?: true
            val alertDaysBefore = (template?.alertDaysBefore ?: 3).coerceIn(0, 30)
            val daysUntilDue = ChronoUnit.DAYS.between(today, scheduledDate)

            val status = when {
                isPaid -> PaymentAgendaStatus.PAID
                daysUntilDue < 0L -> PaymentAgendaStatus.OVERDUE
                daysUntilDue <= alertDaysBefore.toLong() -> PaymentAgendaStatus.DUE_SOON
                else -> PaymentAgendaStatus.UPCOMING
            }

            rows += PaymentAgendaRow(
                key = key,
                name = displayName,
                scheduledDate = scheduledDate,
                previousPaymentDate = priorPayments.maxByOrNull { it.date }?.date,
                previousPaymentAmountCents = priorPayments.maxByOrNull { it.date }?.amountCents,
                amountCents = amountCents,
                isPaid = isPaid,
                paidDate = paidDate,
                paidExpenseId = paidInMonth?.id,
                alertEnabled = alertEnabled,
                alertDaysBefore = alertDaysBefore,
                notes = template?.notes.orEmpty(),
                status = status,
                daysUntilDue = daysUntilDue,
            )
        }

        val sortedRows = rows.sortedWith(
            compareBy<PaymentAgendaRow> { it.isPaid }
                .thenBy { it.scheduledDate }
                .thenBy { it.name.lowercase(Locale.ROOT) },
        )

        return PaymentAgendaMonthSummary(
            month = month,
            rows = sortedRows,
            totalScheduledCents = sortedRows.sumOf { it.amountCents },
            paidCount = sortedRows.count { it.isPaid },
            pendingCount = sortedRows.count { !it.isPaid },
            dueOrOverdueCount = sortedRows.count {
                it.status == PaymentAgendaStatus.OVERDUE || it.status == PaymentAgendaStatus.DUE_SOON
            },
        )
    }
}
