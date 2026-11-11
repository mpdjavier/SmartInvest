package com.smartinvest.domain.repository

import com.smartinvest.domain.model.CreditCard
import com.smartinvest.domain.model.ExpensePaymentMethod
import com.smartinvest.domain.model.FundMovement
import com.smartinvest.domain.model.FundMovementType
import com.smartinvest.domain.model.HouseholdCategory
import com.smartinvest.domain.model.HouseholdMember
import com.smartinvest.domain.model.MonthlyExpenseItem
import com.smartinvest.domain.model.RegisteredSettlement
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.YearMonth

data class NewExpense(
    val description: String,
    val totalCents: Long,
    val date: LocalDate,
    val categoryId: Long,
    val paidByMemberId: Long,
    val paymentMethod: ExpensePaymentMethod,
    val creditCardId: Long? = null,
    val installments: Int = 1,
    val notes: String = "",
    /** FCI marked as wallet that funds a WALLET_FCI expense. */
    val walletHoldingId: Long? = null,
)

data class NewFundMovement(
    val type: FundMovementType,
    val amountCents: Long,
    val date: LocalDate,
    val memberId: Long?,
    val description: String,
)

data class NewSettlement(
    val month: YearMonth,
    val fromMemberId: Long = 0,
    val fromName: String = "",
    val toMemberId: Long = 0,
    val toName: String = "",
    val amountCents: Long = 0,
    val date: LocalDate = LocalDate.now(),
    val note: String = "",
)

interface HouseholdRepository {
    fun observeMembers(): Flow<List<HouseholdMember>>
    fun observeCategories(includeArchived: Boolean = true): Flow<List<HouseholdCategory>>
    fun observeCards(includeInactive: Boolean = true): Flow<List<CreditCard>>
    fun observeMonthlyExpenses(month: YearMonth): Flow<List<MonthlyExpenseItem>>
    fun observeFundMovements(month: YearMonth): Flow<List<FundMovement>>
    fun observeSettlements(month: YearMonth): Flow<List<RegisteredSettlement>>
    fun observeFundBalance(): Flow<Long>
    suspend fun currentFundBalance(): Long

    suspend fun addMember(name: String): Long
    suspend fun renameMember(id: Long, name: String)
    suspend fun setMemberActive(id: Long, active: Boolean)

    suspend fun addCategory(name: String, emoji: String, colorHex: String): Long
    suspend fun updateCategory(id: Long, name: String, emoji: String, colorHex: String)
    suspend fun setCategoryArchived(id: Long, archived: Boolean)

    suspend fun upsertCard(card: CreditCard): Long
    suspend fun setCardActive(id: Long, active: Boolean)

    suspend fun addExpense(input: NewExpense): Long
    suspend fun deleteExpense(expenseId: Long)
    suspend fun setInstallmentPaid(installmentId: Long, paid: Boolean)

    suspend fun addFundMovement(input: NewFundMovement): Long
    suspend fun deleteFundMovement(id: Long)

    suspend fun registerSettlement(input: NewSettlement): Long
    suspend fun deleteSettlement(id: Long)
}
