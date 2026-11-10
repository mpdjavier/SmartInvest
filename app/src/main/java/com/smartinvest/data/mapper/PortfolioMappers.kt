package com.smartinvest.data.mapper

import com.smartinvest.core.time.DateUtils
import com.smartinvest.data.local.entity.AlertEntity
import com.smartinvest.data.local.entity.FxRateEntity
import com.smartinvest.data.local.entity.HoldingEntity
import com.smartinvest.data.local.entity.InstrumentQuoteEntity
import com.smartinvest.data.local.entity.PortfolioSnapshotEntity
import com.smartinvest.data.local.entity.RateHistoryEntity
import com.smartinvest.data.local.entity.SalaryPlanEntity
import com.smartinvest.data.local.entity.WalletEntity
import com.smartinvest.domain.model.AppAlert
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.InstrumentQuote
import com.smartinvest.domain.model.PortfolioSnapshot
import com.smartinvest.domain.model.RatePoint
import com.smartinvest.domain.model.SavedSalaryPlan
import com.smartinvest.domain.model.Wallet
import java.time.YearMonth

fun WalletEntity.toDomain(): Wallet = Wallet(
    id = id,
    name = name,
    institution = institution,
    type = type,
    currency = currency,
    balanceCents = balanceCents,
    tnaPercent = tnaPercent,
    remuneratedCapCents = remuneratedCapCents,
    isActive = isActive,
    rateSource = rateSource,
    rateUpdatedAt = rateUpdatedAt,
    liquidityPriority = liquidityPriority,
    notes = notes,
    linkedHoldingId = linkedHoldingId,
)

fun Wallet.toEntity(): WalletEntity = WalletEntity(
    id = id,
    name = name,
    institution = institution,
    type = type,
    currency = currency,
    balanceCents = balanceCents,
    tnaPercent = tnaPercent,
    remuneratedCapCents = remuneratedCapCents,
    isActive = isActive,
    rateSource = rateSource,
    rateUpdatedAt = rateUpdatedAt,
    liquidityPriority = liquidityPriority,
    notes = notes,
    linkedHoldingId = linkedHoldingId,
)

fun HoldingEntity.toDomain(): Holding = Holding(
    id = id,
    symbol = symbol,
    name = name,
    assetType = assetType,
    currency = currency,
    currentValueCents = currentValueCents,
    costCents = costCents,
    tnaPercent = tnaPercent,
    maturityDate = maturityEpochDay?.let(DateUtils::epochDayToDate),
    notes = notes,
    marketKey = marketKey,
    source = source,
    quantity = quantity,
    quoteUnit = quoteUnit,
)

fun Holding.toEntity(): HoldingEntity = HoldingEntity(
    id = id,
    symbol = symbol,
    name = name,
    assetType = assetType,
    currency = currency,
    currentValueCents = currentValueCents,
    costCents = costCents,
    tnaPercent = tnaPercent,
    maturityEpochDay = maturityDate?.toEpochDay(),
    notes = notes,
    marketKey = marketKey,
    source = source,
    quantity = quantity,
    quoteUnit = quoteUnit,
)

fun InstrumentQuoteEntity.toDomain(): InstrumentQuote = InstrumentQuote(
    marketKey = marketKey,
    symbol = symbol,
    source = source,
    price = price,
    currency = currency,
    quoteUnit = quoteUnit,
    date = DateUtils.epochDayToDate(dateEpochDay),
    fetchedAt = fetchedAt,
)

fun PortfolioSnapshotEntity.toDomain(): PortfolioSnapshot = PortfolioSnapshot(
    id = id,
    date = DateUtils.epochDayToDate(dateEpochDay),
    totalArsCents = totalArsCents,
    totalUsdCents = totalUsdCents,
    arsAssetsCents = arsAssetsCents,
    usdAssetsArsCents = usdAssetsArsCents,
    dailyInterestCents = dailyInterestCents,
    createdAt = createdAt,
)

fun FxRateEntity.toDomain(): FxRate = FxRate(
    house = house,
    name = name,
    buy = buy,
    sell = sell,
    updatedAt = updatedAt,
)

fun RateHistoryEntity.toDomain(): RatePoint = RatePoint(
    date = DateUtils.epochDayToDate(dateEpochDay),
    tnaPercent = tnaPercent,
    source = source,
)

fun SalaryPlanEntity.toDomain(): SavedSalaryPlan = SavedSalaryPlan(
    id = id,
    month = YearMonth.parse(month),
    netSalaryCents = netSalaryCents,
    cushionCents = cushionCents,
    fciCents = fciCents,
    lecapCents = lecapCents,
    mepCents = mepCents,
    createdAt = createdAt,
)

fun AlertEntity.toDomain(): AppAlert = AppAlert(
    id = id,
    type = type,
    severity = severity,
    title = title,
    message = message,
    dedupeKey = dedupeKey,
    createdAt = createdAt,
    isRead = isRead,
)
