package com.smartinvest.domain.engine

import com.smartinvest.data.importer.ReceiptParser
import com.smartinvest.domain.model.CreditCard
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
    /** Months between occurrences for manual agenda items (1 = monthly). */
    val intervalMonths: Int = 1,
    /** First month of the cadence ("YYYY-MM"). Null falls back to the previous payment. */
    val startMonth: String? = null,
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
    /** Expense flagged as recurring; the payment calendar only shows these. */
    val isRecurring: Boolean = false,
    /** Months between occurrences of a recurring expense (1 = monthly). */
    val recurrenceMonths: Int = 1,
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
    /** Cadence used to project this row (1 = monthly). */
    val intervalMonths: Int = 1,
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
     *
     * When [recurringOnly] is true (payment calendar), only expenses flagged as recurring
     * plus the configured [templates] are projected; ordinary service-category expenses
     * stay in the agenda screen but do not clutter the calendar.
     */
    fun buildForMonth(
        month: YearMonth,
        today: LocalDate,
        expenses: List<HistoricalExpenseRecord>,
        serviceCategoryIds: Set<Long>,
        templates: List<PaymentAgendaTemplate>,
        recurringOnly: Boolean = false,
        creditCards: List<CreditCard> = emptyList(),
    ): PaymentAgendaMonthSummary {
        val effectiveTemplates = mergeCreditCardTemplates(templates, creditCards)
        val templateMap = effectiveTemplates.associateBy { it.key }
        val templateKeys = templateMap.keys

        val relevantExpenses = expenses.filter { exp ->
            val matchesTemplate = normalizeKey(exp.description) in templateKeys
            if (recurringOnly) {
                exp.isRecurring || matchesTemplate
            } else {
                exp.categoryId in serviceCategoryIds || matchesTemplate
            }
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

            // Cadence: a bimonthly expense anchored in January only shows in
            // January, March, May… The first recurring payment is the anchor, and
            // months with an actual payment or an explicit override always show.
            val intervalMonths = (template?.intervalMonths
                ?: history.firstOrNull { it.isRecurring }?.recurrenceMonths
                ?: 1).coerceAtLeast(1)
            val anchorMonth = template?.startMonth
                ?.let { runCatching { YearMonth.parse(it) }.getOrNull() }
                ?: history.firstOrNull { it.isRecurring }?.let { YearMonth.from(it.date) }
                ?: referencePayment?.let { YearMonth.from(it.date) }
                ?: month
            val explicitMonth = template?.monthDateOverrides?.containsKey(monthStr) == true
            val manualPaid = template?.manualPaidMonths?.contains(monthStr) == true
            val cadenceApplies = intervalMonths <= 1 ||
                explicitMonth ||
                manualPaid ||
                paidInMonth != null ||
                occursInMonth(month, anchorMonth, intervalMonths)
            if (!cadenceApplies) continue

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
                intervalMonths = intervalMonths,
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

    /** True when [month] falls on the cadence anchored at [anchorMonth] (step [intervalMonths]). */
    private fun occursInMonth(
        month: YearMonth,
        anchorMonth: YearMonth,
        intervalMonths: Int,
    ): Boolean {
        if (intervalMonths <= 1) return true
        val diff = ChronoUnit.MONTHS.between(anchorMonth, month)
        return diff >= 0L && diff % intervalMonths == 0L
    }

    /**
     * Synthesizes `Resumen <Tarjeta>` (due date) and `Cierre <Tarjeta>` (closing date)
     * templates for every active configured [CreditCard], merging with any user-saved
     * or receipt-imported template so date overrides, amounts and paid ticks are preserved.
     */
    fun mergeCreditCardTemplates(
        templates: List<PaymentAgendaTemplate>,
        creditCards: List<CreditCard>,
    ): List<PaymentAgendaTemplate> {
        val activeCards = creditCards.filter {
            it.isActive &&
                it.name.isNotBlank() &&
                !it.name.contains("SplitHome", ignoreCase = true)
        }
        if (activeCards.isEmpty()) return templates

        val byKey = templates.associateBy { it.key }.toMutableMap()

        for (card in activeCards) {
            val cleanCardName = card.name.trim()
            val cardKeyToken = normalizeKey(cleanCardName)
            if (cardKeyToken.isBlank()) continue

            val dueTitle = "Resumen $cleanCardName"
            val dueKey = normalizeKey(dueTitle)
            val matchingDueKey = byKey.keys.firstOrNull { k ->
                k == dueKey || (k.startsWith("resumen ") && (k.contains(cardKeyToken) || cardKeyToken.contains(k.removePrefix("resumen ").trim())))
            } ?: dueKey
            val existingDue = byKey[matchingDueKey]
            val dueNotesDefault = buildString {
                append("Vencimiento tarjeta ${cleanCardName}")
                if (card.issuer.isNotBlank()) append(" (${card.issuer})")
                append(" · Cierra día ${card.closingDay} · Vence día ${card.dueDay}")
            }
            byKey[matchingDueKey] = if (existingDue != null) {
                existingDue.copy(
                    customDayOfMonth = existingDue.customDayOfMonth ?: card.dueDay.coerceIn(1, 31),
                    notes = existingDue.notes.ifBlank { dueNotesDefault },
                )
            } else {
                PaymentAgendaTemplate(
                    key = dueKey,
                    name = dueTitle,
                    customDayOfMonth = card.dueDay.coerceIn(1, 31),
                    estimatedAmountCents = 0L,
                    alertEnabled = true,
                    alertDaysBefore = 5,
                    notes = dueNotesDefault,
                )
            }

            val closeTitle = "Cierre $cleanCardName"
            val closeKey = normalizeKey(closeTitle)
            val matchingCloseKey = byKey.keys.firstOrNull { k ->
                k == closeKey || (k.startsWith("cierre ") && (k.contains(cardKeyToken) || cardKeyToken.contains(k.removePrefix("cierre ").trim())))
            } ?: closeKey
            val existingClose = byKey[matchingCloseKey]
            val closeNotesDefault = buildString {
                append("Cierre de resumen de ${cleanCardName}")
                if (card.issuer.isNotBlank()) append(" (${card.issuer})")
                append(" · Vence día ${card.dueDay}")
            }
            byKey[matchingCloseKey] = if (existingClose != null) {
                existingClose.copy(
                    customDayOfMonth = existingClose.customDayOfMonth ?: card.closingDay.coerceIn(1, 31),
                    notes = existingClose.notes.ifBlank { closeNotesDefault },
                )
            } else {
                PaymentAgendaTemplate(
                    key = closeKey,
                    name = closeTitle,
                    customDayOfMonth = card.closingDay.coerceIn(1, 31),
                    estimatedAmountCents = 0L,
                    alertEnabled = true,
                    alertDaysBefore = 3,
                    notes = closeNotesDefault,
                )
            }
        }

        return byKey.values.toList()
    }
}
