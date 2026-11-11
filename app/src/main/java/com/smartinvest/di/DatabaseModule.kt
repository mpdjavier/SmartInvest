package com.smartinvest.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.smartinvest.data.local.AppDatabase
import com.smartinvest.data.local.dao.AlertDao
import com.smartinvest.data.local.dao.CategoryDao
import com.smartinvest.data.local.dao.CreditCardDao
import com.smartinvest.data.local.dao.ExpenseDao
import com.smartinvest.data.local.dao.FundMovementDao
import com.smartinvest.data.local.dao.FxRateDao
import com.smartinvest.data.local.dao.HoldingDao
import com.smartinvest.data.local.dao.InstallmentDao
import com.smartinvest.data.local.dao.InstrumentQuoteDao
import com.smartinvest.data.local.dao.MemberDao
import com.smartinvest.data.local.dao.RateHistoryDao
import com.smartinvest.data.local.dao.SalaryPlanDao
import com.smartinvest.data.local.dao.SettlementDao
import com.smartinvest.data.local.dao.SnapshotDao
import com.smartinvest.data.local.dao.WalletDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "smartinvest.db")
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .build()

    @Provides fun memberDao(db: AppDatabase): MemberDao = db.memberDao()
    @Provides fun categoryDao(db: AppDatabase): CategoryDao = db.categoryDao()
    @Provides fun cardDao(db: AppDatabase): CreditCardDao = db.creditCardDao()
    @Provides fun expenseDao(db: AppDatabase): ExpenseDao = db.expenseDao()
    @Provides fun installmentDao(db: AppDatabase): InstallmentDao = db.installmentDao()
    @Provides fun fundDao(db: AppDatabase): FundMovementDao = db.fundMovementDao()
    @Provides fun walletDao(db: AppDatabase): WalletDao = db.walletDao()
    @Provides fun holdingDao(db: AppDatabase): HoldingDao = db.holdingDao()
    @Provides fun snapshotDao(db: AppDatabase): SnapshotDao = db.snapshotDao()
    @Provides fun fxRateDao(db: AppDatabase): FxRateDao = db.fxRateDao()
    @Provides fun rateHistoryDao(db: AppDatabase): RateHistoryDao = db.rateHistoryDao()
    @Provides fun salaryPlanDao(db: AppDatabase): SalaryPlanDao = db.salaryPlanDao()
    @Provides fun alertDao(db: AppDatabase): AlertDao = db.alertDao()
    @Provides fun settlementDao(db: AppDatabase): SettlementDao = db.settlementDao()
    @Provides fun instrumentQuoteDao(db: AppDatabase): InstrumentQuoteDao = db.instrumentQuoteDao()

    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `holdings` ADD COLUMN `marketKey` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `holdings` ADD COLUMN `source` TEXT NOT NULL DEFAULT 'MANUAL'")
            db.execSQL("ALTER TABLE `holdings` ADD COLUMN `quantity` REAL NOT NULL DEFAULT 0.0")
            db.execSQL("ALTER TABLE `holdings` ADD COLUMN `quoteUnit` TEXT NOT NULL DEFAULT 'PER_UNIT'")

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `settlements` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `month` TEXT NOT NULL,
                    `fromMemberId` INTEGER NOT NULL,
                    `fromName` TEXT NOT NULL,
                    `toMemberId` INTEGER NOT NULL,
                    `toName` TEXT NOT NULL,
                    `amountCents` INTEGER NOT NULL,
                    `dateEpochDay` INTEGER NOT NULL,
                    `note` TEXT NOT NULL,
                    `createdAt` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_settlements_month` ON `settlements` (`month`)")
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_settlements_dateEpochDay` ON `settlements` (`dateEpochDay`)",
            )

            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `instrument_quotes` (
                    `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    `marketKey` TEXT NOT NULL,
                    `symbol` TEXT NOT NULL,
                    `source` TEXT NOT NULL,
                    `price` REAL NOT NULL,
                    `currency` TEXT NOT NULL,
                    `quoteUnit` TEXT NOT NULL,
                    `dateEpochDay` INTEGER NOT NULL,
                    `fetchedAt` INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_instrument_quotes_marketKey_dateEpochDay` " +
                    "ON `instrument_quotes` (`marketKey`, `dateEpochDay`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_instrument_quotes_marketKey` " +
                    "ON `instrument_quotes` (`marketKey`)",
            )
        }
    }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `wallets` ADD COLUMN `linkedHoldingId` INTEGER")
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `holdings` ADD COLUMN `isWallet` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `holdings` ADD COLUMN `settlementDays` INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE `expenses` ADD COLUMN `walletHoldingId` INTEGER")
            db.execSQL("ALTER TABLE `expenses` ADD COLUMN `walletQuantityDelta` REAL NOT NULL DEFAULT 0.0")
            db.execSQL("ALTER TABLE `expenses` ADD COLUMN `walletValueDeltaCents` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `salary_plans` ADD COLUMN `bondCents` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `salary_plans` ADD COLUMN `liquidityCents` INTEGER NOT NULL DEFAULT 0")
        }
    }
}
