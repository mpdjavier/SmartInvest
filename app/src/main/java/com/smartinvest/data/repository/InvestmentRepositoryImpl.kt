package com.smartinvest.data.repository

import com.smartinvest.core.time.DateUtils
import com.smartinvest.data.local.dao.ExpenseDao
import com.smartinvest.data.local.dao.FxRateDao
import com.smartinvest.data.local.dao.HoldingDao
import com.smartinvest.data.local.dao.HoldingMovementDao
import com.smartinvest.data.local.dao.InstrumentQuoteDao
import com.smartinvest.data.local.dao.RateHistoryDao
import com.smartinvest.data.local.dao.SalaryPlanDao
import com.smartinvest.data.local.dao.SnapshotDao
import com.smartinvest.data.local.dao.WalletDao
import com.smartinvest.data.local.entity.FxRateEntity
import com.smartinvest.data.local.entity.HoldingMovementEntity
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
import com.smartinvest.data.network.RateScraper
import com.smartinvest.data.network.toDomain
import com.smartinvest.data.widget.WidgetRefresher
import com.smartinvest.di.IoDispatcher
import com.smartinvest.domain.engine.BrokerMovementImporter
import com.smartinvest.domain.engine.HoldingMovementEngine
import com.smartinvest.domain.engine.PortfolioReconciliationEngine
import com.smartinvest.domain.engine.PortfolioValuationEngine
import com.smartinvest.domain.model.AllocationBucket
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.ComparaTasasCatalog
import com.smartinvest.domain.model.ComparaTasasConfig
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.FxRate
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.HoldingMovement
import com.smartinvest.domain.model.HoldingMovementType
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
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.abs
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InvestmentRepositoryImpl @Inject constructor(
    private val walletDao: WalletDao,
    private val holdingDao: HoldingDao,
    private val holdingMovementDao: HoldingMovementDao,
    private val expenseDao: ExpenseDao,
    private val instrumentQuoteDao: InstrumentQuoteDao,
    private val snapshotDao: SnapshotDao,
    private val fxRateDao: FxRateDao,
    private val rateHistoryDao: RateHistoryDao,
    private val salaryPlanDao: SalaryPlanDao,
    private val rateSyncService: RateSyncService,
    private val rateScraper: RateScraper,
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

    override fun observeHoldingMovements(holdingId: Long): Flow<List<HoldingMovement>> =
        holdingMovementDao.observeForHolding(holdingId)
            .onStart { ensureHoldingMovementsSeeded(holdingId) }
            .map { list -> list.map { it.toDomain() } }
            .flowOn(io)

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
                adjusted = adjusted.copy(costCents = adjusted.currentValueCents)
                holdingDao.clearWalletFlags(adjusted.id)
            }
            holdingDao.upsert(adjusted.toEntity())
        }
        widgetRefresher.refresh()
        return id
    }

    override suspend fun deleteHolding(id: Long) {
        withContext(io) {
            holdingMovementDao.deleteForHolding(id)
            holdingDao.deleteById(id)
        }
        widgetRefresher.refresh()
    }

    override suspend fun addHoldingMovement(
        holdingId: Long,
        type: HoldingMovementType,
        amountCents: Long,
        date: LocalDate,
        note: String,
    ): Long {
        val id = withContext(io) {
            require(amountCents > 0L) { "El monto del movimiento debe ser mayor a cero" }
            ensureHoldingMovementsSeeded(holdingId)
            val holding = holdingDao.getById(holdingId)?.toDomain()
                ?: error("El activo ya no existe")

            val effect = HoldingMovementEngine.apply(holding, type, amountCents)
            val qtyDelta = (effect.quantity - holding.quantity).takeIf { abs(it) > 1e-6 }
            val signedCents = if (type == HoldingMovementType.INGRESO) amountCents else -amountCents
            val noteLine = BrokerMovementImporter.formatMovementNoteLine(
                date = date,
                signedAmountCents = signedCents,
                currency = holding.currency,
                note = note.trim(),
                quantityDelta = qtyDelta,
            )
            val updatedNotes = if (holding.notes.isBlank()) {
                noteLine
            } else {
                "${holding.notes.trim()}\n$noteLine"
            }

            val now = System.currentTimeMillis()
            val movementId = holdingMovementDao.insert(
                HoldingMovementEntity(
                    holdingId = holdingId,
                    type = type,
                    amountCents = amountCents,
                    currency = holding.currency,
                    dateEpochDay = date.toEpochDay(),
                    note = note.trim(),
                    createdAt = now,
                ),
            )
            holdingDao.upsert(
                holding.copy(
                    currentValueCents = effect.valueCents,
                    costCents = effect.costCents,
                    quantity = effect.quantity,
                    notes = updatedNotes,
                ).toEntity(),
            )
            movementId
        }
        widgetRefresher.refresh()
        return id
    }

    override suspend fun deleteHoldingMovement(id: Long) {
        withContext(io) {
            val movement = holdingMovementDao.getById(id) ?: return@withContext
            val holding = holdingDao.getById(movement.holdingId)?.toDomain()
            holdingMovementDao.deleteById(id)
            if (holding != null) {
                val effect = HoldingMovementEngine.apply(
                    holding = holding,
                    type = movement.type,
                    amountCents = movement.amountCents,
                    reverse = true,
                )
                val remaining = holdingMovementDao.getAllForHolding(movement.holdingId)
                    .sortedBy { it.dateEpochDay }
                val nonMovementLines = holding.notes.lineSequence()
                    .filter { line -> BrokerMovementImporter.parseMovementNotes(line).isEmpty() }
                    .joinToString("\n")
                    .trim()
                val movementBlock = remaining.joinToString("\n") { entity ->
                    val signed = if (entity.type == HoldingMovementType.INGRESO) {
                        entity.amountCents
                    } else {
                        -entity.amountCents
                    }
                    BrokerMovementImporter.formatMovementNoteLine(
                        date = DateUtils.epochDayToDate(entity.dateEpochDay),
                        signedAmountCents = signed,
                        currency = entity.currency,
                        note = entity.note,
                    )
                }
                val rebuiltNotes = listOf(nonMovementLines, movementBlock)
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                holdingDao.upsert(
                    holding.copy(
                        currentValueCents = effect.valueCents,
                        costCents = effect.costCents,
                        quantity = effect.quantity,
                        notes = rebuiltNotes,
                    ).toEntity(),
                )
            }
        }
        widgetRefresher.refresh()
    }

    /**
     * Populates `holding_movements` on first access from the movement lines
     * already stored in `Holding.notes` (or from the broker preset movements
     * when `notes` has no movement lines yet).
     */
    private suspend fun ensureHoldingMovementsSeeded(holdingId: Long) {
        HoldingMovementSeeder.ensureSeeded(holdingId, holdingDao, holdingMovementDao, expenseDao)
    }

    override suspend fun syncRates(): SyncOutcome {
        val outcome = rateSyncService.sync()
        runCatching { refreshComparaTasasCatalog() }
        widgetRefresher.refresh()
        return outcome
    }

    override suspend fun refreshComparaTasasCatalog(): Result<ComparaTasasCatalog> = withContext(io) {
        runCatching {
            val catalog = rateScraper.fetchCatalog()
            val currentSettings = settingsRepository.currentSettings()
            val updatedConfig = currentSettings.comparaTasasConfig.copy(catalog = catalog)
            settingsRepository.update(
                currentSettings.copy(comparaTasasConfigJson = ComparaTasasConfig.encode(updatedConfig)),
            )
            catalog
        }
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
                instrumentQuoteDao.deleteForMarket(effectiveKey)
                instrumentQuoteDao.upsertAll(entities)
                instrumentQuoteDao.deleteForMarketAfter(effectiveKey, result.latestDate.toEpochDay())
                storedQuotes += entities.size
                val isLocked = PortfolioReconciliationEngine.isManualValuationLocked(holding)
                val manualDate = PortfolioReconciliationEngine.manualValuationDate(holding)
                val isLockedForDate = isLocked && (manualDate == null || !result.latestDate.isAfter(manualDate))
                val isRecentEnoughToHeal = manualDate == null || !result.latestDate.isBefore(manualDate.minusDays(7))
                if (holding.quantity > 0.0 && result.latestPrice > 0.0) {
                    val newValue = holding.valueCentsFor(result.latestPrice)
                    val isCorrupted = isRecentEnoughToHeal && PortfolioValuationEngine.isCorruptedValuation(
                        holding.currentValueCents,
                        newValue,
                    )
                    if (!isLockedForDate || isCorrupted) {
                        holdingDao.updateValuation(holding.id, newValue, result.name)
                    }
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
            instrumentQuoteDao.deleteForMarket(normalized)
            instrumentQuoteDao.upsertAll(entities)
            instrumentQuoteDao.deleteForMarketAfter(normalized, fetched.latestDate.toEpochDay())
            // Also update any matching holdings in portfolio.
            holdingDao.getAll()
                .map { it.toDomain() }
                .filter {
                    val isLocked = PortfolioReconciliationEngine.isManualValuationLocked(it)
                    val manualDate = PortfolioReconciliationEngine.manualValuationDate(it)
                    val isLockedForDate = isLocked && (manualDate == null || !fetched.latestDate.isAfter(manualDate))
                    val expectedValue = if (it.quantity > 0.0 && fetched.latestPrice > 0.0) {
                        it.valueCentsFor(fetched.latestPrice)
                    } else {
                        0L
                    }
                    val isCorrupted = PortfolioValuationEngine.isCorruptedValuation(
                        it.currentValueCents,
                        expectedValue,
                    )
                    MarketKeyParser.normalize(it.marketKey) == normalized &&
                        it.quantity > 0.0 &&
                        (!isLockedForDate || isCorrupted)
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
            val existingByHouse = fxRateDao.getAll().associateBy { it.house }
            val entities = rates.mapNotNull { dto ->
                val domain = dto.toDomain()
                val prev = existingByHouse[domain.house]
                val sell = domain.sell.takeIf { it > 0.0 } ?: prev?.sell ?: 0.0
                if (sell <= 0.0) return@mapNotNull null
                val buy = domain.buy.takeIf { it > 0.0 } ?: prev?.buy ?: sell
                FxRateEntity(
                    house = domain.house,
                    name = domain.name,
                    buy = buy,
                    sell = sell,
                    updatedAt = domain.updatedAt,
                )
            }
            if (entities.isNotEmpty()) {
                fxRateDao.upsertAll(entities)
            }
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
                    .filter { it.fecha.isNotBlank() && it.valor > 0 && it.fecha <= date.toString() }
                    .maxByOrNull { it.fecha }

                fun saleOnOrBefore(series: List<ArgentinaDolarPointDto>, date: LocalDate?): Double? {
                    val point = series
                        .filter {
                            it.fecha.isNotBlank() &&
                                (it.venta ?: 0.0) > 0.0 &&
                                (date == null || it.fecha <= date.toString())
                        }
                        .maxByOrNull { it.fecha }
                    return point?.venta?.takeIf { it > 0.0 }
                }

                val countryLast = countrySeries
                    .filter { it.fecha.isNotBlank() && it.valor > 0 }
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

                val mepLast = mepSeries.filter { it.fecha.isNotBlank() && (it.venta ?: 0.0) > 0.0 }.maxByOrNull { it.fecha }
                val wholesaleLast = wholesaleSeries.filter { it.fecha.isNotBlank() && (it.venta ?: 0.0) > 0.0 }.maxByOrNull { it.fecha }
                val blueLast = blueSeries.filter { it.fecha.isNotBlank() && (it.venta ?: 0.0) > 0.0 }.maxByOrNull { it.fecha }
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
        healOffHoursMarketData(today)
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

    /**
     * Cleans up off-hours quote artifacts (e.g. `$ 2,30% TEM` mistaken for a price on S30N6,
     * or Rava quotes stamped with today's date before the market opens at 11:00 AM) and
     * restores any holding valuation that was overwritten by a corrupted quote.
     */
    private suspend fun healOffHoursMarketData(today: LocalDate) {
        // Keep wallet FCI cost aligned with its available balance so old records never show
        // a fake unrealized gain/loss.
        holdingDao.getWallets().forEach { walletEntity ->
            if (walletEntity.costCents != walletEntity.currentValueCents) {
                holdingDao.upsert(walletEntity.copy(costCents = walletEntity.currentValueCents))
            }
            HoldingMovementSeeder.ensureSeeded(
                holdingId = walletEntity.id,
                holdingDao = holdingDao,
                holdingMovementDao = holdingMovementDao,
                expenseDao = expenseDao,
            )
        }

        val maxMarketEpochDay = DateUtils.latestMarketSessionDate().toEpochDay()
        val allEntities = instrumentQuoteDao.getAll()
        if (allEntities.isEmpty()) return

        allEntities.groupBy { it.marketKey }.forEach { (marketKey, rows) ->
            val sorted = rows.sortedBy { it.dateEpochDay }
            val domainSeries = sorted.map { it.toDomain() }
            val validDates = PortfolioValuationEngine.sanitizeQuoteSeries(domainSeries)
                .map { it.date.toEpochDay() }
                .toSet()

            sorted.forEach { row ->
                val isUnoperatedToday = row.source == MarketSource.RAVA &&
                    row.dateEpochDay > maxMarketEpochDay
                if (row.price <= 0.0 || row.dateEpochDay !in validDates || isUnoperatedToday) {
                    instrumentQuoteDao.deleteById(row.id)
                }
            }
        }

        var cleanedQuotes = instrumentQuoteDao.getAll().map { it.toDomain() }
        val now = System.currentTimeMillis()
        holdingDao.getAll().map { it.toDomain() }.forEach { holding ->
            if (holding.quantity <= 0.0) return@forEach
            var latest = PortfolioValuationEngine.quotesFor(holding, cleanedQuotes)
                .filter { !it.date.isAfter(today) }
                .lastOrNull()
            val isStaleOrTruncated = latest == null ||
                java.time.temporal.ChronoUnit.DAYS.between(latest.date, today) > 20L
            if (isStaleOrTruncated && (holding.marketKey.isNotBlank() || holding.source != MarketSource.MANUAL)) {
                val refreshed = runCatching { marketDataService.fetchForHolding(holding, today) }.getOrNull()
                if (refreshed != null && refreshed.history.isNotEmpty()) {
                    val effectiveKey = holding.marketKey.ifBlank { refreshed.marketKey }
                    val entities = refreshed.history.map { point ->
                        InstrumentQuoteEntity(
                            marketKey = effectiveKey,
                            symbol = holding.symbol.ifBlank { refreshed.symbol },
                            source = refreshed.source,
                            price = point.price,
                            currency = holding.currency,
                            quoteUnit = holding.quoteUnit,
                            dateEpochDay = point.date.toEpochDay(),
                            fetchedAt = now,
                        )
                    }
                    instrumentQuoteDao.deleteForMarket(effectiveKey)
                    instrumentQuoteDao.upsertAll(entities)
                    cleanedQuotes = instrumentQuoteDao.getAll().map { it.toDomain() }
                    latest = PortfolioValuationEngine.quotesFor(holding, cleanedQuotes)
                        .filter { !it.date.isAfter(today) }
                        .lastOrNull()
                }
            }
            val validLatest = latest?.takeIf {
                it.price > 0.0 && java.time.temporal.ChronoUnit.DAYS.between(it.date, today) <= 45L
            } ?: return@forEach
            val expectedValue = holding.valueCentsFor(validLatest.price)
            if (PortfolioValuationEngine.isCorruptedValuation(holding.currentValueCents, expectedValue)) {
                holdingDao.updateValuation(holding.id, expectedValue, holding.name)
            }
        }
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

internal object HoldingMovementSeeder {
    /**
     * Populates `holding_movements` on first access from the movement lines
     * already stored in `Holding.notes` (or from the broker preset movements
     * when `notes` has no movement lines yet), and backfills any wallet FCI
     * expenses or note lines added after the initial seed.
     */
    suspend fun ensureSeeded(
        holdingId: Long,
        holdingDao: HoldingDao,
        holdingMovementDao: HoldingMovementDao,
        expenseDao: ExpenseDao? = null,
    ) {
        if (holdingId <= 0L) return
        val holding = holdingDao.getById(holdingId)?.toDomain() ?: return
        val existing = holdingMovementDao.getAllForHolding(holdingId)

        if (existing.isEmpty()) {
            val fromNotes = BrokerMovementImporter.parseMovementNotes(holding.notes)
            val sourceMovements = if (fromNotes.isNotEmpty()) {
                fromNotes
            } else {
                val preset = BrokerMovementImporter.presetMovementsFor(holding.symbol, holding.marketKey)
                if (!preset.isNullOrEmpty() && holding.currentValueCents > 0L) {
                    BrokerMovementImporter.summarize(preset).movements
                } else {
                    emptyList()
                }
            }
            if (sourceMovements.isNotEmpty()) {
                val baseTime = System.currentTimeMillis()
                sourceMovements.sortedBy { it.date }.forEachIndexed { index, mov ->
                    val isIncome = mov.signedAmountCents >= 0L
                    val noteText = formatSeededNote(mov)
                    holdingMovementDao.insert(
                        HoldingMovementEntity(
                            holdingId = holdingId,
                            type = if (isIncome) HoldingMovementType.INGRESO else HoldingMovementType.EGRESO,
                            amountCents = abs(mov.signedAmountCents),
                            currency = holding.currency,
                            dateEpochDay = mov.date.toEpochDay(),
                            note = noteText,
                            createdAt = baseTime + index,
                        ),
                    )
                }
            }
        }

        // Backfill or link any WALLET_FCI expenses from `expenses` that were recorded before
        // `holding_movements` tracked expenseId (e.g. card statement or service payments).
        if (holding.isWallet && expenseDao != null) {
            val walletExpenses = expenseDao.getForWalletHolding(holdingId)
            if (walletExpenses.isNotEmpty()) {
                val current = holdingMovementDao.getAllForHolding(holdingId).toMutableList()
                for (exp in walletExpenses) {
                    val expAmount = exp.walletValueDeltaCents.takeIf { it > 0L } ?: exp.totalAmountCents
                    if (expAmount <= 0L) continue
                    val cleanDesc = exp.description.trim()
                    val expectedNote = "Pago: $cleanDesc"
                    val byExpenseId = current.firstOrNull { it.expenseId == exp.id }
                    if (byExpenseId != null) {
                        if (byExpenseId.dateEpochDay != exp.dateEpochDay ||
                            byExpenseId.amountCents != expAmount
                        ) {
                            val updated = byExpenseId.copy(
                                dateEpochDay = exp.dateEpochDay,
                                amountCents = expAmount,
                                note = expectedNote,
                            )
                            holdingMovementDao.upsertAll(listOf(updated))
                            current[current.indexOf(byExpenseId)] = updated
                        }
                        continue
                    }

                    val unlinkedMatch = current.firstOrNull { row ->
                        row.expenseId == null &&
                            row.type == HoldingMovementType.EGRESO &&
                            abs(row.amountCents - expAmount) <= 1L &&
                            (row.dateEpochDay == exp.dateEpochDay ||
                                (cleanDesc.isNotBlank() && row.note.contains(cleanDesc, ignoreCase = true)))
                    }
                    if (unlinkedMatch != null) {
                        val linked = unlinkedMatch.copy(
                            expenseId = exp.id,
                            dateEpochDay = exp.dateEpochDay,
                            note = expectedNote,
                        )
                        holdingMovementDao.upsertAll(listOf(linked))
                        current[current.indexOf(unlinkedMatch)] = linked
                    } else {
                        val entity = HoldingMovementEntity(
                            holdingId = holdingId,
                            type = HoldingMovementType.EGRESO,
                            amountCents = expAmount,
                            currency = holding.currency,
                            dateEpochDay = exp.dateEpochDay,
                            note = expectedNote,
                            createdAt = exp.createdAt,
                            expenseId = exp.id,
                        )
                        val newId = holdingMovementDao.insert(entity)
                        current += entity.copy(id = newId)
                    }
                }
            }
        }

        // Also backfill any movement lines appended to `holding.notes` after the initial seed.
        val notesMovements = BrokerMovementImporter.parseMovementNotes(holding.notes)
        if (notesMovements.isNotEmpty()) {
            val current = holdingMovementDao.getAllForHolding(holdingId).toMutableList()
            val baseTime = System.currentTimeMillis()
            notesMovements.sortedBy { it.date }.forEachIndexed { index, mov ->
                val isIncome = mov.signedAmountCents >= 0L
                val type = if (isIncome) HoldingMovementType.INGRESO else HoldingMovementType.EGRESO
                val amount = abs(mov.signedAmountCents)
                val epochDay = mov.date.toEpochDay()
                val alreadyExists = current.any { row ->
                    row.type == type &&
                        abs(row.amountCents - amount) <= 1L &&
                        (row.dateEpochDay == epochDay ||
                            (mov.operationCode.isNotBlank() &&
                                row.note.contains(mov.operationCode.removePrefix("RESCATE:").trim(), ignoreCase = true)))
                }
                if (!alreadyExists) {
                    val entity = HoldingMovementEntity(
                        holdingId = holdingId,
                        type = type,
                        amountCents = amount,
                        currency = holding.currency,
                        dateEpochDay = epochDay,
                        note = formatSeededNote(mov),
                        createdAt = baseTime + index,
                    )
                    val newId = holdingMovementDao.insert(entity)
                    current += entity.copy(id = newId)
                }
            }
        }
    }

    private fun formatSeededNote(mov: com.smartinvest.domain.engine.HoldingMovement): String {
        val qtySuffix = mov.quantity?.takeIf { abs(it) > 1e-6 }?.let { q ->
            val sign = if (q >= 0.0) "+" else ""
            val formatted = if (abs(q) % 1.0 == 0.0) {
                String.format(java.util.Locale.US, "%.0f", q)
            } else {
                String.format(java.util.Locale.US, "%.2f", q)
            }
            "cant: $sign$formatted"
        }.orEmpty()
        return listOf(mov.operationCode, qtySuffix)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
    }
}
