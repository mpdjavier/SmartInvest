package com.smartinvest.domain.engine

import com.smartinvest.domain.model.ExpensePaymentMethod
import com.smartinvest.domain.model.FundMovement
import com.smartinvest.domain.model.FundMovementType
import com.smartinvest.domain.model.HouseholdMember
import com.smartinvest.domain.model.MonthlyExpenseItem
import com.smartinvest.domain.model.RegisteredSettlement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class SettlementCalculatorTest {

    private val month = YearMonth.of(2025, 5)
    private val me = HouseholdMember(1, "Vos", true, "#22C55E", true)
    private val mom = HouseholdMember(2, "Mamá", false, "#A78BFA", true)

    private fun expense(
        id: Long,
        payer: Long,
        cents: Long,
        method: ExpensePaymentMethod = ExpensePaymentMethod.DEBIT,
        installment: Boolean = false,
    ) = MonthlyExpenseItem(
        expenseId = id,
        installmentId = if (installment) id * 10 else null,
        description = "gasto $id",
        totalCents = cents,
        monthlyCents = cents,
        date = LocalDate.of(2025, 5, 10),
        categoryId = 1,
        paidByMemberId = payer,
        paymentMethod = method,
        paidFromFund = method == ExpensePaymentMethod.COMMON_FUND,
        installmentNumber = if (installment) 1 else null,
        installmentCount = if (installment) 6 else null,
        installmentPaid = null,
        creditCardId = if (installment) 1 else null,
    )

    @Test
    fun `equal split asks the lower contributor to transfer the difference`() {
        val expenses = listOf(
            expense(1, me.id, 60_000_00),
            expense(2, mom.id, 20_000_00),
        )
        val summary = SettlementCalculator.calculate(month, listOf(me, mom), expenses, emptyList(), 0)

        assertEquals(80_000_00L, summary.totalNetContributionsCents)
        assertEquals(40_000_00L, summary.fairShareCents)
        assertEquals(1, summary.transfers.size)
        val transfer = summary.transfers.first()
        assertEquals(mom.id, transfer.fromMemberId)
        assertEquals(me.id, transfer.toMemberId)
        assertEquals(20_000_00L, transfer.amountCents)
    }

    @Test
    fun `fund covered expenses are not attributed to a member`() {
        val expenses = listOf(
            expense(1, me.id, 30_000_00),
            expense(2, me.id, 50_000_00, ExpensePaymentMethod.COMMON_FUND),
        )
        val summary = SettlementCalculator.calculate(month, listOf(me, mom), expenses, emptyList(), 500_000_00)

        assertEquals(30_000_00L, summary.directSpendCents)
        assertEquals(50_000_00L, summary.fundSpendCents)
        assertEquals(30_000_00L, summary.totalNetContributionsCents)
        assertEquals(15_000_00L, summary.transfers.first().amountCents)
    }

    @Test
    fun `withdrawals reduce the withdrawing member contribution`() {
        val movements = listOf(
            FundMovement(1, FundMovementType.CONTRIBUTION, 40_000_00, LocalDate.of(2025, 5, 3), month, me.id, "aporte", null),
            FundMovement(2, FundMovementType.WITHDRAWAL, 10_000_00, LocalDate.of(2025, 5, 9), month, me.id, "retiro", null),
            FundMovement(3, FundMovementType.CONTRIBUTION, 20_000_00, LocalDate.of(2025, 5, 4), month, mom.id, "aporte", null),
        )
        val summary = SettlementCalculator.calculate(month, listOf(me, mom), emptyList(), movements, 100_000_00)

        val mine = summary.members.first { it.memberId == me.id }
        val hers = summary.members.first { it.memberId == mom.id }
        assertEquals(30_000_00L, mine.netContributionCents)
        assertEquals(20_000_00L, hers.netContributionCents)
        assertEquals(5_000_00L, summary.transfers.first().amountCents)
    }

    @Test
    fun `negative total keeps every cent assigned`() {
        val movements = listOf(
            FundMovement(1, FundMovementType.WITHDRAWAL, 7, LocalDate.of(2025, 5, 9), month, mom.id, "retiro", null),
        )
        val summary = SettlementCalculator.calculate(month, listOf(me, mom), emptyList(), movements, 0)

        assertEquals(-7L, summary.totalNetContributionsCents)
        assertEquals(-4L, summary.fairShareCents)
        assertEquals(-7L, summary.members.sumOf { it.targetCents })
        assertEquals(1, summary.transfers.size)
        val transfer = summary.transfers.first()
        assertEquals(mom.id, transfer.fromMemberId)
        assertEquals(me.id, transfer.toMemberId)
        assertEquals(3L, transfer.amountCents)
    }

    @Test
    fun `balanced month has no transfers`() {
        val expenses = listOf(
            expense(1, me.id, 25_000_00),
            expense(2, mom.id, 25_000_00),
        )
        val summary = SettlementCalculator.calculate(month, listOf(me, mom), expenses, emptyList(), 0)
        assertTrue(summary.isBalanced)
        assertTrue(summary.transfers.isEmpty())
        assertFalse(summary.isMonthClosed)
    }

    @Test
    fun `manual settlement closes the month and records date and amount`() {
        val expenses = listOf(
            expense(1, me.id, 60_000_00),
            expense(2, mom.id, 20_000_00),
        )
        val paidDate = LocalDate.of(2025, 6, 3)
        val registered = listOf(
            RegisteredSettlement(
                id = 99,
                month = month,
                fromMemberId = mom.id,
                fromName = mom.name,
                toMemberId = me.id,
                toName = me.name,
                amountCents = 20_000_00L,
                date = paidDate,
                note = "Transferencia MP",
                createdAt = 1_000L,
            ),
        )
        val summary = SettlementCalculator.calculate(
            month = month,
            members = listOf(me, mom),
            expenses = expenses,
            fundMovements = emptyList(),
            fundBalanceCents = 0,
            settlements = registered,
        )

        assertTrue(summary.isMonthClosed)
        assertEquals(20_000_00L, summary.registeredSettlementCents)
        assertEquals(paidDate, summary.settlementDate)
    }
}
