package com.smartinvest.data.mapper

import com.smartinvest.core.time.DateUtils
import com.smartinvest.data.local.entity.CategoryEntity
import com.smartinvest.data.local.entity.CreditCardEntity
import com.smartinvest.data.local.entity.ExpenseEntity
import com.smartinvest.data.local.entity.FundMovementEntity
import com.smartinvest.data.local.entity.MemberEntity
import com.smartinvest.data.local.entity.SettlementEntity
import com.smartinvest.data.local.model.InstallmentRow
import com.smartinvest.domain.model.CreditCard
import com.smartinvest.domain.model.ExpensePaymentMethod
import com.smartinvest.domain.model.FundMovement
import com.smartinvest.domain.model.HouseholdCategory
import com.smartinvest.domain.model.HouseholdMember
import com.smartinvest.domain.model.MonthlyExpenseItem
import com.smartinvest.domain.model.RegisteredSettlement
import java.time.YearMonth

fun MemberEntity.toDomain(): HouseholdMember = HouseholdMember(
    id = id,
    name = name,
    isCurrentUser = isCurrentUser,
    colorHex = colorHex,
    isActive = isActive,
)

fun HouseholdMember.toEntity(): MemberEntity = MemberEntity(
    id = id,
    name = name,
    isCurrentUser = isCurrentUser,
    colorHex = colorHex,
    isActive = isActive,
)

fun CategoryEntity.toDomain(): HouseholdCategory = HouseholdCategory(
    id = id,
    name = name,
    emoji = emoji,
    colorHex = colorHex,
    isArchived = isArchived,
)

fun CreditCardEntity.toDomain(): CreditCard = CreditCard(
    id = id,
    name = name,
    issuer = issuer,
    brand = brand,
    closingDay = closingDay,
    dueDay = dueDay,
    colorHex = colorHex,
    isActive = isActive,
)

fun CreditCard.toEntity(): CreditCardEntity = CreditCardEntity(
    id = id,
    name = name,
    issuer = issuer,
    brand = brand,
    closingDay = closingDay,
    dueDay = dueDay,
    colorHex = colorHex,
    isActive = isActive,
)

fun ExpenseEntity.toMonthlyItem(month: YearMonth): MonthlyExpenseItem = MonthlyExpenseItem(
    expenseId = id,
    installmentId = null,
    description = description,
    totalCents = totalAmountCents,
    monthlyCents = totalAmountCents,
    date = DateUtils.epochDayToDate(dateEpochDay),
    categoryId = categoryId,
    paidByMemberId = paidByMemberId,
    paymentMethod = paymentMethod,
    paidFromFund = paymentMethod == ExpensePaymentMethod.COMMON_FUND,
    installmentNumber = null,
    installmentCount = null,
    installmentPaid = null,
    creditCardId = creditCardId,
)

fun InstallmentRow.toMonthlyItem(): MonthlyExpenseItem = MonthlyExpenseItem(
    expenseId = expenseId,
    installmentId = installmentId,
    description = description,
    totalCents = totalAmountCents,
    monthlyCents = amountCents,
    date = DateUtils.epochDayToDate(dateEpochDay),
    categoryId = categoryId,
    paidByMemberId = paidByMemberId,
    paymentMethod = ExpensePaymentMethod.CREDIT,
    paidFromFund = false,
    installmentNumber = number,
    installmentCount = count,
    installmentPaid = isPaid,
    creditCardId = creditCardId,
)

fun FundMovementEntity.toDomain(): FundMovement = FundMovement(
    id = id,
    type = type,
    amountCents = amountCents,
    date = DateUtils.epochDayToDate(dateEpochDay),
    month = YearMonth.parse(month),
    memberId = memberId,
    description = description,
    expenseId = expenseId,
)

fun SettlementEntity.toDomain(): RegisteredSettlement = RegisteredSettlement(
    id = id,
    month = YearMonth.parse(month),
    fromMemberId = fromMemberId,
    fromName = fromName,
    toMemberId = toMemberId,
    toName = toName,
    amountCents = amountCents,
    date = DateUtils.epochDayToDate(dateEpochDay),
    note = note,
    createdAt = createdAt,
)
