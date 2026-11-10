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
