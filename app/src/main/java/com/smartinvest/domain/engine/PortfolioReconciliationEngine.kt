package com.smartinvest.domain.engine

import com.smartinvest.core.money.MoneyParser
import com.smartinvest.domain.model.AssetType
import com.smartinvest.domain.model.CurrencyCode
import com.smartinvest.domain.model.Holding
import com.smartinvest.domain.model.InstrumentQuote
import com.smartinvest.domain.model.MarketSource
import com.smartinvest.domain.model.QuoteUnit
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One parsed line from a manual portfolio adjustment list such as:
 * `AO27D (GALICIA) 20796.11 USD` or `S30N6 (IEB) 3817398.24 ARS`.
 */
data class ParsedHoldingAdjustment(
    val symbol: String,
    val accountTag: String,
    val valueCents: Long,
    val currency: CurrencyCode,
    val assetType: AssetType,
    val name: String,
    val marketKey: String,
    val source: MarketSource,
    val quoteUnit: QuoteUnit,
    val quantity: Double = 0.0,
    val costCents: Long = 0L,
    val maturityDate: LocalDate? = null,
    val isWallet: Boolean = false,
    val settlementDays: Int = 1,
    val movements: List<HoldingMovement> = emptyList(),
) {
    val displayLabel: String
        get() = if (accountTag.isNotBlank()) "$symbol ($accountTag)" else symbol
}

/**
 * Side-by-side comparison of a holding's exact manual balance against the app's
 * calculated quote valuation and its net invested cost basis.
 */
data class HoldingReconciliationItem(
    val holding: Holding,
    val displayLabel: String,
    val accountTag: String,
    val isDollarExposure: Boolean,
    val isManualLocked: Boolean,
    val manualDate: LocalDate?,
    val manualNativeCents: Long,
    val manualArsCents: Long,
    val manualUsdCents: Long,
    /** Theoretical valuation from `quantity × latestQuote.price` when both exist. */
    val calculatedNativeCents: Long?,
    val calculatedArsCents: Long?,
    /** `manualNativeCents - calculatedNativeCents` when a calculated value exists. */
    val diffNativeCents: Long?,
    val diffArsCents: Long?,
    val diffPercent: Double?,
    /** Price implied by `manualNativeCents / (quantity × quoteFactor)`. */
    val impliedPrice: Double?,
    val latestMarketPrice: Double?,
    val latestMarketDate: LocalDate?,
    /** Evolution vs. net invested cost (`costCents`). */
    val costNativeCents: Long,
    val costArsCents: Long,
    val gainNativeCents: Long?,
    val gainArsCents: Long?,
    val gainPercent: Double?,
)

/**
 * Full portfolio reconciliation report: exact ARS and USD totals, 60/40 exposure
 * (with SPY, AO27, AO27D and USD funds in the dollarized bucket), and calculation
 * verification per asset.
 */
data class PortfolioReconciliationReport(
    val items: List<HoldingReconciliationItem> = emptyList(),
    val mepRate: Double = 1_000.0,
    val mepIsEstimated: Boolean = true,
    /** Sum of holdings whose native currency is ARS. */
    val nativeArsTotalCents: Long = 0L,
    /** Sum of holdings whose native currency is USD. */
    val nativeUsdTotalCents: Long = 0L,
    /** Total portfolio valued in ARS (`nativeArs + nativeUsd * mep`). */
    val totalArsCents: Long = 0L,
    /** Total portfolio valued in USD (`totalArs / mep`). */
    val totalUsdCents: Long = 0L,
    /** Pure peso exposure (excludes USD holdings, CEDEARs like SPY, and bonds like AO27/AO27D). */
    val pesoExposureArsCents: Long = 0L,
    val pesoExposurePercent: Double = 0.0,
    /** Dollarized exposure (USD holdings + SPY + bonds AO27/AO27D + USD FCIs), valued in ARS and USD. */
    val dollarExposureArsCents: Long = 0L,
    val dollarExposureUsdCents: Long = 0L,
    val dollarExposurePercent: Double = 0.0,
    /** Sum of `calculatedArsCents` (falling back to `manualArsCents` when a holding has no quantity/quote). */
    val totalCalculatedArsCents: Long = 0L,
    /** Total discrepancy `totalArsCents - totalCalculatedArsCents`. */
    val totalDiffArsCents: Long = 0L,
    /** Total net cost and unrealized gain across holdings with cost > 0. */
    val totalTrackedCostArsCents: Long = 0L,
    val totalTrackedValueArsCents: Long = 0L,
    val totalUnrealizedGainArsCents: Long = 0L,
    val totalUnrealizedGainPercent: Double? = null,
)

object PortfolioReconciliationEngine {

    private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val MANUAL_LOCK_REGEX = Regex(
        """^\[ajuste manual(?::\s*(\d{2}/\d{2}/\d{4}))?\]$""",
        RegexOption.IGNORE_CASE,
    )
    private val PAREN_TAG_REGEX = Regex("""\(([^)]+)\)""")
    private val LINE_REGEX = Regex(
        "^\\s*(?:[-·*•]\\s*)?([A-Za-z0-9._-]+)(?:\\s*\\(([^)]+)\\))?\\s+(?:US\\$|\\$)?\\s*([\\d.,]+)\\s*(ARS|USD|U\\\$S|US\\$|\\$|PESOS|DOLARES|DÓLARES)?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    const val DEFAULT_2026_11_14_LIST: String = """
AO27D (GALICIA) 20796.11 USD
S30N6 (GALICIA) 4061874.52 ARS
MP-ARS 615166.58 ARS
MP-USD 7111.63 ARS
ADCAP-BAL3 74814083 ARS
T15E7 781899.42 ARS
AO27 778787.10 ARS
SPY 14165760 ARS
S30N6 (IEB) 3817398.24 ARS
"""

    fun isManualValuationLocked(holding: Holding): Boolean =
        isManualValuationLocked(holding.notes)

    fun isManualValuationLocked(notes: String): Boolean =
        notes.lineSequence().any { MANUAL_LOCK_REGEX.matches(it.trim()) }

    fun manualValuationDate(holding: Holding): LocalDate? =
        manualValuationDate(holding.notes)

    fun manualValuationDate(notes: String): LocalDate? {
        for (line in notes.lineSequence()) {
            val match = MANUAL_LOCK_REGEX.matchEntire(line.trim()) ?: continue
            val dateText = match.groupValues.getOrNull(1).orEmpty()
            if (dateText.isNotBlank()) {
                runCatching { LocalDate.parse(dateText, DATE_FMT) }.getOrNull()?.let { return it }
            }
        }
        return null
    }

    fun withManualValuationLock(
        notes: String,
        locked: Boolean,
        date: LocalDate = LocalDate.of(2026, 11, 14),
    ): String {
        val cleaned = notes.lineSequence()
            .map { it.trimEnd() }
            .filterNot { MANUAL_LOCK_REGEX.matches(it.trim()) }
            .joinToString("\n")
            .trim()
        if (!locked) return cleaned
        val tag = "[ajuste manual: ${date.format(DATE_FMT)}]"
        return if (cleaned.isEmpty()) tag else "$cleaned\n$tag"
    }

    /**
     * Extracts a short account/broker tag such as `GALICIA` or `IEB` from a holding's
     * name or notes, ignoring movement log lines and manual-lock markers.
     */
    fun accountTag(holding: Holding): String {
        PAREN_TAG_REGEX.find(holding.name)?.groupValues?.getOrNull(1)?.trim()?.let { inName ->
            if (inName.isNotBlank() && !inName.equals("Clase A", ignoreCase = true)) {
                return inName.uppercase()
            }
        }
        val firstPlainNote = holding.notes.lineSequence()
            .map { it.trim() }
            .firstOrNull { line ->
                line.isNotEmpty() &&
                    !MANUAL_LOCK_REGEX.matches(line) &&
                    BrokerMovementImporter.parseMovementNotes(line).isEmpty() &&
                    line.length <= 24
            }
        return firstPlainNote?.uppercase().orEmpty()
    }

    fun displayLabel(holding: Holding): String {
        val sym = holding.symbol.trim().ifBlank { holding.name.trim() }
        val tag = accountTag(holding)
        return if (tag.isNotBlank() && !sym.contains(tag, ignoreCase = true)) {
            "$sym ($tag)"
        } else {
            sym
        }
    }

    /**
     * Exact 9 holdings from the user's 14/11/2026 update, enriched with their known
     * broker quantities, cost basis and movement history.
     */
    fun presetHoldings2026_11_14(): List<ParsedHoldingAdjustment> =
        parseAdjustmentText(DEFAULT_2026_11_14_LIST)

    /**
     * Parses a multi-line list of manual balances (`TICKER (CUENTA) MONTO ARS/USD`).
     */
    fun parseAdjustmentText(rawText: String): List<ParsedHoldingAdjustment> {
        val adcapSummary = BrokerMovementImporter.summarize(BrokerMovementImporter.ecoValoresAdcapBal3Movements)
        val spySummary = BrokerMovementImporter.summarize(BrokerMovementImporter.iebSpyMovements)
        val s30IebSummary = BrokerMovementImporter.summarize(BrokerMovementImporter.iebS30n6Movements)
        val t15Summary = BrokerMovementImporter.summarize(BrokerMovementImporter.iebT15e7Movements)
        val ao27Summary = BrokerMovementImporter.summarize(BrokerMovementImporter.iebAo27Movements)

        return rawText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val match = LINE_REGEX.matchEntire(line) ?: return@mapNotNull null
                val rawSymbol = match.groupValues[1].trim().uppercase()
                val rawTag = match.groupValues[2].trim().uppercase()
                val rawAmount = match.groupValues[3].trim()
                val rawCurr = match.groupValues[4].trim().uppercase()

                val cents = parseExactCents(rawAmount) ?: return@mapNotNull null
                val currency = when {
                    rawCurr in setOf("USD", "U\$S", "US\$", "DOLARES", "DÓLARES") -> CurrencyCode.USD
                    rawCurr in setOf("ARS", "\$", "PESOS") -> CurrencyCode.ARS
                    rawSymbol.endsWith("D") && rawSymbol != "MP-USD" -> CurrencyCode.USD
                    else -> CurrencyCode.ARS
                }

                buildPresetSpec(
                    symbol = rawSymbol,
                    accountTag = rawTag,
                    valueCents = cents,
                    currency = currency,
                    adcapSummary = adcapSummary,
                    spySummary = spySummary,
                    s30IebSummary = s30IebSummary,
                    t15Summary = t15Summary,
                    ao27Summary = ao27Summary,
                )
            }
            .toList()
    }

    internal fun parseExactCents(raw: String): Long? {
        val clean = raw.trim().replace(" ", "").removePrefix("US$").removePrefix("$")
        if (clean.isEmpty()) return null
        return when {
            clean.contains(',') && clean.contains('.') -> {
                val normalized = clean.replace(".", "").replace(',', '.')
                normalized.toDoubleOrNull()?.let { (it * 100.0).roundToLong() }
            }
            clean.contains(',') -> {
                val normalized = clean.replace(',', '.')
                normalized.toDoubleOrNull()?.let { (it * 100.0).roundToLong() }
            }
            clean.contains('.') -> {
                val dotCount = clean.count { it == '.' }
                val afterLastDot = clean.substringAfterLast('.')
                if (dotCount == 1 && afterLastDot.length in 1..2) {
                    clean.toDoubleOrNull()?.let { (it * 100.0).roundToLong() }
                } else {
                    MoneyParser.parseToCents(clean)
                }
            }
            else -> {
                clean.toLongOrNull()?.let { it * 100L }
            }
        }
    }

    private fun buildPresetSpec(
        symbol: String,
        accountTag: String,
        valueCents: Long,
        currency: CurrencyCode,
        adcapSummary: BrokerImportSummary,
        spySummary: BrokerImportSummary,
        s30IebSummary: BrokerImportSummary,
        t15Summary: BrokerImportSummary,
        ao27Summary: BrokerImportSummary,
    ): ParsedHoldingAdjustment = when {
        symbol == "AO27D" -> ParsedHoldingAdjustment(
            symbol = "AO27D",
            accountTag = accountTag.ifBlank { "GALICIA" },
            valueCents = valueCents,
            currency = currency,
            assetType = AssetType.BOND,
            name = if (accountTag.isNotBlank()) "Bonar 2027 U\$S ($accountTag)" else "Bonar 2027 U\$S",
            marketKey = "rava:AO27D",
            source = MarketSource.RAVA,
            quoteUnit = QuoteUnit.PER_100_NOMINAL,
            costCents = valueCents,
        )
        symbol == "AO27" -> ParsedHoldingAdjustment(
            symbol = "AO27",
            accountTag = accountTag.ifBlank { "IEB" },
            valueCents = valueCents,
            currency = currency,
            assetType = AssetType.BOND,
            name = "Bonar 2027 \$ (${accountTag.ifBlank { "IEB" }})",
            marketKey = "rava:AO27",
            source = MarketSource.RAVA,
            quoteUnit = QuoteUnit.PER_100_NOMINAL,
            quantity = ao27Summary.netQuantity,
            costCents = ao27Summary.netInvestedCents,
            movements = ao27Summary.movements,
        )
        symbol == "S30N6" && accountTag.contains("GALICIA", ignoreCase = true) -> ParsedHoldingAdjustment(
            symbol = "S30N6",
            accountTag = "GALICIA",
            valueCents = valueCents,
            currency = currency,
            assetType = AssetType.LECAP,
            name = "Letra del Tesoro S30N6 (GALICIA)",
            marketKey = "rava:S30N6",
            source = MarketSource.RAVA,
            quoteUnit = QuoteUnit.PER_100_NOMINAL,
            quantity = 3_154_364.0,
            costCents = valueCents,
            maturityDate = LocalDate.of(2026, 11, 30),
        )
        symbol == "S30N6" -> ParsedHoldingAdjustment(
            symbol = "S30N6",
            accountTag = accountTag.ifBlank { "IEB" },
            valueCents = valueCents,
            currency = currency,
            assetType = AssetType.LECAP,
            name = "Letra del Tesoro S30N6 (${accountTag.ifBlank { "IEB" }})",
            marketKey = "rava:S30N6",
            source = MarketSource.RAVA,
            quoteUnit = QuoteUnit.PER_100_NOMINAL,
            quantity = s30IebSummary.netQuantity,
            costCents = s30IebSummary.netInvestedCents,
            maturityDate = LocalDate.of(2026, 11, 30),
            movements = s30IebSummary.movements,
        )
        symbol == "T15E7" -> ParsedHoldingAdjustment(
            symbol = "T15E7",
            accountTag = accountTag.ifBlank { "IEB" },
            valueCents = valueCents,
            currency = currency,
            assetType = AssetType.LECAP,
            name = "Letra del Tesoro T15E7",
            marketKey = "rava:T15E7",
            source = MarketSource.RAVA,
            quoteUnit = QuoteUnit.PER_100_NOMINAL,
            quantity = t15Summary.netQuantity,
            costCents = t15Summary.netInvestedCents,
            maturityDate = LocalDate.of(2027, 1, 15),
            movements = t15Summary.movements,
        )
        symbol == "SPY" -> ParsedHoldingAdjustment(
            symbol = "SPY",
            accountTag = accountTag.ifBlank { "IEB" },
            valueCents = valueCents,
            currency = currency,
            assetType = AssetType.CEDEAR,
            name = "SPDR S&P 500 CEDEAR",
            marketKey = "rava:SPY",
            source = MarketSource.RAVA,
            quoteUnit = QuoteUnit.PER_UNIT,
            quantity = spySummary.netQuantity,
            costCents = spySummary.netInvestedCents,
            movements = spySummary.movements,
        )
        symbol == "ADCAP-BAL3" || symbol.contains("ADCAP") -> ParsedHoldingAdjustment(
            symbol = "ADCAP-BAL3",
            accountTag = accountTag,
            valueCents = valueCents,
            currency = currency,
            assetType = AssetType.FCI,
            name = "Adcap Balanceado III - Clase A",
            marketKey = "cafci:1402:4070",
            source = MarketSource.CAFCI,
            quoteUnit = QuoteUnit.PER_UNIT,
            costCents = adcapSummary.netInvestedCents,
            settlementDays = 1,
            movements = adcapSummary.movements,
        )
        symbol == "MP-ARS" -> ParsedHoldingAdjustment(
            symbol = "MP-ARS",
            accountTag = accountTag,
            valueCents = valueCents,
            currency = currency,
            assetType = AssetType.FCI,
            name = "Mercado Fondo Ahorro Pesos",
            marketKey = "cafci:798:1982",
            source = MarketSource.CAFCI,
            quoteUnit = QuoteUnit.PER_UNIT,
            costCents = valueCents,
            isWallet = true,
            settlementDays = 0,
        )
        symbol == "MP-USD" -> ParsedHoldingAdjustment(
            symbol = "MP-USD",
            accountTag = accountTag,
            valueCents = valueCents,
            currency = currency,
            assetType = AssetType.FCI,
            name = "Mercado Fondo Dólares",
            marketKey = "cafci:1522:4557",
            source = MarketSource.CAFCI,
            quoteUnit = QuoteUnit.PER_UNIT,
            costCents = valueCents,
            settlementDays = 0,
        )
        else -> ParsedHoldingAdjustment(
            symbol = symbol,
            accountTag = accountTag,
            valueCents = valueCents,
            currency = currency,
            assetType = AssetType.OTHER,
            name = if (accountTag.isNotBlank()) "$symbol ($accountTag)" else symbol,
            marketKey = "",
            source = MarketSource.MANUAL,
            quoteUnit = QuoteUnit.PER_UNIT,
            costCents = valueCents,
        )
    }

    /**
     * Builds the complete reconciliation and calculation-check report for [holdings].
     */
    fun buildReport(
        holdings: List<Holding>,
        quotes: List<InstrumentQuote>,
        mepRate: Double,
        mepIsEstimated: Boolean = false,
    ): PortfolioReconciliationReport {
        val safeMep = mepRate.takeIf { it > 0.0 } ?: 1_000.0
        val items = holdings.map { holding ->
            val tag = accountTag(holding)
            val label = displayLabel(holding)
            val isDollar = PortfolioValuationEngine.isDollarExposure(holding)
            val locked = isManualValuationLocked(holding)
            val manualDate = manualValuationDate(holding)

            val manualNative = holding.currentValueCents
            val manualArs = if (holding.currency == CurrencyCode.ARS) {
                manualNative
            } else {
                (manualNative * safeMep).roundToLong()
            }
            val manualUsd = if (holding.currency == CurrencyCode.USD) {
                manualNative
            } else {
                (manualNative / safeMep).roundToLong()
            }

            val series = if (holding.marketKey.isNotBlank()) {
                PortfolioValuationEngine.quotesFor(holding, quotes)
            } else {
                emptyList()
            }
            val latestQuote = series.lastOrNull()
            val calculatedNative = if (holding.quantity > 0.0 && latestQuote != null && latestQuote.price > 0.0) {
                holding.valueCentsFor(latestQuote.price)
            } else {
                null
            }
            val calculatedArs = calculatedNative?.let { nat ->
                if (holding.currency == CurrencyCode.ARS) nat else (nat * safeMep).roundToLong()
            }
            val diffNative = calculatedNative?.let { manualNative - it }
            val diffArs = calculatedArs?.let { manualArs - it }
            val diffPct = if (calculatedNative != null && calculatedNative > 0L && diffNative != null) {
                diffNative.toDouble() * 100.0 / calculatedNative.toDouble()
            } else {
                null
            }

            val impliedPrice = if (holding.quantity > 0.0 && manualNative > 0L) {
                val denom = holding.quantity * holding.quoteFactor * 100.0
                if (denom > 0.0) manualNative.toDouble() / denom else null
            } else {
                null
            }

            val costNative = holding.costCents
            val costArs = if (holding.currency == CurrencyCode.ARS) {
                costNative
            } else {
                (costNative * safeMep).roundToLong()
            }
            val hasMeaningfulCost = costNative > 0L && costNative != manualNative
            val gainNative = if (costNative > 0L) manualNative - costNative else null
            val gainArs = if (costArs > 0L) manualArs - costArs else null
            val gainPct = if (costNative > 0L && gainNative != null) {
                gainNative.toDouble() * 100.0 / costNative.toDouble()
            } else {
                null
            }

            HoldingReconciliationItem(
                holding = holding,
                displayLabel = label,
                accountTag = tag,
                isDollarExposure = isDollar,
                isManualLocked = locked,
                manualDate = manualDate,
                manualNativeCents = manualNative,
                manualArsCents = manualArs,
                manualUsdCents = manualUsd,
                calculatedNativeCents = calculatedNative,
                calculatedArsCents = calculatedArs,
                diffNativeCents = diffNative,
                diffArsCents = diffArs,
                diffPercent = diffPct,
                impliedPrice = impliedPrice,
                latestMarketPrice = latestQuote?.price,
                latestMarketDate = latestQuote?.date,
                costNativeCents = if (hasMeaningfulCost || costNative > 0L) costNative else 0L,
                costArsCents = if (hasMeaningfulCost || costArs > 0L) costArs else 0L,
                gainNativeCents = gainNative,
                gainArsCents = gainArs,
                gainPercent = gainPct,
            )
        }

        val nativeArs = items
            .filter { it.holding.currency == CurrencyCode.ARS }
            .sumOf { it.manualNativeCents }
        val nativeUsd = items
            .filter { it.holding.currency == CurrencyCode.USD }
            .sumOf { it.manualNativeCents }

        val totalArs = items.sumOf { it.manualArsCents }
        val totalUsd = (totalArs / safeMep).roundToLong()

        val dollarArs = items.filter { it.isDollarExposure }.sumOf { it.manualArsCents }
        val dollarUsd = (dollarArs / safeMep).roundToLong()
        val pesoArs = (totalArs - dollarArs).coerceAtLeast(0L)

        val pesoPct = if (totalArs > 0L) pesoArs * 100.0 / totalArs else 0.0
        val dollarPct = if (totalArs > 0L) 100.0 - pesoPct else 0.0

        val totalCalculatedArs = items.sumOf { it.calculatedArsCents ?: it.manualArsCents }
        val totalDiffArs = totalArs - totalCalculatedArs

        val withCost = items.filter { it.costArsCents > 0L }
        val trackedCostArs = withCost.sumOf { it.costArsCents }
        val trackedValArs = withCost.sumOf { it.manualArsCents }
        val totalGainArs = trackedValArs - trackedCostArs
        val totalGainPct = if (trackedCostArs > 0L) {
            totalGainArs.toDouble() * 100.0 / trackedCostArs.toDouble()
        } else {
            null
        }

        return PortfolioReconciliationReport(
            items = items,
            mepRate = safeMep,
            mepIsEstimated = mepIsEstimated,
            nativeArsTotalCents = nativeArs,
            nativeUsdTotalCents = nativeUsd,
            totalArsCents = totalArs,
            totalUsdCents = totalUsd,
            pesoExposureArsCents = pesoArs,
            pesoExposurePercent = pesoPct,
            dollarExposureArsCents = dollarArs,
            dollarExposureUsdCents = dollarUsd,
            dollarExposurePercent = dollarPct,
            totalCalculatedArsCents = totalCalculatedArs,
            totalDiffArsCents = totalDiffArs,
            totalTrackedCostArsCents = trackedCostArs,
            totalTrackedValueArsCents = trackedValArs,
            totalUnrealizedGainArsCents = totalGainArs,
            totalUnrealizedGainPercent = totalGainPct,
        )
    }

    /**
     * Builds the `Holding.notes` string combining an optional account tag (`GALICIA` / `IEB`),
     * movement log lines, and the manual valuation lock tag.
     */
    fun composeNotes(
        existingNotes: String,
        accountTag: String,
        movementsBlock: String,
        manualLocked: Boolean,
        manualDate: LocalDate = LocalDate.of(2026, 11, 14),
    ): String {
        val plainLines = existingNotes.lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.isNotEmpty() &&
                    !MANUAL_LOCK_REGEX.matches(line) &&
                    BrokerMovementImporter.parseMovementNotes(line).isEmpty()
            }
            .toMutableList()
        if (accountTag.isNotBlank() && plainLines.none { it.equals(accountTag, ignoreCase = true) }) {
            plainLines.add(0, accountTag)
        }
        val base = buildString {
            if (plainLines.isNotEmpty()) {
                append(plainLines.joinToString("\n"))
            }
            if (movementsBlock.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(movementsBlock.trim())
            }
        }
        return withManualValuationLock(base, locked = manualLocked, date = manualDate)
    }
}
