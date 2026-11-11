package com.smartinvest.widget

import com.smartinvest.core.money.MoneyFormatter
import com.smartinvest.core.time.DateUtils
import com.smartinvest.data.local.dao.ExpenseDao
import com.smartinvest.data.local.dao.FundMovementDao
import com.smartinvest.data.local.dao.FxRateDao
import com.smartinvest.data.local.dao.HoldingDao
import com.smartinvest.data.local.dao.InstrumentQuoteDao
import com.smartinvest.data.local.dao.MemberDao
import com.smartinvest.data.local.dao.WalletDao
import com.smartinvest.data.mapper.toDomain
import com.smartinvest.data.mapper.toMonthlyItem
import com.smartinvest.domain.engine.PortfolioValuationEngine
import com.smartinvest.domain.engine.SettlementCalculator
import com.smartinvest.domain.repository.SettingsRepository
import com.smartinvest.ui.util.MoneyVisibility
import kotlinx.coroutines.flow.first
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Singleton

data class WidgetData(
    val totalArsCents: Long,
    val totalUsdCents: Long,
    val dailyInterestCents: Long,
    val mepRate: Double,
    val mepEstimated: Boolean,
    val installmentsCents: Long,
    val installmentsCount: Int,
    val householdStatus: String,
    val updatedAtMillis: Long,
    val hideAmounts: Boolean = false,
)

@Singleton
class WidgetDataLoader @Inject constructor(
    private val walletDao: WalletDao,
    private val holdingDao: HoldingDao,
    private val instrumentQuoteDao: InstrumentQuoteDao,
    private val fxRateDao: FxRateDao,
    private val memberDao: MemberDao,
    private val expenseDao: ExpenseDao,
    private val fundDao: FundMovementDao,
    private val settings: SettingsRepository,
) {

    suspend fun load(): WidgetData {
        val today = DateUtils.today()
        val month = YearMonth.from(today)
        val currentSettings = settings.currentSettings()

        val wallets = walletDao.getAll().map { it.toDomain() }
        val holdings = holdingDao.getAll().map { it.toDomain() }
        val fxRates = fxRateDao.getAll().map { it.toDomain() }
        val quotes = instrumentQuoteDao.getAll().map { it.toDomain() }
        val valuation = PortfolioValuationEngine.calculate(
            wallets = wallets,
            holdings = holdings,
            fxRates = fxRates,
            fallbackMepRate = currentSettings.fallbackMepRate,
            quotes = quotes,
        )

        val members = memberDao.observeAll().first().map { it.toDomain() }
        val cash = expenseDao.observeCashForMonth(month.toString()).first().map { it.toMonthlyItem(month) }
        val installments = expenseDao.observeInstallmentRows(month.toString()).first().map { it.toMonthlyItem() }
        val movements = fundDao.observeForMonth(month.toString()).first().map { it.toDomain() }
        val balance = fundDao.observeBalance().first()

        val summary = SettlementCalculator.calculate(
            month = month,
            members = members,
            expenses = cash + installments,
            fundMovements = movements,
            fundBalanceCents = balance,
        )

        val household = when {
            !summary.hasActivity -> "Hogar: sin movimientos este mes · Fondo ${money(balance, currentSettings.hideAmounts)}"
            summary.mainTransfer != null -> {
                val transfer = summary.mainTransfer!!
                "${transfer.fromName} → ${transfer.toName}: ${money(transfer.amountCents, currentSettings.hideAmounts)} para empatar"
            }
            else -> "Hogar: cuentas equilibradas · Fondo ${money(balance, currentSettings.hideAmounts)}"
        }

        return WidgetData(
            totalArsCents = valuation.totalArsCents,
            totalUsdCents = valuation.totalUsdCents,
            dailyInterestCents = valuation.dailyInterestCents,
            mepRate = valuation.mepRate,
            mepEstimated = valuation.mepIsEstimated,
            installmentsCents = summary.installmentSpendCents,
            installmentsCount = summary.installmentCount,
            householdStatus = household,
            updatedAtMillis = System.currentTimeMillis(),
            hideAmounts = currentSettings.hideAmounts,
        )
    }

    private fun money(cents: Long, hidden: Boolean): String =
        if (hidden) "$ ${MoneyVisibility.MASK}" else MoneyFormatter.ars(cents)
}
