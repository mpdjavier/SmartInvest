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
import com.smartinvest.data.network.ArgentinaDatosService
import com.smartinvest.data.network.ArgentinaDolarPointDto
import com.smartinvest.data.network.DolarApiService
import com.smartinvest.data.network.MarketDataService
import com.smartinvest.data.network.MarketKeyParser
import com.smartinvest.data.network.toDomain
import com.smartinvest.data.widget.WidgetRefresher
import com.smartinvest.di.IoDispatcher
import com.smartinvest.domain.engine.PortfolioReconciliationEngine
import com.smartinvest.domain.engine.PortfolioValuationEngine
import com.smartinvest.domain.model.AllocationBucket
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.InstrumentQuote
import com.smartinvest.domain.model.MacroMarketSnapshot
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
import java.time.LocalDate
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
    private val argentinaDatos: ArgentinaDatosService,
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
                val isLocked = PortfolioReconciliationEngine.isManualValuationLocked(holding)
                val manualDate = PortfolioReconciliationEngine.manualValuationDate(holding)
                val isLockedForDate = isLocked && (manualDate == null || !result.latestDate.isAfter(manualDate))
                if (
                    holding.quantity > 0.0 &&
                    result.latestPrice > 0.0 &&
                    !isLockedForDate
                ) {
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
                .filter {
                    val isLocked = PortfolioReconciliationEngine.isManualValuationLocked(it)
                    val manualDate = PortfolioReconciliationEngine.manualValuationDate(it)
                    val isLockedForDate = isLocked && (manualDate == null || !fetched.latestDate.isAfter(manualDate))
                    MarketKeyParser.normalize(it.marketKey) == normalized &&
                        it.quantity > 0.0 &&
                        !isLockedForDate
                }
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

    override suspend fun refreshMacroMarket(force: Boolean): Result<MacroMarketSnapshot> =
        withContext(io) {
            val current = settingsRepository.currentSettings()
            val previous = current.macroMarket
            val age = System.currentTimeMillis() - previous.fetchedAtMillis
            val fresh = previous.fetchedAtMillis > 0L &&
                age in 0..MACRO_SNAPSHOT_TTL_MILLIS &&
                previous.hasAnyData
            if (!force && fresh) return@withContext Result.success(previous)

            runCatching {
                val sources = mutableListOf<String>()

                val countrySeries = runCatching { argentinaDatos.countryRiskHistory() }
                    .onSuccess { if (it.isNotEmpty()) sources += "ArgentinaDatos · riesgo país" }
                    .getOrDefault(emptyList())
                val fxSeries = listOf(
                    "MEP" to runCatching { argentinaDatos.mepHistory() },
                    "mayorista" to runCatching { argentinaDatos.wholesaleHistory() },
                    "blue" to runCatching { argentinaDatos.blueHistory() },
                    "oficial" to runCatching { argentinaDatos.officialHistory() },
                ).map { (label, result) ->
                    val series = result.getOrDefault(emptyList())
                    if (series.isNotEmpty()) sources += "ArgentinaDatos · dólar $label"
                    label to series
                }.toMap()

                val fixedTerm = runCatching { argentinaDatos.fixedTermRates() }
                    .onSuccess { if (it.isNotEmpty()) sources += "ArgentinaDatos · tasas plazo fijo (BCRA)" }
                    .getOrDefault(emptyList())

                fun latestCountryOnOrBefore(date: LocalDate) = countrySeries
                    .filter { it.fecha.isNotBlank() && it.fecha <= date.toString() }
                    .maxByOrNull { it.fecha }

                fun saleOnOrBefore(series: List<ArgentinaDolarPointDto>, date: LocalDate?): Double? {
                    val point = series
                        .filter { date == null || it.fecha <= date.toString() }
                        .maxByOrNull { it.fecha }
                    return point?.venta?.takeIf { it > 0.0 }
                }

                val countryLast = countrySeries
                    .filter { it.fecha.isNotBlank() }
                    .maxByOrNull { it.fecha }
                val countryDate = countryLast?.fecha
                val countryBase = countryDate
                    ?.let { runCatching { LocalDate.parse(it).minusDays(30) }.getOrNull() }
                    ?.let { latestCountryOnOrBefore(it) }
                val countryChange = if (countryLast != null && countryBase != null) {
                    countryLast.valor - countryBase.valor
                } else {
                    previous.countryRisk30dChange
                }

                val mepSeries = fxSeries["MEP"].orEmpty()
                val wholesaleSeries = fxSeries["mayorista"].orEmpty()
                val blueSeries = fxSeries["blue"].orEmpty()

                val mepLast = mepSeries.filter { it.fecha.isNotBlank() }.maxByOrNull { it.fecha }
                val wholesaleLast = wholesaleSeries.filter { it.fecha.isNotBlank() }.maxByOrNull { it.fecha }
                val blueLast = blueSeries.filter { it.fecha.isNotBlank() }.maxByOrNull { it.fecha }
                val fxDate = listOfNotNull(mepLast?.fecha, wholesaleLast?.fecha).minOrNull()

                val mep = saleOnOrBefore(mepSeries, null)
                val wholesale = saleOnOrBefore(wholesaleSeries, null)
                val blue = saleOnOrBefore(blueSeries, null)
                val fxGap = if (mep != null && wholesale != null && wholesale > 0.0) {
                    (mep / wholesale - 1.0) * 100.0
                } else {
                    previous.fxGapPercent
                }
                val blueGap = if (blue != null && wholesale != null && wholesale > 0.0) {
                    (blue / wholesale - 1.0) * 100.0
                } else {
                    previous.blueGapPercent
                }
                val fxGap14dAgo = fxDate
                    ?.let { runCatching { LocalDate.parse(it).minusDays(14) }.getOrNull() }
                    ?.let { target ->
                        val mep14 = saleOnOrBefore(mepSeries, target)
                        val wholesale14 = saleOnOrBefore(wholesaleSeries, target)
                        if (mep14 != null && wholesale14 != null && wholesale14 > 0.0) {
                            (mep14 / wholesale14 - 1.0) * 100.0
                        } else {
                            null
                        }
                    }
                    ?: previous.fxGap14dAgoPercent
                    ?: current.previousFxGapPercent.takeIf { it >= 0.0 }

                val rawTna = fixedTerm
                    .mapNotNull { (it.tnaClientes ?: it.tnaNoClientes)?.takeIf { value -> value > 0.0 } }
                    .sorted()
                val medianTna = when {
                    rawTna.isEmpty() -> null
                    rawTna.size % 2 == 1 -> rawTna[rawTna.size / 2]
                    else -> (rawTna[rawTna.size / 2 - 1] + rawTna[rawTna.size / 2]) / 2.0
                }
                val shortTna = medianTna?.let { if (it <= 1.0) it * 100.0 else it }

                val snapshot = MacroMarketSnapshot(
                    fetchedAtMillis = System.currentTimeMillis(),
                    countryRiskPoints = countryLast?.valor
                        ?: previous.countryRiskPoints
                        ?: current.countryRiskPoints.takeIf { it > 0 },
                    countryRiskDate = countryDate ?: previous.countryRiskDate,
                    countryRisk30dChange = countryChange,
                    fxGapPercent = fxGap,
                    fxGap14dAgoPercent = fxGap14dAgo,
                    blueGapPercent = blueGap,
                    shortTnaPercent = shortTna ?: previous.shortTnaPercent,
                    shortTnaDate = if (shortTna != null) {
                        DateUtils.today().toString()
                    } else {
                        previous.shortTnaDate
                    },
                    officialRate = saleOnOrBefore(
                        fxSeries["oficial"].orEmpty(),
                        null,
                    ) ?: previous.officialRate,
                    mepRate = mep ?: previous.mepRate,
                    wholesaleRate = wholesale ?: previous.wholesaleRate,
                    blueRate = blue ?: previous.blueRate,
                    fxDate = fxDate ?: previous.fxDate,
                    sources = sources.distinct().ifEmpty { previous.sources },
                )
                require(snapshot.hasAnyData) {
                    "Las fuentes alternativas no devolvieron datos macro"
                }
                settingsRepository.setMacroMarket(MacroMarketSnapshot.encode(snapshot))
                snapshot
            }
        }

    override suspend fun refreshSnapshot(): PortfolioValuation = withContext(io) {
        val settings = settingsRepository.currentSettings()
        val today = DateUtils.today()
        val valuation = PortfolioValuationEngine.calculate(
            wallets = walletDao.getAll().map { it.toDomain() },
            holdings = holdingDao.getAll().map { it.toDomain() },
            fxRates = fxRateDao.getAll().map { it.toDomain() },
            fallbackMepRate = settings.fallbackMepRate,
            quotes = instrumentQuoteDao.getAll().map { it.toDomain() },
            today = today,
        )
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

/** Reuse a persisted macro snapshot for 12 hours unless the user forces a refresh. */
private const val MACRO_SNAPSHOT_TTL_MILLIS = 12L * 60L * 60L * 1000L
