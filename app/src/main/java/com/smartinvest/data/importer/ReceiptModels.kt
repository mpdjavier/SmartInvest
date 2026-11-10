package com.smartinvest.data.importer

import java.time.LocalDate

enum class ReceiptKind(val label: String) {
    EXPENSE("Gasto / pago"),
    TRANSFER("Transferencia"),
    UNKNOWN("Sin clasificar"),
}

/**
 * Result of reading a bank / wallet receipt.
 *
 * All fields are suggestions: the import screen lets the user fix anything before
 * creating the household expense. [likelyOwnTransfer] only warns when both ends look
 * like the same person's accounts; the user confirms it explicitly.
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
) {
    val canSave: Boolean get() = amountCents != null && amountCents > 0 && date != null && description.isNotBlank()

    val institutionLabel: String?
        get() = originInstitution ?: destinationInstitution
}
