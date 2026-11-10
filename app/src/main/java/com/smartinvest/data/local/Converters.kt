package com.smartinvest.data.local

import androidx.room.TypeConverter
import com.smartinvest.domain.model.AlertSeverity
import com.smartinvest.domain.model.AlertType
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.ExpensePaymentMethod
import com.smartinvest.domain.model.FundMovementType
import com.smartinvest.domain.model.MarketSource
import com.smartinvest.domain.model.QuoteUnit
import com.smartinvest.domain.model.WalletType

class Converters {
    @TypeConverter fun fromPaymentMethod(value: ExpensePaymentMethod): String = value.name
    @TypeConverter fun toPaymentMethod(value: String): ExpensePaymentMethod = ExpensePaymentMethod.fromDb(value)

    @TypeConverter fun fromFundType(value: FundMovementType): String = value.name
    @TypeConverter fun toFundType(value: String): FundMovementType =
        FundMovementType.entries.firstOrNull { it.name == value } ?: FundMovementType.CONTRIBUTION

    @TypeConverter fun fromWalletType(value: WalletType): String = value.name
    @TypeConverter fun toWalletType(value: String): WalletType =
        WalletType.entries.firstOrNull { it.name == value } ?: WalletType.OTHER

    @TypeConverter fun fromCurrency(value: CurrencyCode): String = value.name
    @TypeConverter fun toCurrency(value: String): CurrencyCode =
        CurrencyCode.entries.firstOrNull { it.name == value } ?: CurrencyCode.ARS

    @TypeConverter fun fromAssetType(value: AssetType): String = value.name
    @TypeConverter fun toAssetType(value: String): AssetType =
        AssetType.entries.firstOrNull { it.name == value } ?: AssetType.OTHER

    @TypeConverter fun fromAlertType(value: AlertType): String = value.name
    @TypeConverter fun toAlertType(value: String): AlertType =
        AlertType.entries.firstOrNull { it.name == value } ?: AlertType.SYSTEM

    @TypeConverter fun fromSeverity(value: AlertSeverity): String = value.name
    @TypeConverter fun toSeverity(value: String): AlertSeverity =
        AlertSeverity.entries.firstOrNull { it.name == value } ?: AlertSeverity.INFO

    @TypeConverter fun fromMarketSource(value: MarketSource): String = value.name
    @TypeConverter fun toMarketSource(value: String): MarketSource =
        MarketSource.entries.firstOrNull { it.name == value } ?: MarketSource.MANUAL

    @TypeConverter fun fromQuoteUnit(value: QuoteUnit): String = value.name
    @TypeConverter fun toQuoteUnit(value: String): QuoteUnit =
        QuoteUnit.entries.firstOrNull { it.name == value } ?: QuoteUnit.PER_UNIT
}
