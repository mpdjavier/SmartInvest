package com.smartinvest.data.local.model

data class InstallmentRow(
    val installmentId: Long,
    val expenseId: Long,
    val number: Int,
    val count: Int,
    val dueMonth: String,
    val amountCents: Long,
    val isPaid: Boolean,
    val description: String,
    val totalAmountCents: Long,
    val categoryId: Long,
    val paidByMemberId: Long,
    val creditCardId: Long?,
    val dateEpochDay: Long,
)

/**
 * One expense occurrence for search and reports: cash-like expenses appear once,
 * credit purchases once per installment (with the due month as impact month).
 */
data class ExpenseRecordRow(
    val expenseId: Long,
    val installmentId: Long?,
    val description: String,
    val notes: String,
    val dateEpochDay: Long,
    val purchaseMonth: String,
    val impactMonth: String?,
    val totalAmountCents: Long,
    val impactCents: Long?,
    val categoryId: Long,
    val paidByMemberId: Long,
    val paymentMethod: String,
    val creditCardId: Long?,
    val installmentNumber: Int?,
    val installmentCount: Int?,
    val installmentPaid: Boolean?,
)
