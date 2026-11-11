package com.smartinvest.domain.engine

import com.smartinvest.core.money.MoneyMath
import com.smartinvest.domain.model.ExpensePaymentMethod
import com.smartinvest.domain.model.FundMovement
import com.smartinvest.domain.model.FundMovementType
import com.smartinvest.domain.model.HouseholdMember
import com.smartinvest.domain.model.HouseholdSummary
import com.smartinvest.domain.model.MemberSettlement
import com.smartinvest.domain.model.MonthlyExpenseItem
import com.smartinvest.domain.model.RegisteredSettlement
import com.smartinvest.domain.model.SettlementTransfer
import java.time.YearMonth
import kotlin.math.min

/**
 * End-of-month settlement for shared household expenses.
 *
 * Policy (documented so the numbers are auditable):
 * 1. Direct expenses are imputed at the amount that impacts the selected month.
 *    For credit purchases that is only the installment of the month.
 * 2. Expenses paid with the common fund are NOT attributed to an individual: the
 *    shared pool covers them. They are shown separately.
 * 3. Fund contributions made this month count as a personal contribution;
 *    withdrawals reduce the withdrawing member's contribution (personal use).
 * 4. The fund's pre-existing balance is a shared reserve and does not affect the
 *    month's equalization until someone contributes or withdraws this month.
 * 5. The target ("a partes iguales") is total net contributions / participants.
 *    Rounding cents are absorbed by the largest contributors so transfers are exact.
 */
object SettlementCalculator {

    fun calculate(
        month: YearMonth,
        members: List<HouseholdMember>,
        expenses: List<MonthlyExpenseItem>,
        fundMovements: List<FundMovement>,
        fundBalanceCents: Long,
        settlements: List<RegisteredSettlement> = emptyList(),
    ): HouseholdSummary {
        val fundCovered = expenses.filter { it.paymentMethod == ExpensePaymentMethod.COMMON_FUND }
        val directExpenses = expenses.filter { it.paymentMethod != ExpensePaymentMethod.COMMON_FUND }
        val installments = expenses.filter { it.isInstallment }

        val directByMember = directExpenses.groupBy { it.paidByMemberId }
            .mapValues { (_, rows) -> rows.sumOf { it.monthlyCents } }

        val contributionsByMember = fundMovements
            .filter { it.type == FundMovementType.CONTRIBUTION && it.memberId != null }
            .groupBy { it.memberId!! }
            .mapValues { (_, rows) -> rows.sumOf { it.amountCents } }

        val withdrawalsByMember = fundMovements
            .filter { it.type == FundMovementType.WITHDRAWAL && it.memberId != null }
            .groupBy { it.memberId!! }
            .mapValues { (_, rows) -> rows.sumOf { it.amountCents } }

        val participants = members.filter { member ->
            member.isActive ||
                (directByMember[member.id] ?: 0L) != 0L ||
                (contributionsByMember[member.id] ?: 0L) != 0L ||
                (withdrawalsByMember[member.id] ?: 0L) != 0L
        }

        val netByMember = participants.associate { member ->
            val direct = directByMember[member.id] ?: 0L
            val contributions = contributionsByMember[member.id] ?: 0L
            val withdrawals = withdrawalsByMember[member.id] ?: 0L
            member.id to (direct + contributions - withdrawals)
        }

        val totalNet = netByMember.values.sum()
        val targets = targetShares(totalNet, participants, netByMember)
        val fairShare = if (participants.isEmpty()) 0L else Math.floorDiv(totalNet, participants.size.toLong())

        // Manual settlements already paid this month reduce the pending balance:
        // the payer's debt goes down (+ amount) and the receiver's credit too (- amount).
        val settledAdjustment = mutableMapOf<Long, Long>()
        settlements
            .filter { it.month == month && !it.isClosingMarker && it.amountCents > 0L }
            .forEach { record ->
                settledAdjustment[record.fromMemberId] =
                    (settledAdjustment[record.fromMemberId] ?: 0L) + record.amountCents
                settledAdjustment[record.toMemberId] =
                    (settledAdjustment[record.toMemberId] ?: 0L) - record.amountCents
            }

        val memberSettlements = participants.map { member ->
            val direct = directByMember[member.id] ?: 0L
            val contributions = contributionsByMember[member.id] ?: 0L
            val withdrawals = withdrawalsByMember[member.id] ?: 0L
            val net = netByMember[member.id] ?: 0L
            val target = targets[member.id] ?: 0L
            val adjustment = settledAdjustment[member.id] ?: 0L
            MemberSettlement(
                memberId = member.id,
                name = member.name,
                colorHex = member.colorHex,
                directPaidCents = direct,
                fundContributionsCents = contributions,
                fundWithdrawalsCents = withdrawals,
                netContributionCents = net,
                targetCents = target,
                differenceCents = net - target + adjustment,
                settledAdjustmentCents = adjustment,
            )
        }.sortedByDescending { it.netContributionCents }

        return HouseholdSummary(
            month = month,
            directSpendCents = directExpenses.sumOf { it.monthlyCents },
            fundSpendCents = fundCovered.sumOf { it.monthlyCents },
            totalSpendCents = expenses.sumOf { it.monthlyCents },
            installmentSpendCents = installments.sumOf { it.monthlyCents },
            installmentCount = installments.size,
            pendingInstallmentsCents = installments.filter { it.installmentPaid == false }.sumOf { it.monthlyCents },
            fundContributionsCents = fundMovements
                .filter { it.type == FundMovementType.CONTRIBUTION }
                .sumOf { it.amountCents },
            fundWithdrawalsCents = fundMovements
                .filter { it.type == FundMovementType.WITHDRAWAL }
                .sumOf { it.amountCents },
            fundBalanceCents = fundBalanceCents,
            totalNetContributionsCents = totalNet,
            fairShareCents = fairShare,
            members = memberSettlements,
            transfers = settleTransfers(memberSettlements),
            settlements = settlements,
        )
    }

    /**
     * Assigns each participant an exact target. The floor is split equally and
     * the remaining cents are absorbed by the largest contributors.
     *
     * Floor division/modulo (instead of truncating `/` and `%`) keep the sum of
     * the targets identical to [totalNet] even when it is negative.
     */
    private fun targetShares(
        totalNet: Long,
        participants: List<HouseholdMember>,
        netByMember: Map<Long, Long>,
    ): Map<Long, Long> {
        if (participants.isEmpty()) return emptyMap()
        val count = participants.size.toLong()
        val floor = Math.floorDiv(totalNet, count)
        val result = participants.associate { it.id to floor }.toMutableMap()
        var remainder = Math.floorMod(totalNet, count).toInt()
        val ordered = participants.sortedByDescending { netByMember[it.id] ?: 0L }
        var index = 0
        while (remainder > 0 && ordered.isNotEmpty()) {
            val member = ordered[index % ordered.size]
            result[member.id] = (result[member.id] ?: 0L) + 1L
            remainder--
            index++
        }
        return result
    }

    private fun settleTransfers(settlements: List<MemberSettlement>): List<SettlementTransfer> {
        data class Balance(val member: MemberSettlement, var amount: Long)

        val debtors = settlements
            .filter { it.differenceCents < 0 }
            .map { Balance(it, -it.differenceCents) }
            .sortedByDescending { it.amount }
            .toMutableList()
        val creditors = settlements
            .filter { it.differenceCents > 0 }
            .map { Balance(it, it.differenceCents) }
            .sortedByDescending { it.amount }
            .toMutableList()

        val transfers = mutableListOf<SettlementTransfer>()
        var d = 0
        var c = 0
        while (d < debtors.size && c < creditors.size) {
            val amount = min(debtors[d].amount, creditors[c].amount)
            if (amount > 0) {
                transfers += SettlementTransfer(
                    fromMemberId = debtors[d].member.memberId,
                    fromName = debtors[d].member.name,
                    toMemberId = creditors[c].member.memberId,
                    toName = creditors[c].member.name,
                    amountCents = amount,
                )
            }
            debtors[d].amount -= amount
            creditors[c].amount -= amount
            if (debtors[d].amount <= 0L) d++
            if (creditors[c].amount <= 0L) c++
        }
        return transfers.sortedByDescending { it.amountCents }
    }
}
