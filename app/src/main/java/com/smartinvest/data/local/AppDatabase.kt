package com.smartinvest.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.smartinvest.data.local.dao.AlertDao
import com.smartinvest.data.local.dao.CategoryDao
import com.smartinvest.data.local.dao.CreditCardDao
import com.smartinvest.data.local.dao.ExpenseDao
import com.smartinvest.data.local.dao.FundMovementDao
import com.smartinvest.data.local.dao.HoldingDao
import com.smartinvest.data.local.dao.InstallmentDao
import com.smartinvest.data.local.dao.InstrumentQuoteDao
import com.smartinvest.data.local.dao.MemberDao
import com.smartinvest.data.local.dao.FxRateDao
import com.smartinvest.data.local.dao.RateHistoryDao
import com.smartinvest.data.local.dao.SalaryPlanDao
import com.smartinvest.data.local.dao.SettlementDao
import com.smartinvest.data.local.dao.SnapshotDao
import com.smartinvest.data.local.dao.WalletDao
import com.smartinvest.data.local.entity.AlertEntity
import com.smartinvest.data.local.entity.CategoryEntity
import com.smartinvest.data.local.entity.CreditCardEntity
import com.smartinvest.data.local.entity.ExpenseEntity
import com.smartinvest.data.local.entity.FundMovementEntity
import com.smartinvest.data.local.entity.FxRateEntity
import com.smartinvest.data.local.entity.HoldingEntity
import com.smartinvest.data.local.entity.InstallmentEntity
import com.smartinvest.data.local.entity.InstrumentQuoteEntity
import com.smartinvest.data.local.entity.MemberEntity
import com.smartinvest.data.local.entity.PortfolioSnapshotEntity
import com.smartinvest.data.local.entity.RateHistoryEntity
import com.smartinvest.data.local.entity.SalaryPlanEntity
import com.smartinvest.data.local.entity.SettlementEntity
import com.smartinvest.data.local.entity.WalletEntity

@Database(
    entities = [
        MemberEntity::class,
        CategoryEntity::class,
        CreditCardEntity::class,
        ExpenseEntity::class,
        InstallmentEntity::class,
        FundMovementEntity::class,
        WalletEntity::class,
        HoldingEntity::class,
        PortfolioSnapshotEntity::class,
        FxRateEntity::class,
        RateHistoryEntity::class,
        SalaryPlanEntity::class,
        AlertEntity::class,
        SettlementEntity::class,
        InstrumentQuoteEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun memberDao(): MemberDao
    abstract fun categoryDao(): CategoryDao
    abstract fun creditCardDao(): CreditCardDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun installmentDao(): InstallmentDao
    abstract fun fundMovementDao(): FundMovementDao
    abstract fun settlementDao(): SettlementDao
    abstract fun walletDao(): WalletDao
    abstract fun holdingDao(): HoldingDao
    abstract fun instrumentQuoteDao(): InstrumentQuoteDao
    abstract fun snapshotDao(): SnapshotDao
    abstract fun fxRateDao(): FxRateDao
    abstract fun rateHistoryDao(): RateHistoryDao
    abstract fun salaryPlanDao(): SalaryPlanDao
    abstract fun alertDao(): AlertDao
}
