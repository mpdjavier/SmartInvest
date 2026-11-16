package com.smartinvest.data.network

import com.smartinvest.di.IoDispatcher
import com.smartinvest.domain.model.FundCatalogEntry
import com.smartinvest.domain.model.FundPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for the public [compararfondos.com.ar](https://compararfondos.com.ar/api.html)
 * API. It aggregates CAFCI/CNV data, needs no key and requires attribution, so it
 * is a good fit for a read-only evaluator inside the app.
 */
@Singleton
class FundComparisonClient @Inject constructor(
    client: OkHttpClient,
    private val json: Json,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Dedicated client: the full catalog is several megabytes, so the shared
     * 30-second call timeout is too tight for slow mobile connections.
     */
    private val http: OkHttpClient = client.newBuilder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()


    suspend fun fetchCatalog(): List<FundCatalogEntry> = withContext(io) {
        val body = get(API_FUNDS)
        parseCatalog(body, json)
    }

    suspend fun fetchHistory(name: String): List<FundPoint> = withContext(io) {
        val url = API_BASE.toHttpUrl().newBuilder()
            .addPathSegment("api")
            .addPathSegment("fondo")
            .addPathSegment(name)
            .build()
        val body = get(url.toString())
        parseHistory(body, json)
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json")
            .header("Accept-Language", "es-AR,es;q=0.9")
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("compararfondos respondió HTTP ${response.code}")
            }
            response.body?.string().orEmpty()
        }
    }

    companion object {
        internal const val API_BASE = "https://compararfondos.com.ar"
        internal const val API_FUNDS = "$API_BASE/api/fondos"
        internal const val ATTRIBUTION = "Datos: compararfondos.com.ar (CAFCI/CNV)"
        internal const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"

        internal fun parseCatalog(raw: String, json: Json): List<FundCatalogEntry> {
            if (raw.isBlank()) return emptyList()
            val response = json.decodeFromString<FundListDto>(raw)
            return response.funds.mapNotNull { dto ->
                val name = dto.nombre?.trim().orEmpty()
                if (name.isEmpty()) return@mapNotNull null
                FundCatalogEntry(
                    name = name,
                    manager = dto.gestora?.trim().orEmpty(),
                    type = dto.tipo?.trim().orEmpty(),
                    currency = dto.moneda?.trim()?.uppercase().orEmpty().ifBlank { "ARS" },
                    category = dto.categoriaOficial?.trim().orEmpty().ifBlank {
                        dto.categoria?.trim().orEmpty()
                    },
                    patrimony = dto.patrimonio ?: 0.0,
                    vcp = dto.vcpHoy ?: 0.0,
                    vcpDate = dto.fechaVcp?.let(::parseDate),
                    return7 = dto.r7,
                    return30 = dto.r30,
                    return365 = dto.r365,
                    volatility90 = dto.volat,
                    maxDrop90 = dto.maxDrop,
                    positiveDays90 = dto.positiveDays,
                )
            }
        }

        internal fun parseHistory(raw: String, json: Json): List<FundPoint> {
            if (raw.isBlank()) return emptyList()
            val response = json.decodeFromString<FundHistoryDto>(raw)
            return response.history
                .mapNotNull { point ->
                    val date = parseDate(point.fecha) ?: return@mapNotNull null
                    val value = point.vcp ?: return@mapNotNull null
                    if (!value.isFinite() || value <= 0.0) return@mapNotNull null
                    FundPoint(date = date, value = value)
                }
                .distinctBy { it.date }
                .sortedBy { it.date }
        }

        private fun parseDate(raw: String?): LocalDate? {
            val clean = raw?.trim().orEmpty()
            if (clean.isEmpty()) return null
            return runCatching { LocalDate.parse(clean.take(10)) }.getOrNull()
        }
    }
}

@Serializable
private data class FundListDto(
    val status: String? = null,
    val count: Int? = null,
    val funds: List<FundDto> = emptyList(),
)

@Serializable
private data class FundDto(
    val nombre: String? = null,
    val nombreBase: String? = null,
    val gestora: String? = null,
    val tipo: String? = null,
    val moneda: String? = null,
    val categoria: String? = null,
    val categoriaOficial: String? = null,
    val patrimonio: Double? = null,
    val vcpHoy: Double? = null,
    val fechaVcp: String? = null,
    val r7: Double? = null,
    val r30: Double? = null,
    val r365: Double? = null,
    val volat: Double? = null,
    val maxDrop: Double? = null,
    val positiveDays: Double? = null,
)

@Serializable
private data class FundHistoryDto(
    val nombre: String? = null,
    val count: Int? = null,
    val history: List<FundHistoryPointDto> = emptyList(),
)

@Serializable
private data class FundHistoryPointDto(
    val fecha: String? = null,
    val vcp: Double? = null,
)
