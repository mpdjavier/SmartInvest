package com.smartinvest.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.smartinvest.data.local.entity.CategoryEntity
import com.smartinvest.data.local.entity.CreditCardEntity
import com.smartinvest.data.local.entity.ExpenseEntity
import com.smartinvest.data.local.entity.FundMovementEntity
import com.smartinvest.data.local.entity.InstallmentEntity
import com.smartinvest.data.local.entity.MemberEntity
import com.smartinvest.data.local.entity.SettlementEntity
import com.smartinvest.data.local.model.InstallmentRow
import kotlinx.coroutines.flow.Flow

@Dao
interface MemberDao {
    @Query("SELECT * FROM members ORDER BY isCurrentUser DESC, id ASC")
    fun observeAll(): Flow<List<MemberEntity>>

    @Query("SELECT * FROM members ORDER BY isCurrentUser DESC, id ASC")
    suspend fun getAll(): List<MemberEntity>

    @Query("SELECT * FROM members WHERE id = :id")
    suspend fun getById(id: Long): MemberEntity?

    @Insert
    suspend fun insert(entity: MemberEntity): Long

    @Update
    suspend fun update(entity: MemberEntity)

    @Query("SELECT COUNT(*) FROM members")
    suspend fun count(): Int
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY isArchived ASC, sortOrder ASC, name ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE isArchived = 0 ORDER BY sortOrder ASC, name ASC")
    fun observeActive(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY isArchived ASC, sortOrder ASC, name ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entity: CategoryEntity): Long

    @Update
    suspend fun update(entity: CategoryEntity)

    @Query("UPDATE categories SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)
}

@Dao
interface CreditCardDao {
    @Query("SELECT * FROM credit_cards ORDER BY isActive DESC, name ASC")
    fun observeAll(): Flow<List<CreditCardEntity>>

    @Query("SELECT * FROM credit_cards WHERE isActive = 1 ORDER BY name ASC")
    fun observeActive(): Flow<List<CreditCardEntity>>

    @Query("SELECT * FROM credit_cards ORDER BY name ASC")
    suspend fun getAll(): List<CreditCardEntity>

    @Query("SELECT * FROM credit_cards WHERE id = :id")
    suspend fun getById(id: Long): CreditCardEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CreditCardEntity): Long

    @Update
    suspend fun update(entity: CreditCardEntity)

    @Query("UPDATE credit_cards SET isActive = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)

    @Query("SELECT COUNT(*) FROM credit_cards")
    suspend fun count(): Int
}

@Dao
interface ExpenseDao {
    @Query(
        """
        SELECT * FROM expenses
        WHERE month = :month AND paymentMethod != 'CREDIT'
        ORDER BY dateEpochDay DESC, id DESC
        """,
    )
    fun observeCashForMonth(month: String): Flow<List<ExpenseEntity>>

    @Query(
        """
        SELECT i.id AS installmentId,
               i.expenseId AS expenseId,
               i.number AS number,
               i.count AS count,
               i.dueMonth AS dueMonth,
               i.amountCents AS amountCents,
               i.isPaid AS isPaid,
               e.description AS description,
               e.totalAmountCents AS totalAmountCents,
               e.categoryId AS categoryId,
               e.paidByMemberId AS paidByMemberId,
               e.creditCardId AS creditCardId,
               e.dateEpochDay AS dateEpochDay
        FROM installments i
        INNER JOIN expenses e ON e.id = i.expenseId
        WHERE i.dueMonth = :month
        ORDER BY e.dateEpochDay DESC, e.id DESC
        """,
    )
    fun observeInstallmentRows(month: String): Flow<List<InstallmentRow>>

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getById(id: Long): ExpenseEntity?

    @Query("SELECT * FROM expenses WHERE paymentMethod = 'CREDIT' ORDER BY dateEpochDay DESC")
    suspend fun getCreditExpenses(): List<ExpenseEntity>

    @Query("SELECT COUNT(*) FROM expenses")
    suspend fun count(): Int

    @Insert
    suspend fun insert(entity: ExpenseEntity): Long

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface InstallmentDao {
    @Insert
    suspend fun insertAll(entities: List<InstallmentEntity>)

    @Query("SELECT * FROM installments WHERE id = :id")
    suspend fun getById(id: Long): InstallmentEntity?

    @Query("SELECT * FROM installments WHERE expenseId = :expenseId ORDER BY number ASC")
    suspend fun getForExpense(expenseId: Long): List<InstallmentEntity>

    @Query("UPDATE installments SET isPaid = :paid, paidAt = :paidAt WHERE id = :id")
    suspend fun setPaid(id: Long, paid: Boolean, paidAt: Long?)

    @Query("DELETE FROM installments WHERE expenseId = :expenseId")
    suspend fun deleteForExpense(expenseId: Long)
}

@Dao
interface FundMovementDao {
    @Query("SELECT * FROM fund_movements WHERE month = :month ORDER BY dateEpochDay DESC, id DESC")
    fun observeForMonth(month: String): Flow<List<FundMovementEntity>>

    @Query("SELECT * FROM fund_movements ORDER BY dateEpochDay DESC, id DESC")
    fun observeAll(): Flow<List<FundMovementEntity>>

    @Query(
        """
        SELECT COALESCE(SUM(
            CASE WHEN type = 'CONTRIBUTION' THEN amountCents ELSE -amountCents END
        ), 0) FROM fund_movements
        """,
    )
    fun observeBalance(): Flow<Long>

    @Query(
        """
        SELECT COALESCE(SUM(
            CASE WHEN type = 'CONTRIBUTION' THEN amountCents ELSE -amountCents END
        ), 0) FROM fund_movements
        """,
    )
    suspend fun currentBalance(): Long

    @Query("SELECT * FROM fund_movements WHERE id = :id")
    suspend fun getById(id: Long): FundMovementEntity?

    @Query("DELETE FROM fund_movements WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM fund_movements WHERE expenseId = :expenseId")
    suspend fun deleteForExpense(expenseId: Long)

    @Insert
    suspend fun insert(entity: FundMovementEntity): Long

    @Query("SELECT COUNT(*) FROM fund_movements")
    suspend fun count(): Int
}

@Dao
interface SettlementDao {
    @Query("SELECT * FROM settlements WHERE month = :month ORDER BY dateEpochDay DESC, id DESC")
    fun observeForMonth(month: String): Flow<List<SettlementEntity>>

    @Query("SELECT * FROM settlements WHERE month = :month ORDER BY dateEpochDay DESC, id DESC")
    suspend fun getForMonth(month: String): List<SettlementEntity>

    @Query("SELECT * FROM settlements WHERE id = :id")
    suspend fun getById(id: Long): SettlementEntity?

    @Insert
    suspend fun insert(entity: SettlementEntity): Long

    @Query("DELETE FROM settlements WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM settlements")
    suspend fun count(): Int
}
