package com.smartinvest.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.smartinvest.domain.model.ExpensePaymentMethod
import com.smartinvest.domain.model.FundMovementType

@Entity(tableName = "members")
data class MemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isCurrentUser: Boolean = false,
    val colorHex: String = "#22C55E",
    val isActive: Boolean = true,
)

@Entity(tableName = "categories")
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String = "🏠",
    val colorHex: String = "#38BDF8",
    val isArchived: Boolean = false,
    val sortOrder: Int = 0,
)

@Entity(tableName = "credit_cards")
data class CreditCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val issuer: String = "",
    val brand: String = "VISA",
    val closingDay: Int,
    val dueDay: Int,
    val colorHex: String = "#F59E0B",
    val isActive: Boolean = true,
)

@Entity(
    tableName = "expenses",
    indices = [
        Index("month"),
        Index("paidByMemberId"),
        Index("categoryId"),
        Index("creditCardId"),
    ],
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val description: String,
    val totalAmountCents: Long,
    val dateEpochDay: Long,
    val month: String,
    val categoryId: Long,
    val paidByMemberId: Long,
    val paymentMethod: ExpensePaymentMethod,
    val creditCardId: Long? = null,
    val installments: Int = 1,
    val firstInstallmentMonth: String? = null,
    val notes: String = "",
    val walletHoldingId: Long? = null,
    @androidx.room.ColumnInfo(defaultValue = "0.0") val walletQuantityDelta: Double = 0.0,
    @androidx.room.ColumnInfo(defaultValue = "0") val walletValueDeltaCents: Long = 0,
    val createdAt: Long,
)

@Entity(
    tableName = "installments",
    foreignKeys = [
        ForeignKey(
            entity = ExpenseEntity::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("expenseId"),
        Index("dueMonth"),
        Index(value = ["expenseId", "dueMonth"], unique = true),
    ],
)
data class InstallmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expenseId: Long,
    val creditCardId: Long?,
    val number: Int,
    val count: Int,
    val dueMonth: String,
    val amountCents: Long,
    val isPaid: Boolean = false,
    val paidAt: Long? = null,
)

@Entity(
    tableName = "fund_movements",
    indices = [Index("month"), Index("memberId"), Index("expenseId")],
)
data class FundMovementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: FundMovementType,
    val amountCents: Long,
    val dateEpochDay: Long,
    val month: String,
    val memberId: Long?,
    val description: String,
    val expenseId: Long? = null,
    val createdAt: Long,
)

/**
 * Manual close of a month. Created when the user registers the payment that
 * settles the month; deleting it reopens the month.
 */
@Entity(
    tableName = "settlements",
    indices = [Index("month"), Index("dateEpochDay")],
)
data class SettlementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val month: String,
    val fromMemberId: Long = 0,
    val fromName: String = "",
    val toMemberId: Long = 0,
    val toName: String = "",
    val amountCents: Long = 0,
    val dateEpochDay: Long,
    val note: String = "",
    val createdAt: Long,
)
