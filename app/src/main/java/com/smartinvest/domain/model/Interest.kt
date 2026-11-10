package com.smartinvest.domain.model

import java.time.LocalDate
import java.time.YearMonth

enum class InterestSourceKind(val label: String) {
    WALLET("Billetera"),
    MONEY_MARKET("FCI money market"),
    FCI("FCI"),
    LECAP("LECAP"),
    BOND("Bono"),
    CEDEAR("CEDEAR"),
    OTHER("Otro"),
}

/**
 * One source of daily earnings for one date.
 *
 * Exactly one basis is used:
 *  - [tnaPercent] != null -> accrual: basis × TNA ÷ 365.
 *  - [priceStart]/[priceEnd] != null -> real market variation of a tracked quote.
 */
data class InterestLine(
    val sourceId: String,
    val sourceName: String,
    val kind: InterestSourceKind,
    val currency: CurrencyCode,
    val basisCents: Long,
    val tnaPercent: Double? = null,
    val priceStart: Double? = null,
    val priceEnd: Double? = null,
    val quantity: Double = 0.0,
    val quoteUnit: QuoteUnit = QuoteUnit.PER_UNIT,
    val dailyNativeCents: Long,
    val dailyArsCents: Long,
    val explanation: String,
)

data class DailyInterestDay(
    val date: LocalDate,
    val lines: List<InterestLine>,
    val totalArsCents: Long,
)

data class InterestSourceSummary(
    val sourceId: String,
    val sourceName: String,
    val kind: InterestSourceKind,
    val totalArsCents: Long,
    val days: Int,
    val averageDailyArsCents: Long,
    val basisLabel: String,
    val accrualArsCents: Long = 0L,
    val marketArsCents: Long = 0L,
) {
    /** True when the source is valued with real quote changes instead of TNA / 365. */
    val isMarketBased: Boolean get() = basisLabel.startsWith("Cotización")
}

/**
 * Explains why "Interés / día" in Cartera does not match the accumulated interest of the month.
 *
 * [projectedMonthArsCents] is [accrualPerDayArsCents] × [daysConsidered]: the naive monthly
 * projection that the user sees when multiplying the daily tile by the days of the month.
 * [differenceVsProjectionArsCents] is what the accumulated total adds or removes on top of
 * that projection. The market variation of tracked quotes explains most of it; the remainder
 * comes from using current balances/TNA for every historical day, caps and rounding.
 */
data class InterestReconciliation(
    val daysConsidered: Int = 0,
    val accrualTotalArsCents: Long = 0L,
    val marketTotalArsCents: Long = 0L,
    val totalArsCents: Long = 0L,
    val accrualPerDayArsCents: Long = 0L,
    val projectedMonthArsCents: Long = 0L,
    val differenceVsProjectionArsCents: Long = 0L,
    val unexplainedArsCents: Long = 0L,
    val accrualSources: Int = 0,
    val marketSources: Int = 0,
    val missingMarketSources: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
) {
    val hasData: Boolean
        get() = daysConsidered > 0 || totalArsCents != 0L || accrualTotalArsCents != 0L

    val isFullyExplained: Boolean get() = unexplainedArsCents == 0L
}

data class DailyInterestBreakdown(
    val month: YearMonth,
    val days: List<DailyInterestDay>,
    val totalArsCents: Long,
    val averageDailyArsCents: Long,
    val sources: List<InterestSourceSummary>,
    val reconciliation: InterestReconciliation = InterestReconciliation(),
) {
    val hasMarketData: Boolean get() = sources.any { it.isMarketBased }

    val hasActivity: Boolean get() = totalArsCents != 0L || days.isNotEmpty()
}
