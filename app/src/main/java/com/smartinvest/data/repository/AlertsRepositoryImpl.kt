package com.smartinvest.data.repository

import com.smartinvest.core.money.MoneyFormatter
import com.smartinvest.core.time.DateUtils
import com.smartinvest.data.local.dao.AlertDao
import com.smartinvest.data.local.dao.CreditCardDao
import com.smartinvest.data.local.dao.FundMovementDao
import com.smartinvest.data.local.dao.FxRateDao
import com.smartinvest.data.local.dao.HoldingDao
import com.smartinvest.data.local.dao.RateHistoryDao
import com.smartinvest.data.local.dao.WalletDao
import com.smartinvest.data.local.entity.AlertEntity
import com.smartinvest.data.mapper.toDomain
import com.smartinvest.di.IoDispatcher
import com.smartinvest.domain.engine.CardCycleResolver
import com.smartinvest.domain.engine.PortfolioValuationEngine
import com.smartinvest.domain.engine.RebalanceAdvisor
import com.smartinvest.domain.model.AlertSeverity
import com.smartinvest.domain.model.AlertType
import com.smartinvest.domain.model.AppAlert
import com.smartinvest.domain.model.NewAlert
import com.smartinvest.domain.repository.AlertsRepository
import com.smartinvest.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

@Singleton
class AlertsRepositoryImpl @Inject constructor(
    private val alertDao: AlertDao,
    private val walletDao: WalletDao,
    private val holdingDao: HoldingDao,
    private val fxRateDao: FxRateDao,
    private val rateHistoryDao: RateHistoryDao,
    private val cardDao: CreditCardDao,
    private val fundDao: FundMovementDao,
    private val settingsRepository: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : AlertsRepository {

    override fun observeAlerts(): Flow<List<AppAlert>> =
        alertDao.observeAll().map { list -> list.map { it.toDomain() } }.flowOn(io)

    override fun observeUnreadCount(): Flow<Int> = alertDao.observeUnreadCount().flowOn(io)

    override suspend fun markRead(id: Long) = withContext(io) { alertDao.markRead(id) }

    override suspend fun markAllRead() = withContext(io) { alertDao.markAllRead() }

    override suspend fun insertIfNew(alert: NewAlert): Boolean = withContext(io) {
        createAlert(alert) != null
    }

    override suspend fun pruneOlderThan(days: Long) = withContext(io) {
        alertDao.prune(System.currentTimeMillis() - days * 86_400_000L)
    }

    override suspend fun scanAlerts(): List<AppAlert> = withContext(io) {
        val settings = settingsRepository.currentSettings()
        val today = DateUtils.today()
        val now = System.currentTimeMillis()
        val wallets = walletDao.getAll().filter { it.isActive }.map { it.toDomain() }
        val holdings = holdingDao.getAll().map { it.toDomain() }
        val fxRates = fxRateDao.getAll().map { it.toDomain() }
        val (mep, _) = PortfolioValuationEngine.mepRate(fxRates, settings.fallbackMepRate)
        val created = mutableListOf<AppAlert>()

        // 1. Rate drops
        wallets.forEach { wallet ->
            val history = rateHistoryDao.latestTwo(wallet.id)
            if (history.size >= 2) {
                val latest = history[0]
                val previous = history[1]
                val drop = previous.tnaPercent - latest.tnaPercent
                if (drop >= settings.rateDropThresholdPp && drop > 0.0) {
                    createAlert(
                        NewAlert(
                            type = AlertType.RATE_DROP,
                            severity = AlertSeverity.WARNING,
                            title = "Baja de tasa en ${wallet.name}",
                            message = "Pasó de ${fmtRate(previous.tnaPercent)} a ${fmtRate(latest.tnaPercent)} TNA " +
                                "(${fmtRate(drop)} pp). Revisá si conviene mover el saldo.",
                            dedupeKey = "rate_drop:${wallet.id}:${latest.dateEpochDay}",
                        ),
                    )?.let(created::add)
                }
            }
        }

        // 2. Maturities (LECAP/BONCAP/bonds)
        holdings.forEach { holding ->
            val maturity = holding.maturityDate ?: return@forEach
            val days = DateUtils.daysBetween(today, maturity)
            if (days in -1..settings.maturityAlertDays.toLong()) {
                val whenText = when {
                    days < 0 -> "venció ayer"
                    days == 0L -> "vence hoy"
                    else -> "vence en $days días"
                }
                createAlert(
                    NewAlert(
                        type = AlertType.MATURITY,
                        severity = if (days <= 7) AlertSeverity.WARNING else AlertSeverity.INFO,
                        title = "${holding.symbol} $whenText",
                        message = "${holding.name} (${DateUtils.longDateLabel(maturity)}). " +
                            "Valor actual ${MoneyFormatter.ars(holding.currentValueCents)}. Planificá la reinversión.",
                        dedupeKey = "maturity:${holding.id}:${maturity}",
                    ),
                )?.let(created::add)
            }
        }

        // 3. AO27D critical USD threshold (bank bonus)
        val ao27 = holdings.filter {
            holding ->
            holding.symbol.uppercase().contains("AO27") || holding.name.uppercase().contains("AO27")
        }
        if (ao27.isNotEmpty()) {
            val usdCents = ao27.sumOf { holding ->
                if (holding.currency == com.smartinvest.domain.model.CurrencyCode.USD) {
                    holding.currentValueCents
                } else {
                    (holding.currentValueCents / mep).roundToLong()
                }
            }
            if (usdCents < settings.ao27dThresholdUsdCents) {
                createAlert(
                    NewAlert(
                        type = AlertType.AO27D_THRESHOLD,
                        severity = AlertSeverity.CRITICAL,
                        title = "AO27D debajo de USD ${settings.ao27dThresholdUsdCents / 100}",
                        message = "Tenés ${MoneyFormatter.usd(usdCents)} en AO27D. " +
                            "Por debajo del umbral podrías perder la bonificación bancaria.",
                        dedupeKey = "ao27d:${weekKey(today)}",
                    ),
                )?.let(created::add)
            }
        }

        // 4. Elastic rebalance band
        val valuation = PortfolioValuationEngine.calculate(
            wallets = wallets,
            holdings = holdings,
            fxRates = fxRates,
            fallbackMepRate = settings.fallbackMepRate,
        )
        val advice = RebalanceAdvisor.analyze(valuation, settings)
        if (advice.isOutOfBand) {
            createAlert(
                NewAlert(
                    type = AlertType.REBALANCE,
                    severity = AlertSeverity.WARNING,
                    title = "Rebalanceo de cartera",
                    message = advice.suggestedAction,
                    dedupeKey = "rebalance:${weekKey(today)}",
                ),
            )?.let(created::add)
        }

        // 5. Card due dates
        cardDao.getAll().filter { it.isActive }.forEach { card ->
            val due = CardCycleResolver.nextDueDate(today, card.dueDay)
            val days = DateUtils.daysBetween(today, due)
            if (days in 0..5) {
                createAlert(
                    NewAlert(
                        type = AlertType.CARD_DUE,
                        severity = if (days <= 2) AlertSeverity.WARNING else AlertSeverity.INFO,
                        title = "${card.name} vence ${if (days == 0L) "hoy" else "en $days días"}",
                        message = "Vencimiento ${DateUtils.longDateLabel(due)} · " +
                            "cierre día ${card.closingDay}. Evitá intereses.",
                        dedupeKey = "card_due:${card.id}:$due",
                    ),
                )?.let(created::add)
            }
        }

        // 6. Negative common fund
        val fundBalance = fundDao.currentBalance()
        if (fundBalance < 0) {
            createAlert(
                NewAlert(
                    type = AlertType.FUND_LOW,
                    severity = AlertSeverity.CRITICAL,
                    title = "Fondo común en negativo",
                    message = "El fondo quedó en ${MoneyFormatter.ars(fundBalance)}. Reponelo o revisá los movimientos.",
                    dedupeKey = "fund_negative:${YearMonth.from(today)}",
                ),
            )?.let(created::add)
        }

        alertDao.prune(now - 90L * 86_400_000L)
        created
    }

    private suspend fun createAlert(alert: NewAlert): AppAlert? {
        val entity = AlertEntity(
            type = alert.type,
            severity = alert.severity,
            title = alert.title,
            message = alert.message,
            dedupeKey = alert.dedupeKey,
            createdAt = System.currentTimeMillis(),
        )
        val id = alertDao.insert(entity)
        return if (id > 0) entity.copy(id = id).toDomain() else null
    }

    private fun fmtRate(value: Double): String =
        String.format(java.util.Locale.forLanguageTag("es-AR"), "%.2f%%", value)

    private fun weekKey(date: LocalDate): String =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
}
