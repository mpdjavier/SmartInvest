package com.smartinvest.data.repository

import com.smartinvest.core.time.DateUtils
import com.smartinvest.data.local.dao.RateHistoryDao
import com.smartinvest.data.local.dao.WalletDao
import com.smartinvest.data.local.entity.RateHistoryEntity
import com.smartinvest.data.network.ArgentinaDatosService
import com.smartinvest.data.network.ComparatasasScraper
import com.smartinvest.data.network.RateMatcher
import com.smartinvest.data.network.RateScraper
import com.smartinvest.data.network.ScrapedRates
import com.smartinvest.di.IoDispatcher
import com.smartinvest.domain.model.SyncOutcome
import com.smartinvest.domain.model.WalletType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class RateSyncService @Inject constructor(
    private val walletDao: WalletDao,
    private val rateHistoryDao: RateHistoryDao,
    private val scraper: RateScraper,
    private val argentinaDatos: ArgentinaDatosService,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun sync(): SyncOutcome = withContext(io) {
        val allActive = walletDao.getAll().filter { it.isActive }
        // Non-remunerated bank / broker checking accounts (TNA = 0%) are not
        // remunerated wallets unless the user explicitly assigned a TNA > 0.
        val wallets = allActive.filter { wallet ->
            wallet.type == WalletType.WALLET ||
                wallet.type == WalletType.MONEY_MARKET_FUND ||
                wallet.tnaPercent > 0.0
        }
        val now = System.currentTimeMillis()

        // Comparatasas JSON feed (/api/entidades.json + ArgentinaDatos cuentas)
        // is primary; BCRA fixed-term series from ArgentinaDatos is the fallback.
        val scraped = runCatching { scraper.fetchRates() }
            .getOrNull()
            ?.takeIf { it.rates.isNotEmpty() }
            ?: runCatching { fetchArgentinaDatosRates() }.getOrNull()

        if (scraped == null || scraped.rates.isEmpty()) {
            return@withContext SyncOutcome(
                updatedWallets = 0,
                failedWallets = wallets.size,
                source = "comparatasas.ar / ArgentinaDatos",
                message = "Sin conexión con fuentes de tasas. Se mantienen las TNA vigentes.",
                atMillis = now,
            )
        }

        var updated = 0
        var unchanged = 0
        var failed = 0
        val today = DateUtils.today()

        wallets.forEach { wallet ->
            val rate = RateMatcher.find(wallet, scraped.rates)
            if (rate == null) {
                failed++
            } else if (abs(rate - wallet.tnaPercent) < 0.01) {
                unchanged++
            } else {
                walletDao.updateRate(
                    id = wallet.id,
                    tna = rate,
                    source = scraped.source,
                    updatedAt = now,
                )
                rateHistoryDao.upsert(
                    RateHistoryEntity(
                        walletId = wallet.id,
                        dateEpochDay = today.toEpochDay(),
                        tnaPercent = rate,
                        source = scraped.source,
                    ),
                )
                updated++
            }
        }

        val message = when {
            wallets.isEmpty() -> "No hay cuentas remuneradas activas para actualizar."
            updated == 0 && unchanged == 0 -> {
                "Tasas leídas desde ${scraped.source} (${scraped.rates.size} entidades), " +
                    "sin coincidencias por nombre. Se mantienen las TNA vigentes."
            }
            else -> buildString {
                append("Actualizadas $updated cuentas")
                if (unchanged > 0) append(" · $unchanged al día")
                if (failed > 0) append(" · $failed sin coincidencia")
            }
        }

        SyncOutcome(
            updatedWallets = updated,
            failedWallets = failed,
            source = scraped.source,
            message = message,
            atMillis = now,
        )
    }

    /** Fixed-term client rates from ArgentinaDatos, expressed as percent. */
    private suspend fun fetchArgentinaDatosRates(): ScrapedRates {
        val rows = argentinaDatos.fixedTermRates()
        val rates = linkedMapOf<String, Double>()
        rows.forEach { row ->
            val raw = row.tnaClientes ?: row.tnaNoClientes ?: return@forEach
            val percent = if (raw !in 0.0..1.0) raw else raw * 100.0
            if (percent !in 1.0..200.0) return@forEach
            val key = ComparatasasScraper.normalize(row.entidad)
            if (key.length >= 4) rates.putIfAbsent(key, percent)
        }
        if (rates.isEmpty()) error("ArgentinaDatos no devolvió tasas de plazo fijo")
        return ScrapedRates(
            rates = rates,
            source = "ArgentinaDatos · plazo fijo BCRA",
            fetchedAtMillis = System.currentTimeMillis(),
        )
    }
}
