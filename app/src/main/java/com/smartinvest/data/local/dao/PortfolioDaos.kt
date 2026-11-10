package com.smartinvest.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.smartinvest.data.local.entity.AlertEntity
import com.smartinvest.data.local.entity.FxRateEntity
import com.smartinvest.data.local.entity.HoldingEntity
import com.smartinvest.data.local.entity.InstrumentQuoteEntity
import com.smartinvest.data.local.entity.PortfolioSnapshotEntity
import com.smartinvest.data.local.entity.RateHistoryEntity
import com.smartinvest.data.local.entity.SalaryPlanEntity
import com.smartinvest.data.local.entity.WalletEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WalletDao {
    @Query("SELECT * FROM wallets ORDER BY isActive DESC, type ASC, name ASC")
    fun observeAll(): Flow<List<WalletEntity>>

    @Query("SELECT * FROM wallets WHERE isActive = 1 ORDER BY type ASC, name ASC")
    fun observeActive(): Flow<List<WalletEntity>>

    @Query("SELECT * FROM wallets ORDER BY type ASC, name ASC")
    suspend fun getAll(): List<WalletEntity>

    @Query("SELECT * FROM wallets WHERE id = :id")
    suspend fun getById(id: Long): WalletEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WalletEntity): Long

    @Update
    suspend fun update(entity: WalletEntity)

    @Query("UPDATE wallets SET isActive = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)

    @Query(
        """
        UPDATE wallets
        SET tnaPercent = :tna, rateSource = :source, rateUpdatedAt = :updatedAt
        WHERE id = :id
        """,
    )
    suspend fun updateRate(id: Long, tna: Double, source: String, updatedAt: Long)

    @Query("SELECT COUNT(*) FROM wallets")
    suspend fun count(): Int
}

@Dao
interface HoldingDao {
    @Query("SELECT * FROM holdings ORDER BY assetType ASC, symbol ASC")
    fun observeAll(): Flow<List<HoldingEntity>>

    @Query("SELECT * FROM holdings ORDER BY assetType ASC, symbol ASC")
    suspend fun getAll(): List<HoldingEntity>

    @Query("SELECT * FROM holdings WHERE id = :id")
    suspend fun getById(id: Long): HoldingEntity?

    @Query("SELECT * FROM holdings WHERE UPPER(symbol) LIKE UPPER(:symbol)")
    suspend fun getBySymbol(symbol: String): List<HoldingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HoldingEntity): Long

    @Query("DELETE FROM holdings WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        """
        UPDATE holdings
        SET currentValueCents = :valueCents,
            name = CASE WHEN name = '' THEN :name ELSE name END
        WHERE id = :id
        """,
    )
    suspend fun updateValuation(id: Long, valueCents: Long, name: String)
}

@Dao
interface InstrumentQuoteDao {
    @Query("SELECT * FROM instrument_quotes WHERE marketKey = :marketKey ORDER BY dateEpochDay ASC")
    fun observeForMarket(marketKey: String): Flow<List<InstrumentQuoteEntity>>

    @Query("SELECT * FROM instrument_quotes ORDER BY marketKey ASC, dateEpochDay ASC")
    fun observeAll(): Flow<List<InstrumentQuoteEntity>>

    @Query("SELECT * FROM instrument_quotes ORDER BY marketKey ASC, dateEpochDay ASC")
    suspend fun getAll(): List<InstrumentQuoteEntity>

    @Query(
        """
        SELECT * FROM instrument_quotes
        WHERE marketKey = :marketKey
        ORDER BY dateEpochDay DESC
        LIMIT 1
        """,
    )
    suspend fun latestForMarket(marketKey: String): InstrumentQuoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<InstrumentQuoteEntity>)

    @Query("DELETE FROM instrument_quotes WHERE dateEpochDay < :epochDay")
    suspend fun deleteOlderThan(epochDay: Long)

    /** Removes future-dated rows left by older versions that stamped quotes with the sync day. */
    @Query("DELETE FROM instrument_quotes WHERE marketKey = :marketKey AND dateEpochDay > :epochDay")
    suspend fun deleteForMarketAfter(marketKey: String, epochDay: Long)
}

@Dao
interface SnapshotDao {
    @Query("SELECT * FROM portfolio_snapshots ORDER BY dateEpochDay ASC")
    fun observeAll(): Flow<List<PortfolioSnapshotEntity>>

    @Query("SELECT * FROM portfolio_snapshots ORDER BY dateEpochDay ASC")
    suspend fun getAll(): List<PortfolioSnapshotEntity>

    @Query("SELECT * FROM portfolio_snapshots ORDER BY dateEpochDay DESC LIMIT 1")
    suspend fun getLatest(): PortfolioSnapshotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: PortfolioSnapshotEntity): Long

    @Query("DELETE FROM portfolio_snapshots WHERE dateEpochDay < :epochDay")
    suspend fun deleteOlderThan(epochDay: Long)
}

@Dao
interface FxRateDao {
    @Query("SELECT * FROM fx_rates ORDER BY house ASC")
    fun observeAll(): Flow<List<FxRateEntity>>

    @Query("SELECT * FROM fx_rates")
    suspend fun getAll(): List<FxRateEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<FxRateEntity>)

    @Query("SELECT * FROM fx_rates WHERE house = :house")
    suspend fun getByHouse(house: String): FxRateEntity?
}

@Dao
interface RateHistoryDao {
    @Query("SELECT * FROM rate_history WHERE walletId = :walletId ORDER BY dateEpochDay DESC, id DESC")
    fun observeForWallet(walletId: Long): Flow<List<RateHistoryEntity>>

    @Query(
        """
        SELECT * FROM rate_history
        WHERE walletId = :walletId
        ORDER BY dateEpochDay DESC, id DESC
        LIMIT 2
        """,
    )
    suspend fun latestTwo(walletId: Long): List<RateHistoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: RateHistoryEntity): Long
}

@Dao
interface SalaryPlanDao {
    @Query("SELECT * FROM salary_plans ORDER BY month DESC")
    fun observeAll(): Flow<List<SalaryPlanEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SalaryPlanEntity): Long
}

@Dao
interface AlertDao {
    @Query("SELECT * FROM alerts ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<AlertEntity>>

    @Query("SELECT COUNT(*) FROM alerts WHERE isRead = 0")
    fun observeUnreadCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: AlertEntity): Long

    @Query("UPDATE alerts SET isRead = 1 WHERE id = :id")
    suspend fun markRead(id: Long)

    @Query("UPDATE alerts SET isRead = 1")
    suspend fun markAllRead()

    @Query("DELETE FROM alerts WHERE createdAt < :before")
    suspend fun prune(before: Long)
}
