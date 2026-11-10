package com.smartinvest.data.network

import com.smartinvest.data.local.entity.WalletEntity
import com.smartinvest.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

data class ScrapedRates(
    val rates: Map<String, Double>,
    val source: String,
    val fetchedAtMillis: Long,
)

interface RateScraper {
    suspend fun fetchRates(): ScrapedRates
}

/**
 * Best-effort scraper for Comparatasas. The site is a public aggregator of TNA
 * for Argentine wallets. Selectors are defensive because the markup can change;
 * when nothing can be parsed the app keeps the current (manually editable) rates.
 */
@Singleton
class ComparatasasScraper @Inject constructor(
    private val client: OkHttpClient,
    @IoDispatcher private val io: CoroutineDispatcher,
) : RateScraper {

    override suspend fun fetchRates(): ScrapedRates = withContext(io) {
        val request = Request.Builder()
            .url(URL)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()

        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Comparatasas respondió ${response.code}")
            response.body?.string().orEmpty()
        }
        if (html.isBlank()) error("Respuesta vacía de Comparatasas")

        val doc = Jsoup.parse(html)
        val rates = linkedMapOf<String, Double>()

        val rows = doc.select("table tr")
        rows.forEach { row ->
            val cells = row.select("td")
            if (cells.size < 2) return@forEach
            val name = cells.first()?.text()?.trim().orEmpty()
            if (name.length !in 2..80) return@forEach
            val percent = PERCENT.find(row.text())?.groupValues?.getOrNull(1)
            val value = percent?.replace(',', '.')?.toDoubleOrNull()
            if (value != null && value in 1.0..200.0) {
                val key = normalize(name)
                if (key.length >= 3) rates.putIfAbsent(key, value)
            }
        }

        ScrapedRates(
            rates = rates,
            source = "comparatasas.ar",
            fetchedAtMillis = System.currentTimeMillis(),
        )
    }

    companion object {
        private const val URL = "https://comparatasas.ar/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private val PERCENT = Regex("""(\d{1,3}(?:[.,]\d{1,2})?)\s*%""")

        fun normalize(value: String): String {
            val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
            return decomposed
                .replace(Regex("\\p{M}+"), "")
                .lowercase()
                .filter { it.isLetterOrDigit() }
        }
    }
}

object RateMatcher {

    fun find(wallet: WalletEntity, rates: Map<String, Double>): Double? {
        if (rates.isEmpty()) return null
        val candidates = listOf(wallet.name, wallet.institution)
            .map(ComparatasasScraper::normalize)
            .filter { it.length >= 4 }
            .distinct()

        for (candidate in candidates) {
            rates[candidate]?.let { return it }
            val contained = rates.entries
                .filter { (key, _) -> key.length >= 4 && (key.contains(candidate) || candidate.contains(key)) }
                .maxByOrNull { it.key.length }
            if (contained != null) return contained.value
        }
        return null
    }
}
