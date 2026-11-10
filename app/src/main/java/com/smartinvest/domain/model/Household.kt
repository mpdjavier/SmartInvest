package com.smartinvest.domain.model

import java.time.LocalDate
import java.time.YearMonth

data class HouseholdMember(
    val id: Long,
    val name: String,
    val isCurrentUser: Boolean,
    val colorHex: String,
    val isActive: Boolean,
)

data class HouseholdCategory(
    val id: Long,
    val name: String,
    val emoji: String,
    val colorHex: String,
    val isArchived: Boolean,
)

data class CreditCard(
    val id: Long,
    val name: String,
    val issuer: String,
    val brand: String,
    val closingDay: Int,
    val dueDay: Int,
    val colorHex: String,
    val isActive: Boolean,
) {
    val cycleLabel: String get() = "Cierre $closingDay · Vence $dueDay"
}

enum class ExpensePaymentMethod(val label: String, val shortLabel: String) {
    CASH("Efectivo", "Efectivo"),
    DEBIT("Débito", "Débito"),
    TRANSFER("Transferencia", "Transfer."),
    CREDIT("Tarjeta de crédito", "Crédito"),
    COMMON_FUND("Fondo común", "Fondo");

    companion object {
        fun fromDb(value: String): ExpensePaymentMethod =
            entries.firstOrNull { it.name == value } ?: CASH
    }
}

data class HouseholdExpense(
    val id: Long,
    val description: String,
    val totalCents: Long,
    val date: LocalDate,
    val month: YearMonth,
    val categoryId: Long,
    val paidByMemberId: Long,
    val paymentMethod: ExpensePaymentMethod,
    val creditCardId: Long?,
    val installments: Int,
    val notes: String,
    val createdAt: Long,
)

data class Installment(
    val id: Long,
    val expenseId: Long,
    val number: Int,
    val count: Int,
    val dueMonth: YearMonth,
    val amountCents: Long,
    val isPaid: Boolean,
)

/**
 * A row as it impacts the selected month. For credit purchases [monthlyCents] is
 * the installment amount, never the full purchase. For cash/debit/fund it is the total.
 */
data class MonthlyExpenseItem(
    val expenseId: Long,
    val installmentId: Long?,
    val description: String,
    val totalCents: Long,
    val monthlyCents: Long,
    val date: LocalDate,
    val categoryId: Long,
    val paidByMemberId: Long,
    val paymentMethod: ExpensePaymentMethod,
    val paidFromFund: Boolean,
    val installmentNumber: Int?,
    val installmentCount: Int?,
    val installmentPaid: Boolean?,
    val creditCardId: Long?,
) {
    val isInstallment: Boolean get() = installmentNumber != null
    val installmentLabel: String?
        get() = if (installmentNumber != null && installmentCount != null) {
            "Cuota $installmentNumber/$installmentCount"
        } else {
            null
        }
}

enum class FundMovementType(val label: String) {
    CONTRIBUTION("Aporte"),
    WITHDRAWAL("Extracción"),
    EXPENSE("Compra con fondo"),
}

data class FundMovement(
    val id: Long,
    val type: FundMovementType,
    val amountCents: Long,
    val date: LocalDate,
    val month: YearMonth,
    val memberId: Long?,
    val description: String,
    val expenseId: Long?,
)

enum class SettlementRole { RECEIVES, OWES, SETTLED }

data class MemberSettlement(
    val memberId: Long,
    val name: String,
    val colorHex: String,
    val directPaidCents: Long,
    val fundContributionsCents: Long,
    val fundWithdrawalsCents: Long,
    val netContributionCents: Long,
    val targetCents: Long,
    val differenceCents: Long,
) {
    val role: SettlementRole
        get() = when {
            differenceCents > 0 -> SettlementRole.RECEIVES
            differenceCents < 0 -> SettlementRole.OWES
            else -> SettlementRole.SETTLED
        }
}

data class SettlementTransfer(
    val fromMemberId: Long,
    val fromName: String,
    val toMemberId: Long,
    val toName: String,
    val amountCents: Long,
)

/**
 * A payment manually registered by the user to close a month.
 *
 * Settlement is deliberately manual: the month may be closed on any day, not
 * only the first day of the next month. A single record can also be a "close
 * without transfer" marker (amount 0, no members) for balanced months.
 */
data class RegisteredSettlement(
    val id: Long,
    val month: YearMonth,
    val fromMemberId: Long,
    val fromName: String,
    val toMemberId: Long,
    val toName: String,
    val amountCents: Long,
    val date: LocalDate,
    val note: String,
    val createdAt: Long,
) {
    val isClosingMarker: Boolean get() = amountCents == 0L && fromMemberId == 0L

    val label: String
        get() = if (isClosingMarker) {
            "Mes cerrado sin transferencia"
        } else {
            "$fromName → $toName"
        }
}

data class HouseholdSummary(
    val month: YearMonth,
    val directSpendCents: Long,
    val fundSpendCents: Long,
    val totalSpendCents: Long,
    val installmentSpendCents: Long,
    val installmentCount: Int,
    val pendingInstallmentsCents: Long,
    val fundContributionsCents: Long,
    val fundWithdrawalsCents: Long,
    val fundBalanceCents: Long,
    val totalNetContributionsCents: Long,
    val fairShareCents: Long,
    val members: List<MemberSettlement>,
    val transfers: List<SettlementTransfer>,
    val settlements: List<RegisteredSettlement> = emptyList(),
) {
    val hasActivity: Boolean
        get() = totalSpendCents > 0 || fundContributionsCents > 0 || fundWithdrawalsCents > 0

    val isBalanced: Boolean get() = transfers.isEmpty()

    /** True when the user already registered the manual close of this month. */
    val isMonthClosed: Boolean get() = settlements.isNotEmpty()

    val registeredSettlementCents: Long get() = settlements.sumOf { it.amountCents }

    val settlementDate: LocalDate? get() = settlements.maxOfOrNull { it.date }

    val lowestContributor: MemberSettlement? =
        members.minByOrNull { it.netContributionCents }

    val highestContributor: MemberSettlement? =
        members.maxByOrNull { it.netContributionCents }

    val mainTransfer: SettlementTransfer? get() = transfers.firstOrNull()
}
