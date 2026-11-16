package com.smartinvest.data.importer

import java.time.LocalDate

enum class ReceiptKind(val label: String) {
    EXPENSE("Gasto / pago"),
    TRANSFER("Transferencia"),
    CARD_STATEMENT("Resumen de tarjeta"),
    UNKNOWN("Sin clasificar"),
}

/**
 * Result of reading a bank / wallet receipt or a credit card statement.
 *
 * All fields are suggestions: the import screen lets the user fix anything before
 * saving. For [ReceiptKind.CARD_STATEMENT], [dueDate], [nextClosingDate] and
 * [amountCents] (Total a pagar) are extracted so the app can schedule the due-date
 * and next-closing alerts and register the statement amount.
 */
data class ParsedReceipt(
    val kind: ReceiptKind,
    val description: String,
    val amountCents: Long?,
    val date: LocalDate?,
    val originInstitution: String?,
    val destinationInstitution: String?,
    val serviceName: String?,
    val reference: String?,
    val likelyOwnTransfer: Boolean,
    val warnings: List<String>,
    val rawText: String,
    val cardName: String? = null,
    val dueDate: LocalDate? = null,
    val nextClosingDate: LocalDate? = null,
    val currentClosingDate: LocalDate? = null,
    val nextDueDate: LocalDate? = null,
) {
    val canSave: Boolean get() = amountCents != null && amountCents > 0 && date != null && description.isNotBlank()

    val institutionLabel: String?
        get() = originInstitution ?: destinationInstitution
}
