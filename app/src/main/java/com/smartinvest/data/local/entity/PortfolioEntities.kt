package com.smartinvest.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.smartinvest.domain.model.AlertSeverity
import com.smartinvest.domain.model.AlertType
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.MarketSource
import com.smartinvest.domain.model.QuoteUnit
import com.smartinvest.domain.model.WalletType

@Entity(tableName = "wallets")
data class WalletEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val institution: String,
    val type: WalletType,
    val currency: CurrencyCode = CurrencyCode.ARS,
    val balanceCents: Long = 0,
    val tnaPercent: Double = 0.0,
    val remuneratedCapCents: Long = 0,
    val isActive: Boolean = true,
    val rateSource: String = "manual",
    val rateUpdatedAt: Long? = null,
    val liquidityPriority: Int = 2,
    val notes: String = "",
    /** Optional FCI holding whose TNA / market rate backs this wallet. */
    val linkedHoldingId: Long? = null,
)

@Entity(tableName = "holdings")
data class HoldingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val symbol: String,
    val name: String,
    val assetType: AssetType,
    val currency: CurrencyCode = CurrencyCode.ARS,
    val currentValueCents: Long,
    val costCents: Long = 0,
    val tnaPercent: Double? = null,
    val maturityEpochDay: Long? = null,
    val notes: String = "",
    @ColumnInfo(defaultValue = "''") val marketKey: String = "",
    @ColumnInfo(defaultValue = "'MANUAL'") val source: MarketSource = MarketSource.MANUAL,
    @ColumnInfo(defaultValue = "0.0") val quantity: Double = 0.0,
    @ColumnInfo(defaultValue = "'PER_UNIT'") val quoteUnit: QuoteUnit = QuoteUnit.PER_UNIT,
    @ColumnInfo(defaultValue = "0") val isWallet: Boolean = false,
    @ColumnInfo(defaultValue = "1") val settlementDays: Int = 1,
)

@Entity(
    tableName = "instrument_quotes",
    indices = [
        Index(value = ["marketKey", "dateEpochDay"], unique = true),
        Index("marketKey"),
    ],
)
data class InstrumentQuoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val marketKey: String,
    val symbol: String,
    val source: MarketSource,
    val price: Double,
    val currency: CurrencyCode,
    val quoteUnit: QuoteUnit,
    val dateEpochDay: Long,
    val fetchedAt: Long,
)

@Entity(
    tableName = "portfolio_snapshots",
    indices = [Index(value = ["dateEpochDay"], unique = true)],
)
data class PortfolioSnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dateEpochDay: Long,
    val totalArsCents: Long,
    val totalUsdCents: Long,
    val arsAssetsCents: Long,
    val usdAssetsArsCents: Long,
    val dailyInterestCents: Long,
    val createdAt: Long,
)

@Entity(tableName = "fx_rates")
data class FxRateEntity(
    @PrimaryKey val house: String,
    val name: String,
    val buy: Double,
    val sell: Double,
    val updatedAt: Long,
)

@Entity(
    tableName = "rate_history",
    indices = [Index(value = ["walletId", "dateEpochDay"], unique = true)],
)
data class RateHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val walletId: Long,
    val dateEpochDay: Long,
    val tnaPercent: Double,
    val source: String,
)

@Entity(tableName = "salary_plans", indices = [Index(value = ["month"], unique = true)])
data class SalaryPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val month: String,
    val netSalaryCents: Long,
    val cushionCents: Long,
    val fciCents: Long,
    val lecapCents: Long,
    val mepCents: Long,
    @ColumnInfo(defaultValue = "0") val bondCents: Long = 0,
    @ColumnInfo(defaultValue = "0") val liquidityCents: Long = 0,
    val createdAt: Long,
)

@Entity(
    tableName = "alerts",
    indices = [Index(value = ["dedupeKey"], unique = true), Index("createdAt")],
)
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: AlertType,
    val severity: AlertSeverity,
    val title: String,
    val message: String,
    val dedupeKey: String,
    val createdAt: Long,
    val isRead: Boolean = false,
)
