package com.smartinvest.data.network

import com.smartinvest.data.local.entity.WalletEntity
import com.smartinvest.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

data class ScrapedRates(
    val rates: Map<String, Double>,
    val source: String,
    val fetchedAtMillis: Long,
)

interface RateScraper {
    suspend fun fetchRates(): ScrapedRates
}

@Serializable
internal data class ComparatasasEntitiesResponseDto(
    val updatedAt: String = "",
    val entities: List<ComparatasasEntityDto> = emptyList(),
)

@Serializable
internal data class ComparatasasEntityDto(
    val slug: String = "",
    val name: String = "",
    val type: String = "",
    val products: List<ComparatasasProductDto> = emptyList(),
)

@Serializable
internal data class ComparatasasProductDto(
    val category: String = "",
    val product: String = "",
    val value: Double? = null,
    val metric: String = "",
)

@Serializable
internal data class ArgentinaDatosAccountRateDto(
    val fondo: String = "",
    val tna: Double? = null,
    val tea: Double? = null,
    val tope: Double? = null,
    val fecha: String = "",
    @SerialName("condicionesCorto") val condicionesCorto: String = "",
)

/**
 * Fetches retail TNA rates for Argentine wallets and money-market funds.
 *
 * Primary source: `https://comparatasas.ar/api/entidades.json` (the JSON feed
 * behind the Nuxt 4 site).
 * Secondary source: `https://api.argentinadatos.com/v1/finanzas/fci/otros/ultimo`
 * (remunerated accounts feed used by ComparaTasas).
 * Fallback: defensive HTML table scrape if the JSON feeds are unreachable.
 */
@Singleton
class ComparatasasScraper @Inject constructor(
    private val client: OkHttpClient,
    @IoDispatcher private val io: CoroutineDispatcher,
) : RateScraper {

    override suspend fun fetchRates(): ScrapedRates = withContext(io) {
        val rates = linkedMapOf<String, Double>()
        val sources = mutableListOf<String>()

        runCatching { fetchText(ENTITIES_JSON_URL, acceptJson = true) }
            .mapCatching { parseEntitiesJson(it) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let {
                rates.putAll(it)
                sources += "comparatasas.ar"
            }

        runCatching { fetchText(ARGENTINADATOS_ACCOUNTS_URL, acceptJson = true) }
            .mapCatching { parseArgentinaDatosAccountsJson(it) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { accountRates ->
                accountRates.forEach { (k, v) -> rates.putIfAbsent(k, v) }
                if ("comparatasas.ar" !in sources) sources += "ArgentinaDatos · cuentas"
            }

        if (rates.isEmpty()) {
            runCatching { fetchText(HTML_URL, acceptJson = false) }
                .mapCatching { parseHtmlTables(it) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    rates.putAll(it)
                    sources += "comparatasas.ar"
                }
        }

        if (rates.isEmpty()) error("Sin tasas disponibles en Comparatasas / ArgentinaDatos")

        ScrapedRates(
            rates = rates,
            source = sources.distinct().joinToString(" + ").ifBlank { "comparatasas.ar" },
            fetchedAtMillis = System.currentTimeMillis(),
        )
    }

    private fun fetchText(url: String, acceptJson: Boolean): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header(
                "Accept",
                if (acceptJson) "application/json,text/plain;q=0.9" else "text/html,application/xhtml+xml",
            )
            .build()

        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("$url respondió ${response.code}")
            response.body?.string().orEmpty()
        }
    }

    companion object {
        private const val ENTITIES_JSON_URL = "https://comparatasas.ar/api/entidades.json"
        private const val ARGENTINADATOS_ACCOUNTS_URL =
            "https://api.argentinadatos.com/v1/finanzas/fci/otros/ultimo"
        private const val HTML_URL = "https://comparatasas.ar/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private val PERCENT = Regex("""(\d{1,3}(?:[.,]\d{1,2})?)\s*%""")

        private val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            explicitNulls = false
        }

        private val RATE_CATEGORIES = listOf("cuentas-remuneradas", "fondos", "plazos-fijos")

        internal fun parseEntitiesJson(raw: String): Map<String, Double> {
            if (raw.isBlank()) return emptyMap()
            val dto = json.decodeFromString<ComparatasasEntitiesResponseDto>(raw)
            val rates = linkedMapOf<String, Double>()

            dto.entities.forEach { entity ->
                val entityKey = normalize(entity.name)
                val slugKey = normalize(entity.slug)
                val validProducts = entity.products.mapNotNull { product ->
                    if (product.category !in RATE_CATEGORIES) return@mapNotNull null
                    val percent = toPercent(product.value) ?: return@mapNotNull null
                    product to percent
                }
                if (validProducts.isEmpty()) return@forEach

                // Index individual product names (e.g. "Mercado Fondo - Clase A", "Ualá Plus 2").
                validProducts.forEach { (product, percent) ->
                    val productKey = normalize(product.product)
                    if (productKey.length >= 3) rates.putIfAbsent(productKey, percent)
                }

                // Pick the representative TNA for the entity itself:
                // 1) cuentas-remuneradas matching the base entity name (or the first one)
                // 2) first money-market / savings FCI in "fondos"
                // 3) first plazo fijo
                val chosen = RATE_CATEGORIES.firstNotNullOfOrNull { category ->
                    val inCategory = validProducts.filter { it.first.category == category }
                    if (inCategory.isEmpty()) {
                        null
                    } else {
                        inCategory.firstOrNull { normalize(it.first.product) == entityKey }
                            ?: inCategory.first()
                    }
                }?.second ?: return@forEach

                if (entityKey.length >= 3) rates.putIfAbsent(entityKey, chosen)
                if (slugKey.length >= 3) rates.putIfAbsent(slugKey, chosen)
            }
            return rates
        }

        internal fun parseArgentinaDatosAccountsJson(raw: String): Map<String, Double> {
            if (raw.isBlank()) return emptyMap()
            val items = json.decodeFromString<List<ArgentinaDatosAccountRateDto>>(raw)
            val rates = linkedMapOf<String, Double>()
            items.forEach { item ->
                val percent = toPercent(item.tna) ?: return@forEach
                val key = normalize(item.fondo)
                if (key.length >= 3) rates.putIfAbsent(key, percent)
            }
            return rates
        }

        internal fun parseHtmlTables(html: String): Map<String, Double> {
            if (html.isBlank()) return emptyMap()
            val doc = Jsoup.parse(html)
            val rates = linkedMapOf<String, Double>()
            doc.select("table tr").forEach { row ->
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
            return rates
        }

        private fun toPercent(raw: Double?): Double? {
            val v = raw ?: return null
            if (v <= 0.0) return null
            val percent = if (v <= 1.5) v * 100.0 else v
            if (percent !in 1.0..200.0) return null
            return (percent * 100.0).roundToLong() / 100.0
        }

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
            .filter { it.length >= 3 }
            .distinct()

        for (candidate in candidates) {
            rates[candidate]?.let { return it }
        }
        for (candidate in candidates) {
            if (candidate.length < 4) continue
            val contained = rates.entries
                .filter { (key, _) -> key.length >= 4 && (key.contains(candidate) || candidate.contains(key)) }
                .minByOrNull { kotlin.math.abs(it.key.length - candidate.length) }
            if (contained != null) return contained.value
        }
        return null
    }
}
