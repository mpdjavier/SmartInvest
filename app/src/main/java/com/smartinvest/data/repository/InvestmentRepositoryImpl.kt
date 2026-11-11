package com.smartinvest.data.repository

import com.smartinvest.core.time.DateUtils
import com.smartinvest.data.local.dao.FxRateDao
import com.smartinvest.data.local.dao.HoldingDao
import com.smartinvest.data.local.dao.InstrumentQuoteDao
import com.smartinvest.data.local.dao.RateHistoryDao
import com.smartinvest.data.local.dao.SalaryPlanDao
import com.smartinvest.data.local.dao.SnapshotDao
import com.smartinvest.data.local.dao.WalletDao
import com.smartinvest.data.local.entity.FxRateEntity
import com.smartinvest.data.local.entity.InstrumentQuoteEntity
import com.smartinvest.data.local.entity.PortfolioSnapshotEntity
import com.smartinvest.data.local.entity.RateHistoryEntity
import com.smartinvest.data.local.entity.SalaryPlanEntity
import com.smartinvest.data.mapper.toDomain
import com.smartinvest.data.mapper.toEntity
import com.smartinvest.data.network.DolarApiService
import com.smartinvest.data.network.MarketDataService
import com.smartinvest.data.network.MarketKeyParser
import com.smartinvest.data.network.toDomain
import com.smartinvest.data.widget.WidgetRefresher
import com.smartinvest.di.IoDispatcher
import com.smartinvest.domain.engine.PortfolioValuationEngine
import com.smartinvest.domain.model.AllocationBucket
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.InstrumentQuote
import com.smartinvest.domain.model.MarketSource
import com.smartinvest.domain.model.MarketSyncOutcome
import com.smartinvest.domain.model.PortfolioSnapshot
import com.smartinvest.domain.model.PortfolioValuation
import com.smartinvest.domain.model.QuoteUnit
import com.smartinvest.domain.model.RatePoint
import com.smartinvest.domain.model.SavedSalaryPlan
import com.smartinvest.domain.model.SalaryPlan
import com.smartinvest.domain.model.SyncOutcome
import com.smartinvest.domain.model.Wallet
import com.smartinvest.domain.repository.InvestmentRepository
import com.smartinvest.domain.repository.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InvestmentRepositoryImpl @Inject constructor(
    private val walletDao: WalletDao,
    private val holdingDao: HoldingDao,
    private val instrumentQuoteDao: InstrumentQuoteDao,
    private val snapshotDao: SnapshotDao,
    private val fxRateDao: FxRateDao,
    private val rateHistoryDao: RateHistoryDao,
    private val salaryPlanDao: SalaryPlanDao,
    private val rateSyncService: RateSyncService,
    private val marketDataService: MarketDataService,
    private val dolarApi: DolarApiService,
    private val settingsRepository: SettingsRepository,
    private val widgetRefresher: WidgetRefresher,
    @IoDispatcher private val io: CoroutineDispatcher,
) : InvestmentRepository {

    override fun observeWallets(): Flow<List<Wallet>> =
        walletDao.observeAll().map { list -> list.map { it.toDomain() } }.flowOn(io)

    override fun observeActiveWallets(): Flow<List<Wallet>> =
        walletDao.observeActive().map { list -> list.map { it.toDomain() } }.flowOn(io)

    override fun observeHoldings(): Flow<List<Holding>> =
        holdingDao.observeAll().map { list -> list.map { it.toDomain() } }.flowOn(io)

    override fun observeWalletHoldings(): Flow<List<Holding>> =
        holdingDao.observeWallets().map { list -> list.map { it.toDomain() } }.flowOn(io)

    override fun observeQuotes(marketKey: String): Flow<List<InstrumentQuote>> =
        instrumentQuoteDao.observeForMarket(marketKey).map { list -> list.map { it.toDomain() } }.flowOn(io)

    override fun observeAllQuotes(): Flow<List<InstrumentQuote>> =
        instrumentQuoteDao.observeAll().map { list -> list.map { it.toDomain() } }.flowOn(io)

    override fun observeSnapshots(): Flow<List<PortfolioSnapshot>> =
        snapshotDao.observeAll().map { list -> list.map { it.toDomain() } }.flowOn(io)

    override fun observeFxRates(): Flow<List<FxRate>> =
        fxRateDao.observeAll().map { list -> list.map { it.toDomain() } }.flowOn(io)

    override fun observeRateHistory(walletId: Long): Flow<List<RatePoint>> =
        rateHistoryDao.observeForWallet(walletId).map { list -> list.map { it.toDomain() } }.flowOn(io)

    override fun observeSalaryPlans(): Flow<List<SavedSalaryPlan>> =
        salaryPlanDao.observeAll().map { list -> list.map { it.toDomain() } }.flowOn(io)

    override suspend fun upsertWallet(wallet: Wallet): Long {
        val id = withContext(io) {
            require(wallet.name.isNotBlank()) { "La cuenta necesita un nombre" }
            require(wallet.balanceCents >= 0) { "El saldo no puede ser negativo" }
            require(wallet.tnaPercent >= 0.0 && wallet.tnaPercent <= 500.0) { "TNA inválida" }
            require(wallet.remuneratedCapCents >= 0) { "El tope no puede ser negativo" }

            val existing = if (wallet.id > 0) walletDao.getById(wallet.id) else null
            val rateChanged = existing == null || abs(existing.tnaPercent - wallet.tnaPercent) >= 0.0001
            val now = System.currentTimeMillis()
            val entity = wallet.copy(
                rateSource = if (rateChanged) "manual" else (existing?.rateSource ?: wallet.rateSource),
                rateUpdatedAt = if (rateChanged) now else (existing?.rateUpdatedAt ?: wallet.rateUpdatedAt),
            ).toEntity()

            val savedId = walletDao.upsert(entity)
            if (rateChanged && wallet.tnaPercent > 0) {
                rateHistoryDao.upsert(
                    RateHistoryEntity(
                        walletId = savedId,
                        dateEpochDay = DateUtils.today().toEpochDay(),
                        tnaPercent = wallet.tnaPercent,
                        source = "manual",
                    ),
                )
            }
            savedId
        }
        widgetRefresher.refresh()
        return id
    }

    override suspend fun setWalletActive(id: Long, active: Boolean) {
        withContext(io) { walletDao.setActive(id, active) }
        widgetRefresher.refresh()
    }

    override suspend fun updateWalletRate(walletId: Long, tnaPercent: Double, source: String) {
        withContext(io) {
            require(tnaPercent >= 0.0) { "TNA inválida" }
            walletDao.updateRate(walletId, tnaPercent, source, System.currentTimeMillis())
            rateHistoryDao.upsert(
                RateHistoryEntity(
                    walletId = walletId,
                    dateEpochDay = DateUtils.today().toEpochDay(),
                    tnaPercent = tnaPercent,
                    source = source,
                ),
            )
        }
        widgetRefresher.refresh()
    }

    override suspend fun upsertHolding(holding: Holding): Long {
        val id = withContext(io) {
            require(holding.symbol.isNotBlank()) { "El ticker no puede estar vacío" }
            require(holding.currentValueCents >= 0) { "La valuación no puede ser negativa" }
            require(holding.costCents >= 0) { "El costo no puede ser negativo" }
            require(holding.quantity >= 0.0) { "La cantidad no puede ser negativa" }
            require(holding.settlementDays >= 0) { "El plazo de rescate no puede ser negativo" }
            require(!holding.isWallet || holding.assetType == AssetType.FCI) {
                "Solo un FCI puede marcarse como billetera"
            }

            val normalizedKey = MarketKeyParser.normalize(holding.marketKey)
            val resolvedSource = if (normalizedKey.isNotBlank()) {
                MarketKeyParser.sourceFor(normalizedKey)
            } else {
                MarketSource.MANUAL
            }
            var adjusted = holding.copy(
                marketKey = normalizedKey,
                source = resolvedSource,
            )
            if (adjusted.quantity > 0.0 && adjusted.currentValueCents == 0L && normalizedKey.isNotBlank()) {
                val latest = instrumentQuoteDao.latestForMarket(normalizedKey)
                if (latest != null && latest.price > 0.0) {
                    adjusted = adjusted.copy(currentValueCents = adjusted.valueCentsFor(latest.price))
                }
            }
            if (adjusted.isWallet) {
                holdingDao.clearWalletFlags(adjusted.id)
            }
            holdingDao.upsert(adjusted.toEntity())
        }
        widgetRefresher.refresh()
        return id
    }

    override suspend fun deleteHolding(id: Long) {
        withContext(io) { holdingDao.deleteById(id) }
        widgetRefresher.refresh()
    }

    override suspend fun syncRates(): SyncOutcome {
        val outcome = rateSyncService.sync()
        widgetRefresher.refresh()
        return outcome
    }

    override suspend fun syncMarketQuotes(): MarketSyncOutcome = withContext(io) {
        val now = System.currentTimeMillis()
        val today = DateUtils.today()
        val tracked = holdingDao.getAll()
            .map { it.toDomain() }
            .filter { it.marketKey.isNotBlank() || it.source != MarketSource.MANUAL }

        if (tracked.isEmpty()) {
            return@withContext MarketSyncOutcome(
                updatedHoldings = 0,
                failedHoldings = 0,
                storedQuotes = 0,
                message = "No hay instrumentos con seguimiento automático configurado.",
                atMillis = now,
            )
        }

        var updated = 0
        var failed = 0
        var storedQuotes = 0

        tracked.forEach { holding ->
            val result = runCatching { marketDataService.fetchForHolding(holding, today) }.getOrNull()
            if (result == null || result.history.isEmpty()) {
                failed++
            } else {
                val effectiveKey = holding.marketKey.ifBlank { result.marketKey }
                val entities = result.history.map { point ->
                    InstrumentQuoteEntity(
                        marketKey = effectiveKey,
                        symbol = holding.symbol.ifBlank { result.symbol },
                        source = result.source,
                        price = point.price,
                        currency = holding.currency,
                        quoteUnit = holding.quoteUnit,
                        dateEpochDay = point.date.toEpochDay(),
                        fetchedAt = now,
                    )
                }
                instrumentQuoteDao.upsertAll(entities)
                instrumentQuoteDao.deleteForMarketAfter(effectiveKey, result.latestDate.toEpochDay())
                storedQuotes += entities.size
                if (holding.quantity > 0.0 && result.latestPrice > 0.0) {
                    val newValue = holding.valueCentsFor(result.latestPrice)
                    holdingDao.updateValuation(holding.id, newValue, result.name)
                }
                updated++
            }
        }

        instrumentQuoteDao.deleteOlderThan(today.minusDays(730).toEpochDay())
        widgetRefresher.refresh()

        val msg = buildString {
            append("Cotizaciones actualizadas: $updated")
            if (storedQuotes > 0) append(" ($storedQuotes cierres)")
            if (failed > 0) append(" · $failed con error")
        }
        MarketSyncOutcome(
            updatedHoldings = updated,
            failedHoldings = failed,
            storedQuotes = storedQuotes,
            message = msg,
            atMillis = now,
        )
    }

    override suspend fun syncInstrument(
        marketKey: String,
        symbol: String,
        currency: CurrencyCode,
        quoteUnit: QuoteUnit,
    ): Result<Int> = withContext(io) {
        runCatching {
            val normalized = MarketKeyParser.normalize(marketKey)
            require(normalized.isNotBlank()) { "Clave de mercado vacía" }
            val probe = Holding(
                id = 0,
                symbol = symbol,
                name = symbol,
                assetType = AssetType.OTHER,
                currency = currency,
                currentValueCents = 0,
                costCents = 0,
                tnaPercent = null,
                maturityDate = null,
                notes = "",
                marketKey = normalized,
                source = MarketKeyParser.sourceFor(normalized),
                quantity = 0.0,
                quoteUnit = quoteUnit,
            )
            val fetched = marketDataService.fetchForHolding(probe, DateUtils.today())
                ?: error("Fuente no soportada: $normalized")
            val now = System.currentTimeMillis()
            val entities = fetched.history.map { point ->
                InstrumentQuoteEntity(
                    marketKey = normalized,
                    symbol = symbol.ifBlank { fetched.symbol },
                    source = fetched.source,
                    price = point.price,
                    currency = currency,
                    quoteUnit = quoteUnit,
                    dateEpochDay = point.date.toEpochDay(),
                    fetchedAt = now,
                )
            }
            instrumentQuoteDao.upsertAll(entities)
            instrumentQuoteDao.deleteForMarketAfter(normalized, fetched.latestDate.toEpochDay())
            // Also update any matching holdings in portfolio.
            holdingDao.getAll()
                .map { it.toDomain() }
                .filter { MarketKeyParser.normalize(it.marketKey) == normalized && it.quantity > 0.0 }
                .forEach { h ->
                    holdingDao.updateValuation(h.id, h.valueCentsFor(fetched.latestPrice), fetched.name)
                }
            widgetRefresher.refresh()
            entities.size
        }
    }

    override suspend fun refreshDollarRates(): Result<Unit> = withContext(io) {
        runCatching {
            val rates = dolarApi.getDollarRates()
            require(rates.isNotEmpty()) { "DolarAPI devolvió una lista vacía" }
            val entities = rates.map { dto ->
                val domain = dto.toDomain()
                FxRateEntity(
                    house = domain.house,
                    name = domain.name,
                    buy = domain.buy,
                    sell = domain.sell,
                    updatedAt = domain.updatedAt,
                )
            }
            fxRateDao.upsertAll(entities)
            widgetRefresher.refresh()
        }
    }

    override suspend fun refreshSnapshot(): PortfolioValuation = withContext(io) {
        val settings = settingsRepository.currentSettings()
        val valuation = PortfolioValuationEngine.calculate(
            wallets = walletDao.getAll().map { it.toDomain() },
            holdings = holdingDao.getAll().map { it.toDomain() },
            fxRates = fxRateDao.getAll().map { it.toDomain() },
            fallbackMepRate = settings.fallbackMepRate,
            quotes = instrumentQuoteDao.getAll().map { it.toDomain() },
        )
        val today = DateUtils.today()
        snapshotDao.upsert(
            PortfolioSnapshotEntity(
                dateEpochDay = today.toEpochDay(),
                totalArsCents = valuation.totalArsCents,
                totalUsdCents = valuation.totalUsdCents,
                arsAssetsCents = valuation.arsAssetsCents,
                usdAssetsArsCents = valuation.usdAssetsArsCents,
                dailyInterestCents = valuation.dailyInterestCents,
                createdAt = System.currentTimeMillis(),
            ),
        )
        snapshotDao.deleteOlderThan(today.minusDays(730).toEpochDay())
        widgetRefresher.refresh()
        valuation
    }

    override suspend fun saveSalaryPlan(plan: SalaryPlan) {
        withContext(io) {
            salaryPlanDao.upsert(
                SalaryPlanEntity(
                    month = plan.month.toString(),
                    netSalaryCents = plan.netSalaryCents,
                    cushionCents = plan.householdCushionCents,
                    fciCents = plan.allocations.firstOrNull { it.bucket == AllocationBucket.CARRY_FCI }?.amountCents ?: 0,
                    lecapCents = plan.allocations.firstOrNull { it.bucket == AllocationBucket.FIXED_RATE }?.amountCents ?: 0,
                    mepCents = plan.allocations.firstOrNull { it.bucket == AllocationBucket.MEP }?.amountCents ?: 0,
                    bondCents = plan.allocations.firstOrNull { it.bucket == AllocationBucket.SOVEREIGN_BOND }?.amountCents ?: 0,
                    liquidityCents = plan.allocations.firstOrNull { it.bucket == AllocationBucket.LIQUIDITY }?.amountCents ?: 0,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }
}
