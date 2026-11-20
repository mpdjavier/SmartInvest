package com.smartinvest.domain.model

import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.pow

/**
 * One monthly row of the BCRA Market Expectations Survey (REM).
 *
 * The three values are the median of the survey:
 * - [inflationMonthlyPercent]: expected monthly CPI variation.
 * - [officialFx]: expected average official exchange rate in ARS per USD.
 * - [tamarTnaPercent]: expected TAMAR nominal annual rate.
 *
 * [month] is stored as `yyyy-MM` so the model can be serialized in DataStore.
 */
@Serializable
data class RemMonthPoint(
    val month: String,
    val inflationMonthlyPercent: Double = 0.0,
    val officialFx: Double = 0.0,
    val tamarTnaPercent: Double = 0.0,
) {
    val yearMonth: YearMonth?
        get() = runCatching { YearMonth.parse(month) }.getOrNull()
}

/**
 * REM snapshot used by the short-term instrument comparator.
 *
 * The app ships with the October 2026 REM (published 5 Nov 2026). The official
 * XLSX can be imported from the BCRA to refresh the path without waiting for an
 * app update.
 */
@Serializable
data class RemSnapshot(
    val publishedAt: String = "",
    val reference: String = "",
    val sourceUrl: String = "",
    val points: List<RemMonthPoint> = emptyList(),
) {
    private val validPoints: List<RemMonthPoint>
        get() = points
            .mapNotNull { point -> point.yearMonth?.let { it to point } }
            .sortedBy { it.first }
            .map { it.second }

    val firstMonth: YearMonth? get() = validPoints.firstOrNull()?.yearMonth
    val lastMonth: YearMonth? get() = validPoints.lastOrNull()?.yearMonth

    val publishedDate: LocalDate?
        get() = if (publishedAt.isBlank()) {
            null
        } else {
            runCatching { LocalDate.parse(publishedAt) }.getOrNull()
        }

    fun pointFor(month: YearMonth): RemMonthPoint? =
        validPoints.firstOrNull { it.yearMonth == month }

    fun monthlyInflation(month: YearMonth): Double? =
        pointFor(month)?.inflationMonthlyPercent?.takeIf { it > 0.0 }

    fun tamar(month: YearMonth): Double? =
        pointFor(month)?.tamarTnaPercent?.takeIf { it > 0.0 }

    /**
     * Interpolated official FX for [date]. Each REM value is treated as the
     * average of its month: the path moves linearly from the previous month's
     * value to the current one as the month advances.
     */
    fun fxAt(date: LocalDate): Double? {
        val fxPoints = validPoints
            .mapNotNull { point -> point.yearMonth?.let { it to point.officialFx } }
            .filter { it.second > 0.0 }
            .sortedBy { it.first }
        if (fxPoints.isEmpty()) return null

        val month = YearMonth.from(date)
        val current = fxPoints.firstOrNull { it.first == month }?.second
        val previous = fxPoints.lastOrNull { it.first < month }?.second
        val next = fxPoints.firstOrNull { it.first > month }?.second

        return when {
            current != null -> {
                val base = previous ?: current
                val fraction = (date.dayOfMonth.toDouble() / date.lengthOfMonth()).coerceIn(0.0, 1.0)
                base + (current - base) * fraction
            }
            previous != null && next != null -> previous + (next - previous) * 0.5
            previous != null -> previous
            else -> fxPoints.first().second
        }
    }

    /**
     * Accumulated inflation factor between [from] and [to] using the REM monthly
     * path. Partial months are accrued by the fraction of days covered.
     */
    fun inflationFactor(from: LocalDate, to: LocalDate): Double? {
        if (!to.isAfter(from)) return 1.0
        val monthly = validPoints
            .mapNotNull { point -> point.yearMonth?.let { it to point.inflationMonthlyPercent / 100.0 } }
            .filter { it.second > 0.0 }
            .toMap()
        if (monthly.isEmpty()) return null

        var factor = 1.0
        var cursor = from
        while (cursor.isBefore(to)) {
            val month = YearMonth.from(cursor)
            val monthEnd = month.atEndOfMonth()
            val segmentEnd = if (monthEnd.isBefore(to)) monthEnd else to
            val days = ChronoUnit.DAYS.between(cursor, segmentEnd) + 1L
            val share = (days.toDouble() / month.lengthOfMonth()).coerceIn(0.0, 1.0)
            val monthlyRate = monthly[month] ?: 0.0
            if (monthlyRate > 0.0 && share > 0.0) {
                factor *= (1.0 + monthlyRate).pow(share)
            }
            cursor = segmentEnd.plusDays(1)
        }
        return factor
    }
}

/** Instrument families compared by the REM module. */
enum class RemInstrumentKind(
    val label: String,
    val description: String,
) {
    PESO_FIXED(
        "Tasa fija ARS",
        "LECAP / BONCAP: TIR nominal efectiva a vencimiento.",
    ),
    PESO_TNA(
        "TNA en pesos",
        "FCI, cuenta remunerada o caución: TNA nominal con devengamiento diario.",
    ),
    CER(
        "Bono CER",
        "Capital ajustado por inflación + TIR real.",
    ),
    DOLLAR_LINKED(
        "Dollar linked",
        "Paga en pesos al oficial: devaluación + spread.",
    ),
    USD_BOND(
        "Bono USD",
        "Rinde en dólares; el retorno en pesos depende del FX.",
    ),
}

/**
 * Editable assumption for one short-term instrument. Percentages are stored as
 * `26.74` (not `0.2674`); dates as ISO `yyyy-MM-dd`.
 */
@Serializable
data class RemInstrument(
    val id: String,
    val symbol: String,
    val name: String,
    val kind: String = RemInstrumentKind.PESO_FIXED.name,
    val maturity: String = "",
    val annualYieldPercent: Double = 0.0,
    /** `true` = effective annual yield (TIR); `false` = nominal annual rate (TNA). */
    val yieldIsEffective: Boolean = true,
    val notes: String = "",
    val isActive: Boolean = true,
) {
    val kindType: RemInstrumentKind
        get() = runCatching { RemInstrumentKind.valueOf(kind) }
            .getOrDefault(RemInstrumentKind.PESO_FIXED)

    val maturityDate: LocalDate?
        get() = if (maturity.isBlank()) {
            null
        } else {
            runCatching { LocalDate.parse(maturity) }.getOrNull()
        }

    val yieldLabel: String get() = if (yieldIsEffective) "TIR" else "TNA"
}

/** Persisted configuration of the REM comparator. */
@Serializable
data class RemConfig(
    val snapshot: RemSnapshot = RemDefaults.snapshot,
    val instruments: List<RemInstrument> = RemDefaults.instruments,
    val crawlingPegMonthlyPercent: Double = RemDefaults.DEFAULT_CRAWLING_PEG,
    val horizonMonth: String = RemDefaults.DEFAULT_HORIZON,
) {
    val horizon: YearMonth
        get() = runCatching { YearMonth.parse(horizonMonth) }
            .getOrDefault(YearMonth.parse(RemDefaults.DEFAULT_HORIZON))
}

enum class RemVerdict(val label: String, val emoji: String) {
    CONVIENE("Conviene", "🟢"),
    EMPATA("Empata", "🟡"),
    NO_CONVIENE("No conviene", "🔴"),
}

data class RemInstrumentComparison(
    val instrument: RemInstrument,
    val kind: RemInstrumentKind,
    val effectiveDate: LocalDate,
    val days: Long,
    val months: Double,
    /** Expected nominal return in ARS until the effective date. */
    val expectedArsReturnPercent: Double,
    /** Geometric monthly equivalent of the nominal return. */
    val expectedMonthlyReturnPercent: Double,
    /** Return measured in USD at the REM FX path. */
    val expectedUsdReturnPercent: Double,
    /** Return above the REM inflation path. */
    val realReturnPercent: Double,
    /** Excess return over the fictitious crawling peg, in percentage points. */
    val vsCrawlingPegPp: Double,
    /** Constant monthly devaluation at which holding USD would tie this instrument. */
    val breakEvenMonthlyDevaluationPercent: Double,
    /** Starting exchange rate at [today] ($/USD) used as the base for break-even FX. */
    val fxToday: Double = 0.0,
    /** Break-even dollar value ($/USD) at [effectiveDate] where holding USD ties this instrument. */
    val breakEvenFx: Double = 0.0,
    /** Total cumulative FX increase (%) from [today] to [effectiveDate] that ties this instrument. */
    val breakEvenDevaluationPercent: Double = expectedArsReturnPercent,
    /** REM-projected official FX ($/USD) at [effectiveDate]. */
    val remFxAtEffectiveDate: Double = 0.0,
    /** REM cumulative FX increase (%) from [today] to [effectiveDate]. */
    val remDevaluationPercent: Double = 0.0,
    /** REM monthly equivalent FX increase (%) from [today] to [effectiveDate]. */
    val remMonthlyDevaluationPercent: Double = 0.0,
    /** Crawling-peg projected FX ($/USD) at [effectiveDate] starting from [fxToday]. */
    val pegFxAtEffectiveDate: Double = 0.0,
    /** Crawling-peg cumulative FX increase (%) from [today] to [effectiveDate]. */
    val pegDevaluationPercent: Double = 0.0,
    /** Difference in $/USD between [breakEvenFx] and [remFxAtEffectiveDate] (>0 = beats REM). */
    val breakEvenVsRemFxDiff: Double = 0.0,
    /** Difference in $/USD between [breakEvenFx] and [pegFxAtEffectiveDate] (>0 = beats peg). */
    val breakEvenVsPegFxDiff: Double = 0.0,
    val verdict: RemVerdict,
    val detail: String,
)

data class RemComparison(
    val snapshot: RemSnapshot,
    val today: LocalDate,
    val horizon: YearMonth,
    val horizonDate: LocalDate,
    val requestedHorizon: YearMonth,
    val horizonCapped: Boolean,
    val crawlingPegMonthlyPercent: Double,
    val months: Double,
    val pegReturnPercent: Double,
    val remFxToday: Double,
    val remFxAtHorizon: Double,
    val remDepreciationPercent: Double,
    val remMonthlyDepreciationPercent: Double,
    val remInflationPercent: Double,
    val rows: List<RemInstrumentComparison>,
    val best: RemInstrumentComparison?,
    val recommendation: String,
    val warnings: List<String>,
) {
    val hasFx: Boolean get() = remFxToday > 0.0
}

/**
 * Bundled values from the October 2026 REM (BCRA, published 5 Nov 2026) and
 * market yields observed in mid-November 2026. Everything is editable in the
 * comparator: the REM XLSX can be re-imported and each instrument's yield can
 * be refreshed without touching the code.
 */
object RemDefaults {
    const val SOURCE_URL =
        "https://www.bcra.gob.ar/publicaciones/relevamiento-de-expectativas-de-mercado-rem-octubre-de-2026/"
    const val DEFAULT_HORIZON = "2027-04"
    const val DEFAULT_CRAWLING_PEG = 1.0

    val snapshot: RemSnapshot = RemSnapshot(
        publishedAt = "2026-11-05",
        reference = "REM octubre 2026 · BCRA",
        sourceUrl = SOURCE_URL,
        points = listOf(
            RemMonthPoint("2026-11", inflationMonthlyPercent = 1.70, officialFx = 1558.48, tamarTnaPercent = 23.90),
            RemMonthPoint("2026-12", inflationMonthlyPercent = 1.84, officialFx = 1590.00, tamarTnaPercent = 24.00),
            RemMonthPoint("2027-01", inflationMonthlyPercent = 1.70, officialFx = 1618.83, tamarTnaPercent = 23.95),
            RemMonthPoint("2027-02", inflationMonthlyPercent = 1.60, officialFx = 1649.18, tamarTnaPercent = 23.25),
            RemMonthPoint("2027-03", inflationMonthlyPercent = 1.70, officialFx = 1676.89, tamarTnaPercent = 23.54),
            RemMonthPoint("2027-04", inflationMonthlyPercent = 1.66, officialFx = 1701.54, tamarTnaPercent = 23.20),
        ),
    )

    val instruments: List<RemInstrument> = listOf(
        RemInstrument(
            id = "s30n6",
            symbol = "S30N6",
            name = "LECAP S30N6",
            kind = RemInstrumentKind.PESO_FIXED.name,
            maturity = "2026-11-30",
            annualYieldPercent = 26.74,
            notes = "TIR de mercado observada el 16/11/2026.",
        ),
        RemInstrument(
            id = "t15e7",
            symbol = "T15E7",
            name = "BONCAP T15E7",
            kind = RemInstrumentKind.PESO_FIXED.name,
            maturity = "2027-01-15",
            annualYieldPercent = 26.43,
            notes = "TIR de mercado observada el 16/11/2026.",
        ),
        RemInstrument(
            id = "s26f7",
            symbol = "S26F7",
            name = "LECAP S26F7",
            kind = RemInstrumentKind.PESO_FIXED.name,
            maturity = "2027-02-26",
            annualYieldPercent = 27.91,
            notes = "TIR de mercado observada el 16/11/2026.",
        ),
        RemInstrument(
            id = "t30a7",
            symbol = "T30A7",
            name = "BONCAP T30A7",
            kind = RemInstrumentKind.PESO_FIXED.name,
            maturity = "2027-04-30",
            annualYieldPercent = 29.87,
            notes = "TIR de mercado observada el 16/11/2026. Vence en el límite del horizonte.",
        ),
        RemInstrument(
            id = "x29e7",
            symbol = "X29E7",
            name = "LECER X29E7",
            kind = RemInstrumentKind.CER.name,
            maturity = "2027-01-29",
            annualYieldPercent = 1.80,
            notes = "TIR real observada el 11/11/2026.",
        ),
        RemInstrument(
            id = "tzxa7",
            symbol = "TZXA7",
            name = "BONCER TZXA7",
            kind = RemInstrumentKind.CER.name,
            maturity = "2027-04-30",
            annualYieldPercent = 1.52,
            notes = "TIR real observada el 12/11/2026.",
        ),
        RemInstrument(
            id = "d15e7",
            symbol = "D15E7",
            name = "Dollar linked D15E7",
            kind = RemInstrumentKind.DOLLAR_LINKED.name,
            maturity = "2027-01-15",
            annualYieldPercent = 5.20,
            notes = "TIR observada el 12/11/2026. Paga en pesos al oficial.",
        ),
    )
}
