package com.smartinvest.data.repository

import com.smartinvest.core.time.DateUtils
import com.smartinvest.data.local.dao.RateHistoryDao
import com.smartinvest.data.local.dao.WalletDao
import com.smartinvest.data.local.entity.RateHistoryEntity
import com.smartinvest.data.network.RateMatcher
import com.smartinvest.data.network.RateScraper
import com.smartinvest.di.IoDispatcher
import com.smartinvest.domain.model.SyncOutcome
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
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun sync(): SyncOutcome = withContext(io) {
        val wallets = walletDao.getAll().filter { it.isActive }
        val now = System.currentTimeMillis()

        val scraped = runCatching { scraper.fetchRates() }.getOrNull()
        if (scraped == null || scraped.rates.isEmpty()) {
            return@withContext SyncOutcome(
                updatedWallets = 0,
                failedWallets = wallets.size,
                source = "comparatasas.ar",
                message = "No se pudieron leer las tasas. Se mantienen las vigentes; podés editarlas manualmente.",
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

        val message = buildString {
            append("Actualizadas $updated cuentas")
            if (unchanged > 0) append(" · $unchanged sin cambios")
            if (failed > 0) append(" · $failed sin coincidencia")
        }

        SyncOutcome(
            updatedWallets = updated,
            failedWallets = failed,
            source = scraped.source,
            message = message,
            atMillis = now,
        )
    }
}
