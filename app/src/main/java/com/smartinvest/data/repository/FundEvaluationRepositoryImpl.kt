package com.smartinvest.data.repository

import com.smartinvest.data.network.FundComparisonClient
import com.smartinvest.domain.model.FundCatalogEntry
import com.smartinvest.domain.model.FundData
import com.smartinvest.domain.model.FundPoint
import com.smartinvest.domain.repository.FundEvaluationRepository
import com.smartinvest.domain.repository.SettingsRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** JSON codec for the watchlist stored in [com.smartinvest.domain.model.AppSettings]. */
object FundWatchlistCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(names: List<String>): String =
        json.encodeToString(names.map { it.trim() }.filter { it.isNotEmpty() }.distinct())

    fun decode(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<String>>(raw) }
            .getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }
}

@Singleton
class FundEvaluationRepositoryImpl @Inject constructor(
    private val client: FundComparisonClient,
    private val settingsRepository: SettingsRepository,
) : FundEvaluationRepository {

    private val cacheMutex = Mutex()
    private var catalogCache: List<FundCatalogEntry> = emptyList()
    private var catalogFetchedAt: Long = 0L
    private val historyCache = mutableMapOf<String, CachedHistory>()

    override fun observeWatchlist(): Flow<List<String>> =
        settingsRepository.settings.map { FundWatchlistCodec.decode(it.fundWatchlistJson) }

    override suspend fun ensureSeededWatchlist(): List<String> {
        val current = settingsRepository.currentSettings()
        val raw = current.fundWatchlistJson
        if (raw.isNotBlank()) return FundWatchlistCodec.decode(raw)
        val seeded = FundEvaluationRepository.DEFAULT_WATCHLIST
        settingsRepository.update(
            current.copy(fundWatchlistJson = FundWatchlistCodec.encode(seeded)),
        )
        return seeded
    }

    override suspend fun addToWatchlist(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        val current = settingsRepository.currentSettings()
        val names = FundWatchlistCodec.decode(current.fundWatchlistJson)
        if (names.any { it.equals(clean, ignoreCase = true) }) return
        settingsRepository.update(
            current.copy(fundWatchlistJson = FundWatchlistCodec.encode(names + clean)),
        )
    }

    override suspend fun removeFromWatchlist(name: String) {
        val current = settingsRepository.currentSettings()
        val names = FundWatchlistCodec.decode(current.fundWatchlistJson)
            .filterNot { it.equals(name, ignoreCase = true) }
        settingsRepository.update(
            current.copy(fundWatchlistJson = FundWatchlistCodec.encode(names)),
        )
    }

    override suspend fun resetWatchlist(): List<String> {
        val current = settingsRepository.currentSettings()
        settingsRepository.update(
            current.copy(
                fundWatchlistJson = FundWatchlistCodec.encode(
                    FundEvaluationRepository.DEFAULT_WATCHLIST,
                ),
            ),
        )
        return FundEvaluationRepository.DEFAULT_WATCHLIST
    }

    override suspend fun catalog(force: Boolean): List<FundCatalogEntry> {
        val now = System.currentTimeMillis()
        cacheMutex.withLock {
            if (!force && catalogCache.isNotEmpty() && now - catalogFetchedAt < CATALOG_TTL_MS) {
                return catalogCache
            }
        }

        val fresh = client.fetchCatalog()
        cacheMutex.withLock {
            catalogCache = fresh
            catalogFetchedAt = System.currentTimeMillis()
        }
        return fresh
    }

    override suspend fun loadFunds(names: List<String>, force: Boolean): List<FundData> {
        val cleanNames = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (cleanNames.isEmpty()) return emptyList()
        val catalog = runCatching { catalog(force) }.getOrDefault(emptyList())
        val entries = catalog.associateBy { it.name.lowercase() }

        return coroutineScope {
            cleanNames.map { name ->
                async {
                    val entry = entries[name.lowercase()]
                    val history = runCatching { history(name, force) }.getOrDefault(emptyList())
                    FundData(name = name, catalog = entry, history = history)
                }
            }.awaitAll()
        }
    }

    override suspend fun loadFund(name: String, force: Boolean): FundData? {
        val clean = name.trim()
        if (clean.isEmpty()) return null
        return loadFunds(listOf(clean), force).firstOrNull()
    }

    private suspend fun history(name: String, force: Boolean): List<FundPoint> {
        val now = System.currentTimeMillis()
        cacheMutex.withLock {
            val cached = historyCache[name]
            if (!force && cached != null && now - cached.fetchedAt < HISTORY_TTL_MS) {
                return cached.points
            }
        }

        val fresh = client.fetchHistory(name)
        cacheMutex.withLock {
            historyCache[name] = CachedHistory(points = fresh, fetchedAt = System.currentTimeMillis())
        }
        return fresh
    }

    private data class CachedHistory(
        val points: List<FundPoint>,
        val fetchedAt: Long,
    )

    companion object {
        private const val CATALOG_TTL_MS = 30 * 60 * 1000L
        private const val HISTORY_TTL_MS = 6 * 60 * 60 * 1000L
    }
}
