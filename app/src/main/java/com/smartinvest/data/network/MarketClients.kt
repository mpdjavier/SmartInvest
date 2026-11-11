package com.smartinvest.data.network

import com.smartinvest.core.time.DateUtils
import com.smartinvest.di.IoDispatcher
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.MarketSource
import com.smartinvest.domain.model.QuoteUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class MarketQuotePoint(
    val date: LocalDate,
    val price: Double,
)

data class MarketFetch(
    val marketKey: String,
    val symbol: String,
    val name: String,
    val source: MarketSource,
    val latestPrice: Double,
    val latestDate: LocalDate,
    val history: List<MarketQuotePoint>,
)

data class MarketPreset(
    val label: String,
    val symbol: String,
    val name: String,
    val assetType: AssetType,
    val currency: CurrencyCode,
    val quoteUnit: QuoteUnit,
    val marketKey: String,
    /** Redemption delay in days (FCIs). */
    val settlementDays: Int = 1,
)

object MarketPresets {
    val all: List<MarketPreset> = listOf(
        MarketPreset(
            label = "S30N6 (LECAP)",
            symbol = "S30N6",
            name = "Letra del Tesoro S30N6",
            assetType = AssetType.LECAP,
            currency = CurrencyCode.ARS,
            quoteUnit = QuoteUnit.PER_100_NOMINAL,
            marketKey = "rava:S30N6",
        ),
        MarketPreset(
            label = "AO27D (Bono USD)",
            symbol = "AO27D",
            name = "Bonar 2027 U\$S",
            assetType = AssetType.BOND,
            currency = CurrencyCode.USD,
            quoteUnit = QuoteUnit.PER_100_NOMINAL,
            marketKey = "rava:AO27D",
        ),
        MarketPreset(
            label = "SPY (CEDEAR)",
            symbol = "SPY",
            name = "SPDR S&P 500 CEDEAR",
            assetType = AssetType.CEDEAR,
            currency = CurrencyCode.ARS,
            quoteUnit = QuoteUnit.PER_UNIT,
            marketKey = "rava:SPY",
        ),
        MarketPreset(
            label = "Adcap Balanceado III",
            symbol = "ADCAP-BAL3",
            name = "Adcap Balanceado III - Clase A",
            assetType = AssetType.FCI,
            currency = CurrencyCode.ARS,
            quoteUnit = QuoteUnit.PER_UNIT,
            marketKey = "cafci:1402:4070",
            settlementDays = 1,
        ),
        MarketPreset(
            label = "Mercado Fondo ARS",
            symbol = "MP-ARS",
            name = "Mercado Fondo Ahorro Pesos",
            assetType = AssetType.FCI,
            currency = CurrencyCode.ARS,
            quoteUnit = QuoteUnit.PER_UNIT,
            marketKey = "cafci:798:1982",
            settlementDays = 0,
        ),
        MarketPreset(
            label = "Mercado Fondo USD",
            symbol = "MP-USD",
            name = "Mercado Fondo Dólares",
            assetType = AssetType.FCI,
            currency = CurrencyCode.USD,
            quoteUnit = QuoteUnit.PER_UNIT,
            marketKey = "cafci:1522:4557",
            settlementDays = 0,
        ),
    )
}

sealed interface ParsedMarketKey {
    val normalizedKey: String
    val source: MarketSource

    data class Rava(val symbol: String) : ParsedMarketKey {
        override val normalizedKey: String = "rava:${symbol.uppercase()}"
        override val source: MarketSource = MarketSource.RAVA
    }

    data class Cafci(val fundId: String, val classId: String) : ParsedMarketKey {
        override val normalizedKey: String = "cafci:$fundId:$classId"
        override val source: MarketSource = MarketSource.CAFCI
    }
}

object MarketKeyParser {
    private val CAFCI_URL_REGEX = Regex("""fondos/(\d+)\?clase=(\d+)""", RegexOption.IGNORE_CASE)
    private val CAFCI_KEY_REGEX = Regex("""^cafci:(\d+):(\d+)$""", RegexOption.IGNORE_CASE)
    private val RAVA_URL_REGEX = Regex("""rava\.com/perfil/([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)
    private val RAVA_KEY_REGEX = Regex("""^rava:([A-Za-z0-9._-]+)$""", RegexOption.IGNORE_CASE)

    fun parse(raw: String): ParsedMarketKey? {
        val clean = raw.trim()
        if (clean.isEmpty()) return null

        CAFCI_KEY_REGEX.matchEntire(clean)?.let { m ->
            return ParsedMarketKey.Cafci(m.groupValues[1], m.groupValues[2])
        }
        CAFCI_URL_REGEX.find(clean)?.let { m ->
            return ParsedMarketKey.Cafci(m.groupValues[1], m.groupValues[2])
        }
        RAVA_KEY_REGEX.matchEntire(clean)?.let { m ->
            return ParsedMarketKey.Rava(m.groupValues[1].uppercase())
        }
        RAVA_URL_REGEX.find(clean)?.let { m ->
            return ParsedMarketKey.Rava(m.groupValues[1].uppercase())
        }
        return null
    }

    fun normalize(raw: String): String = parse(raw)?.normalizedKey ?: raw.trim()

    fun sourceFor(raw: String): MarketSource = parse(raw)?.source ?: MarketSource.MANUAL
}

@Singleton
class RavaClient @Inject constructor(
    private val client: OkHttpClient,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun fetch(symbol: String, today: LocalDate = DateUtils.today()): MarketFetch = withContext(io) {
        val cleanSymbol = symbol.trim().uppercase()
        val url = "https://www.rava.com/perfil/$cleanSymbol"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "es-AR,es;q=0.9")
            .build()

        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Rava respondió HTTP ${response.code} para $cleanSymbol")
            response.body?.string().orEmpty()
        }
        val histJson = runCatching { fetchHistoricoJson(cleanSymbol) }.getOrNull()
        parseHtml(cleanSymbol, html, today, histJson)
    }

    private fun fetchHistoricoJson(symbol: String): String? {
        val histUrl = "https://mercado.rava.com/api/prices/historico/arg/$symbol?dias=90"
        val request = Request.Builder()
            .url(histUrl)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "application/json")
            .header("Referer", "https://mercado.rava.com/")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }

    companion object {
        internal const val BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
        private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        private val META_PRICE = Regex("""\$\s*([\d.]+(?:,\d+)?)""")
        private val QUOTE_TIME = Regex("""(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?""")
        private val JSON_OBJECT_REGEX = Regex("""\{[^{}]+\}""")
        private val JSON_PRICE_REGEX = Regex(""""precio"\s*:\s*([\d.]+)""")
        private val JSON_DATE_REGEX = Regex(""""fecha"\s*:\s*"(\d{4}-\d{2}-\d{2})""")

        fun parseHtml(
            symbol: String,
            html: String,
            fallbackDate: LocalDate = LocalDate.now(),
            historicoJson: String? = null,
        ): MarketFetch {
            require(html.isNotBlank()) { "Respuesta vacía de Rava para $symbol" }
            val doc = Jsoup.parse(html)

            val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
            val cleanName = ogTitle
                .substringBefore("|")
                .removePrefix(symbol)
                .trim()
                .ifBlank { symbol }

            val history = mutableListOf<MarketQuotePoint>()
            val tables = doc.select("table")
            for (table in tables) {
                val headers = table.select("thead th, tr th").map { it.text().trim().lowercase() }
                val dateIdx = headers.indexOfFirst { it.contains("fecha") }
                val closeIdx = headers.indexOfFirst { it.contains("cierre") || it.contains("último") || it.contains("ultimo") }
                if (dateIdx >= 0 && closeIdx >= 0) {
                    val rows = table.select("tbody tr").ifEmpty { table.select("tr").drop(1) }
                    for (row in rows) {
                        val cells = row.select("td")
                        if (cells.size <= maxOf(dateIdx, closeIdx)) continue
                        val dateText = cells[dateIdx].text().trim()
                        val closeText = cells[closeIdx].text().trim()
                        val date = runCatching { LocalDate.parse(dateText, DATE_FMT) }.getOrNull() ?: continue
                        val price = parseArNumber(closeText) ?: continue
                        if (price > 0.0) {
                            history += MarketQuotePoint(date = date, price = price)
                        }
                    }
                    if (history.isNotEmpty()) break
                }
            }

            val jsonPoints = parseHistoricoJson(historicoJson.orEmpty()).associateBy { it.date }
            if (jsonPoints.isNotEmpty()) {
                for (i in history.indices) {
                    val existing = history[i]
                    val jsonPoint = jsonPoints[existing.date] ?: continue
                    if (hasMoreDecimalPrecision(jsonPoint.price, existing.price)) {
                        history[i] = jsonPoint
                    }
                }
                val existingDates = history.map { it.date }.toSet()
                jsonPoints.values.filter { it.date !in existingDates }.forEach { history += it }
            }

            val ogDesc = doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.selectFirst("meta[name=description]")?.attr("content")
                .orEmpty()
            val metaPrice = META_PRICE.find(ogDesc)?.groupValues?.getOrNull(1)?.let(::parseArNumber)

            // Current Rava pages render the quote in the hero instead of a history table.
            // Read the price, its real date and the "Anterior" close so the app gets two
            // points on the very first sync.
            val heroPrice = doc.selectFirst(".p2-price")?.text()?.trim()?.let(::parseArNumber)
            val quoteDate = parseQuoteDate(doc.selectFirst(".p2-time")?.text().orEmpty(), fallbackDate)
            val stats = doc.select("dl.p2-stats .p2-stat").mapNotNull { stat ->
                val label = stat.selectFirst("dt")?.text()?.trim()?.lowercase() ?: return@mapNotNull null
                val value = stat.selectFirst("dd")?.text()?.trim()?.let(::parseArNumber) ?: return@mapNotNull null
                label to value
            }.toMap()
            val rawPreviousPrice = stats["anterior"]?.takeIf { it > 0.0 }
            val rawLatestPrice = heroPrice?.takeIf { it > 0.0 }
                ?: metaPrice?.takeIf { it > 0.0 }
                ?: history.maxByOrNull { it.date }?.price
            require(rawLatestPrice != null) { "No se encontró cotización en Rava para $symbol" }

            val jsonLatest = jsonPoints[quoteDate]?.price
            val latestPrice = if (jsonLatest != null && hasMoreDecimalPrecision(jsonLatest, rawLatestPrice)) {
                jsonLatest
            } else {
                rawLatestPrice
            }
            val previousPrice = rawPreviousPrice?.let { prev ->
                val priorJson = jsonPoints.entries
                    .filter { it.key.isBefore(quoteDate) }
                    .maxByOrNull { it.key }
                    ?.value
                    ?.price
                if (priorJson != null && hasMoreDecimalPrecision(priorJson, prev)) priorJson else prev
            }

            val points = history.toMutableList()
            val sameDateIdx = points.indexOfFirst { it.date == quoteDate }
            when {
                sameDateIdx >= 0 -> points[sameDateIdx] = MarketQuotePoint(date = quoteDate, price = latestPrice)
                points.isEmpty() || quoteDate >= points.maxOf { it.date } ->
                    points += MarketQuotePoint(date = quoteDate, price = latestPrice)
            }
            if (previousPrice != null && points.map { it.date }.distinct().size < 2) {
                points += MarketQuotePoint(date = previousBusinessDay(quoteDate), price = previousPrice)
            }

            val sortedHistory = points
                .distinctBy { it.date }
                .sortedBy { it.date }
            val latest = sortedHistory.last()

            return MarketFetch(
                marketKey = "rava:${symbol.uppercase()}",
                symbol = symbol.uppercase(),
                name = cleanName,
                source = MarketSource.RAVA,
                latestPrice = latestPrice,
                latestDate = latest.date,
                history = sortedHistory,
            )
        }

        internal fun parseHistoricoJson(json: String): List<MarketQuotePoint> {
            if (json.isBlank()) return emptyList()
            val list = mutableListOf<MarketQuotePoint>()
            for (match in JSON_OBJECT_REGEX.findAll(json)) {
                val block = match.value
                val price = JSON_PRICE_REGEX.find(block)?.groupValues?.getOrNull(1)?.toDoubleOrNull() ?: continue
                val dateStr = JSON_DATE_REGEX.find(block)?.groupValues?.getOrNull(1) ?: continue
                val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: continue
                if (price > 0.0) {
                    list += MarketQuotePoint(date = date, price = price)
                }
            }
            return list
        }

        /**
         * Rava's HTML table formats prices with 2 decimals (e.g. 128,42 for 128.415 on S30N6).
         * Prefer the JSON price when it is the unrounded 3+ decimal version of the same quote.
         */
        private fun hasMoreDecimalPrecision(jsonPrice: Double, htmlPrice: Double): Boolean {
            if (kotlin.math.abs(jsonPrice - htmlPrice) > 0.015) return false
            val twoDecRounded = kotlin.math.round(jsonPrice * 100.0) / 100.0
            return kotlin.math.abs(jsonPrice - twoDecRounded) > 1e-5
        }

        /** Parses "06/11 18:45" (or "06/11/2026") into a date, rolling over the year if needed. */
        internal fun parseQuoteDate(raw: String, fallback: LocalDate): LocalDate {
            val match = QUOTE_TIME.find(raw) ?: return fallback
            val day = match.groupValues[1].toIntOrNull() ?: return fallback
            val month = match.groupValues[2].toIntOrNull() ?: return fallback
            val explicitYear = match.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            val year = explicitYear ?: fallback.year
            var date = runCatching { LocalDate.of(year, month, day) }.getOrNull() ?: return fallback
            if (explicitYear == null && date.isAfter(fallback.plusDays(1))) {
                date = date.minusYears(1)
            }
            return date
        }

        internal fun previousBusinessDay(date: LocalDate): LocalDate {
            var previous = date.minusDays(1)
            while (previous.dayOfWeek == java.time.DayOfWeek.SATURDAY || previous.dayOfWeek == java.time.DayOfWeek.SUNDAY) {
                previous = previous.minusDays(1)
            }
            return previous
        }

        internal fun parseArNumber(raw: String): Double? {
            val clean = raw.trim()
                .replace("$", "")
                .replace("%", "")
                .replace("\u00A0", "")
                .replace(" ", "")
            if (clean.isEmpty()) return null
            val normalized = when {
                clean.contains(',') && clean.contains('.') -> clean.replace(".", "").replace(',', '.')
                clean.contains(',') -> clean.replace(',', '.')
                else -> clean
            }
            return normalized.toDoubleOrNull()
        }
    }
}

@Singleton
class CafciClient @Inject constructor(
    private val client: OkHttpClient,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    suspend fun fetch(
        fundId: String,
        classId: String,
        today: LocalDate = DateUtils.today(),
    ): MarketFetch = withContext(io) {
        val url = "https://estadisticas.cafci.org.ar/fondos/$fundId?clase=$classId"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", RavaClient.BROWSER_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "es-AR,es;q=0.9")
            .header("Referer", "https://estadisticas.cafci.org.ar/")
            .build()

        val html = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("CAFCI respondió HTTP ${response.code} para fondo $fundId/$classId")
            response.body?.string().orEmpty()
        }
        parseHtml(fundId, classId, html, today)
    }

    companion object {
        private val DATE_FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        private val VALORES_AL_REGEX = Regex("""Valores\s+al\s+(\d{2}/\d{2}/\d{4})""", RegexOption.IGNORE_CASE)
        private val VCP_UNIT_REGEX = Regex("""Valor\s+por\s+cada\s+cuotaparte:\s*([\d.,]+)""", RegexOption.IGNORE_CASE)
        private val VCP_THOUSAND_REGEX = Regex("""Valor\s+por\s+mil:\s*([\d.,]+)""", RegexOption.IGNORE_CASE)

        fun parseHtml(
            fundId: String,
            classId: String,
            html: String,
            fallbackDate: LocalDate = LocalDate.now(),
        ): MarketFetch {
            require(html.isNotBlank()) { "Respuesta vacía de CAFCI para $fundId/$classId" }
            val doc = Jsoup.parse(html)

            val dateStr = VALORES_AL_REGEX.find(html)?.groupValues?.getOrNull(1)
            val quoteDate = dateStr?.let {
                runCatching { LocalDate.parse(it, DATE_FMT) }.getOrNull()
            } ?: fallbackDate

            val unitVcp = VCP_UNIT_REGEX.find(html)?.groupValues?.getOrNull(1)?.let(RavaClient::parseArNumber)
            val thousandVcp = VCP_THOUSAND_REGEX.find(html)?.groupValues?.getOrNull(1)
                ?.let(RavaClient::parseArNumber)
                ?.let { it / 1000.0 }

            var tableClassName = ""
            var tableThousandVcp: Double? = null
            val returnWindows = mutableMapOf<Int, Double>()

            val perfTable = doc.selectFirst("table.tablaRendimientos")
            if (perfTable != null) {
                val titleCells = perfTable.select("tr.titleTable td")
                if (titleCells.size >= 2) {
                    tableClassName = titleCells[1].text().trim()
                }
                for (row in perfTable.select("tr")) {
                    val cells = row.select("td")
                    if (cells.size < 2) continue
                    val label = cells[0].text().trim().lowercase()
                    val valueText = cells[1].text().trim()
                    when {
                        label.contains("valor cuotaparte") -> {
                            tableThousandVcp = RavaClient.parseArNumber(valueText)?.let { it / 1000.0 }
                        }
                        label.contains("7 d") -> {
                            RavaClient.parseArNumber(valueText)?.let { returnWindows[7] = it }
                        }
                        label.contains("1 mes") -> {
                            RavaClient.parseArNumber(valueText)?.let { returnWindows[30] = it }
                        }
                        label.contains("90 d") -> {
                            RavaClient.parseArNumber(valueText)?.let { returnWindows[90] = it }
                        }
                        label.contains("180 d") -> {
                            RavaClient.parseArNumber(valueText)?.let { returnWindows[180] = it }
                        }
                        label.contains("12 mes") -> {
                            RavaClient.parseArNumber(valueText)?.let { returnWindows[365] = it }
                        }
                    }
                }
            }

            val latestVcp = unitVcp ?: thousandVcp ?: tableThousandVcp
                ?: error("No se pudo leer el valor de cuotaparte en CAFCI ($fundId/$classId)")

            val points = mutableListOf<MarketQuotePoint>()
            returnWindows.forEach { (days, pct) ->
                val denom = 1.0 + (pct / 100.0)
                if (denom > 0.0) {
                    val pastPrice = latestVcp / denom
                    points += MarketQuotePoint(date = quoteDate.minusDays(days.toLong()), price = pastPrice)
                }
            }
            // Populate daily VCP points across the 30-day and 7-day windows so FCI daily
            // yields are accrued smoothly day by day instead of lumping multi-day jumps.
            val priceAt7 = returnWindows[7]?.let { pct ->
                val denom = 1.0 + (pct / 100.0)
                if (denom > 0.0) latestVcp / denom else null
            }
            val priceAt30 = returnWindows[30]?.let { pct ->
                val denom = 1.0 + (pct / 100.0)
                if (denom > 0.0) latestVcp / denom else null
            }
            if (priceAt30 != null && priceAt7 != null && priceAt30 > 0.0 && priceAt7 > 0.0) {
                val dailyFactor = Math.pow(priceAt7 / priceAt30, 1.0 / 23.0)
                for (step in 1..22) {
                    points += MarketQuotePoint(
                        date = quoteDate.minusDays((30 - step).toLong()),
                        price = priceAt30 * Math.pow(dailyFactor, step.toDouble()),
                    )
                }
            }
            if (priceAt7 != null && priceAt7 > 0.0) {
                val dailyFactor = Math.pow(latestVcp / priceAt7, 1.0 / 7.0)
                for (step in 1..6) {
                    points += MarketQuotePoint(
                        date = quoteDate.minusDays((7 - step).toLong()),
                        price = priceAt7 * Math.pow(dailyFactor, step.toDouble()),
                    )
                }
            } else if (priceAt30 != null && priceAt30 > 0.0) {
                val dailyFactor = Math.pow(latestVcp / priceAt30, 1.0 / 30.0)
                for (step in 1..29) {
                    points += MarketQuotePoint(
                        date = quoteDate.minusDays((30 - step).toLong()),
                        price = priceAt30 * Math.pow(dailyFactor, step.toDouble()),
                    )
                }
            }
            points += MarketQuotePoint(date = quoteDate, price = latestVcp)

            val sortedPoints = points
                .distinctBy { it.date }
                .sortedBy { it.date }

            val name = tableClassName.ifBlank { "FCI $fundId/$classId" }
            return MarketFetch(
                marketKey = "cafci:$fundId:$classId",
                symbol = "FCI-$fundId",
                name = name,
                source = MarketSource.CAFCI,
                latestPrice = latestVcp,
                latestDate = quoteDate,
                history = sortedPoints,
            )
        }
    }
}

@Singleton
class MarketDataService @Inject constructor(
    private val ravaClient: RavaClient,
    private val cafciClient: CafciClient,
) {
    suspend fun fetchForHolding(holding: Holding, today: LocalDate = DateUtils.today()): MarketFetch? {
        val key = holding.marketKey.ifBlank {
            if (holding.source == MarketSource.RAVA && holding.symbol.isNotBlank()) {
                "rava:${holding.symbol.uppercase()}"
            } else {
                ""
            }
        }
        val parsed = MarketKeyParser.parse(key) ?: return null
        return when (parsed) {
            is ParsedMarketKey.Rava -> ravaClient.fetch(parsed.symbol, today)
            is ParsedMarketKey.Cafci -> cafciClient.fetch(parsed.fundId, parsed.classId, today)
        }
    }
}
