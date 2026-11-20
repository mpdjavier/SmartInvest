package com.smartinvest.domain.engine

import com.smartinvest.core.time.DateUtils
import com.smartinvest.domain.model.RemComparison
import com.smartinvest.domain.model.RemConfig
import com.smartinvest.domain.model.RemInstrument
import com.smartinvest.domain.model.RemInstrumentComparison
import com.smartinvest.domain.model.RemInstrumentKind
import com.smartinvest.domain.model.RemSnapshot
import com.smartinvest.domain.model.RemVerdict
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/** Serializes the editable REM configuration into `AppSettings.remConfigJson`. */
object RemConfigCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(config: RemConfig): String = json.encodeToString(config)

    fun decode(raw: String): RemConfig {
        val clean = raw.trim()
        if (clean.isEmpty()) return RemConfig()
        return runCatching { json.decodeFromString<RemConfig>(clean) }
            .getOrDefault(RemConfig())
    }
}

/**
 * Compares short-term instruments against a fictitious crawling peg.
 *
 * Every row is projected to a common horizon (never beyond April 2027, because
 * of the electoral calendar) or to the instrument's maturity when it comes
 * earlier. The nominal return in ARS is then compared with:
 *
 *   benchmark = (1 + peg / 100) ^ months
 *
 * where the peg defaults to 1% per month. A fixed-rate LECAP compounds its TIR;
 * a CER bond adds the REM inflation path to its real TIR; a dollar-linked bond
 * adds the REM official-FX path to its spread; and a USD bond multiplies the
 * REM FX path by its USD yield.
 */
object RemComparisonEngine {

    /** Hard cap imposed by the 2027 electoral calendar. */
    val ELECTION_HORIZON: YearMonth = YearMonth.of(2027, 4)

    private const val DAYS_PER_MONTH = 30.4375
    private const val DAYS_PER_YEAR = 365.0
    private const val VERDICT_BAND_PP = 0.5
    private val locale = Locale.forLanguageTag("es-AR")

    fun compare(
        snapshot: RemSnapshot,
        instruments: List<RemInstrument>,
        horizonMonth: YearMonth,
        crawlingPegMonthlyPercent: Double,
        today: LocalDate = DateUtils.today(),
    ): RemComparison {
        val lastRem = snapshot.lastMonth
        val cap = if (lastRem != null) minOf(ELECTION_HORIZON, lastRem) else ELECTION_HORIZON
        val requested = horizonMonth
        val capped = requested.isAfter(cap)
        val horizon = when {
            capped -> cap
            requested.isBefore(YearMonth.from(today)) -> YearMonth.from(today)
            else -> requested
        }
        val horizonDate = if (YearMonth.from(today) >= horizon) today else horizon.atEndOfMonth()
        val months = ChronoUnit.DAYS.between(today, horizonDate)
            .coerceAtLeast(0L)
            .toDouble() / DAYS_PER_MONTH
        val peg = crawlingPegMonthlyPercent.coerceAtLeast(0.0)
        val pegFactor = (1.0 + peg / 100.0).pow(months)
        val pid = pegFactor - 1.0

        val fxToday = snapshot.fxAt(today) ?: snapshot.firstMonth?.let { snapshot.fxAt(it.atDay(1)) } ?: 0.0
        val fxHorizon = snapshot.fxAt(horizonDate) ?: fxToday
        val fxFactor = if (fxToday > 0.0) fxHorizon / fxToday else 1.0
        val remDepreciation = (fxFactor - 1.0) * 100.0
        val remMonthlyDepreciation = if (months > 0.0 && fxFactor > 0.0) {
            (fxFactor.pow(1.0 / months) - 1.0) * 100.0
        } else {
            0.0
        }
        val inflationFactor = snapshot.inflationFactor(today, horizonDate) ?: 1.0
        val remInflation = (inflationFactor - 1.0) * 100.0

        val rows = instruments
            .filter { it.isActive }
            .mapNotNull { instrument ->
                compareInstrument(
                    instrument = instrument,
                    snapshot = snapshot,
                    today = today,
                    horizonDate = horizonDate,
                    peg = peg,
                    fxToday = fxToday,
                )
            }
            .sortedByDescending { it.vsCrawlingPegPp }

        val best = rows.maxByOrNull { it.vsCrawlingPegPp }
        val warnings = buildList {
            if (capped) {
                add(
                    "Horizonte recortado a ${monthLabel(cap)}: el comparador no proyecta más " +
                        "allá de abril de 2027 por el calendario electoral.",
                )
            }
            if (lastRem != null && lastRem.isBefore(ELECTION_HORIZON)) {
                add(
                    "El REM importado llega hasta ${monthLabel(lastRem)}. " +
                        "Los meses posteriores usan el último dato disponible.",
                )
            }
            if (fxToday <= 0.0) {
                add("El REM no tiene tipo de cambio oficial: no se puede medir el retorno en dólares.")
            }
            if (months > 0.0 && fxToday > 0.0) {
                when {
                    remMonthlyDepreciation > peg + 1.0 -> add(
                        "El REM espera una devaluación de ${percent(remMonthlyDepreciation, 2)} " +
                            "mensual, por encima del crawling peg de ${percent(peg, 2)}: " +
                            "los instrumentos en dólares tienen viento a favor.",
                    )
                    remMonthlyDepreciation < peg - 1.0 -> add(
                        "El REM espera una devaluación de ${percent(remMonthlyDepreciation, 2)} " +
                            "mensual, por debajo del crawling peg de ${percent(peg, 2)}: " +
                            "la tasa en pesos tiene ventaja.",
                    )
                }
            }
        }

        return RemComparison(
            snapshot = snapshot,
            today = today,
            horizon = horizon,
            horizonDate = horizonDate,
            requestedHorizon = requested,
            horizonCapped = capped,
            crawlingPegMonthlyPercent = peg,
            months = months,
            pegReturnPercent = pid * 100.0,
            remFxToday = fxToday,
            remFxAtHorizon = fxHorizon,
            remDepreciationPercent = remDepreciation,
            remMonthlyDepreciationPercent = remMonthlyDepreciation,
            remInflationPercent = remInflation,
            rows = rows,
            best = best,
            recommendation = recommendation(best, rows, horizon, peg),
            warnings = warnings,
        )
    }

    private fun compareInstrument(
        instrument: RemInstrument,
        snapshot: RemSnapshot,
        today: LocalDate,
        horizonDate: LocalDate,
        peg: Double,
        fxToday: Double,
    ): RemInstrumentComparison? {
        val maturity = instrument.maturityDate
        if (maturity != null && maturity.isBefore(today)) return null

        val effectiveDate = when {
            maturity == null -> horizonDate
            maturity.isAfter(horizonDate) -> horizonDate
            else -> maturity
        }
        val days = ChronoUnit.DAYS.between(today, effectiveDate).coerceAtLeast(0L)
        val months = days.toDouble() / DAYS_PER_MONTH
        val years = days / DAYS_PER_YEAR
        val kind = instrument.kindType
        val yieldPercent = instrument.annualYieldPercent.coerceAtLeast(0.0)

        val remFxAtEnd = if (fxToday > 0.0) {
            snapshot.fxAt(effectiveDate) ?: fxToday
        } else {
            0.0
        }
        val fxFactor = if (fxToday > 0.0) {
            remFxAtEnd / fxToday
        } else {
            1.0
        }
        val inflationFactor = snapshot.inflationFactor(today, effectiveDate) ?: 1.0

        val arsFactor = when (kind) {
            RemInstrumentKind.PESO_FIXED -> if (instrument.yieldIsEffective) {
                (1.0 + yieldPercent / 100.0).pow(years)
            } else {
                (1.0 + yieldPercent / 100.0 / DAYS_PER_YEAR).pow(days.toDouble())
            }
            RemInstrumentKind.PESO_TNA ->
                (1.0 + yieldPercent / 100.0 / DAYS_PER_YEAR).pow(days.toDouble())
            RemInstrumentKind.CER ->
                inflationFactor * (1.0 + yieldPercent / 100.0).pow(years)
            RemInstrumentKind.DOLLAR_LINKED ->
                fxFactor * (1.0 + yieldPercent / 100.0).pow(years)
            RemInstrumentKind.USD_BOND ->
                fxFactor * (1.0 + yieldPercent / 100.0).pow(years)
        }

        val pegFactor = (1.0 + peg / 100.0).pow(months)
        val arsReturn = (arsFactor - 1.0) * 100.0
        val monthlyReturn = if (months > 0.0) (arsFactor.pow(1.0 / months) - 1.0) * 100.0 else 0.0
        val usdReturn = if (fxFactor > 0.0) (arsFactor / fxFactor - 1.0) * 100.0 else 0.0
        val realReturn = if (inflationFactor > 0.0) (arsFactor / inflationFactor - 1.0) * 100.0 else 0.0
        val vsPeg = if (pegFactor > 0.0) (arsFactor / pegFactor - 1.0) * 100.0 else 0.0
        val breakEven = if (months > 0.0) (arsFactor.pow(1.0 / months) - 1.0) * 100.0 else 0.0
        val breakEvenFx = if (fxToday > 0.0) fxToday * arsFactor else 0.0
        val pegFxAtEnd = if (fxToday > 0.0) fxToday * pegFactor else 0.0
        val remDevalPercent = (fxFactor - 1.0) * 100.0
        val remMonthlyDevalPercent = if (months > 0.0 && fxFactor > 0.0) {
            (fxFactor.pow(1.0 / months) - 1.0) * 100.0
        } else {
            0.0
        }
        val pegDevalPercent = (pegFactor - 1.0) * 100.0
        val breakEvenVsRemFxDiff = if (fxToday > 0.0) breakEvenFx - remFxAtEnd else 0.0
        val breakEvenVsPegFxDiff = if (fxToday > 0.0) breakEvenFx - pegFxAtEnd else 0.0
        val verdict = when {
            vsPeg >= VERDICT_BAND_PP -> RemVerdict.CONVIENE
            vsPeg <= -VERDICT_BAND_PP -> RemVerdict.NO_CONVIENE
            else -> RemVerdict.EMPATA
        }

        return RemInstrumentComparison(
            instrument = instrument,
            kind = kind,
            effectiveDate = effectiveDate,
            days = days,
            months = months,
            expectedArsReturnPercent = arsReturn,
            expectedMonthlyReturnPercent = monthlyReturn,
            expectedUsdReturnPercent = usdReturn,
            realReturnPercent = realReturn,
            vsCrawlingPegPp = vsPeg,
            breakEvenMonthlyDevaluationPercent = breakEven,
            fxToday = fxToday,
            breakEvenFx = breakEvenFx,
            breakEvenDevaluationPercent = arsReturn,
            remFxAtEffectiveDate = remFxAtEnd,
            remDevaluationPercent = remDevalPercent,
            remMonthlyDevaluationPercent = remMonthlyDevalPercent,
            pegFxAtEffectiveDate = pegFxAtEnd,
            pegDevaluationPercent = pegDevalPercent,
            breakEvenVsRemFxDiff = breakEvenVsRemFxDiff,
            breakEvenVsPegFxDiff = breakEvenVsPegFxDiff,
            verdict = verdict,
            detail = detail(
                kind = kind,
                instrument = instrument,
                effectiveDate = effectiveDate,
                months = months,
                arsReturn = arsReturn,
                inflation = (inflationFactor - 1.0) * 100.0,
                fxReturn = remDevalPercent,
                breakEven = breakEven,
            ),
        )
    }

    private fun detail(
        kind: RemInstrumentKind,
        instrument: RemInstrument,
        effectiveDate: LocalDate,
        months: Double,
        arsReturn: Double,
        inflation: Double,
        fxReturn: Double,
        breakEven: Double,
    ): String {
        val horizon = "a ${monthsOneDecimal(months)} meses (${shortDate(effectiveDate)})"
        return when (kind) {
            RemInstrumentKind.PESO_FIXED, RemInstrumentKind.PESO_TNA ->
                "${instrument.yieldLabel} ${percent(instrument.annualYieldPercent, 2)} → " +
                    "${percent(arsReturn, 2)} nominal $horizon. " +
                    "Necesita una devaluación menor a ${percent(breakEven, 2)} mensual " +
                    "para ganarle al dólar."
            RemInstrumentKind.CER ->
                "TIR real ${percent(instrument.annualYieldPercent, 2)} + inflación REM " +
                    "${percent(inflation, 2)} → ${percent(arsReturn, 2)} nominal $horizon. " +
                    "Break-even: ${percent(breakEven, 2)} mensual."
            RemInstrumentKind.DOLLAR_LINKED ->
                "Spread ${percent(instrument.annualYieldPercent, 2)} + devaluación REM " +
                    "${percent(fxReturn, 2)} → ${percent(arsReturn, 2)} nominal $horizon. " +
                    "Break-even: ${percent(breakEven, 2)} mensual."
            RemInstrumentKind.USD_BOND ->
                "TIR USD ${percent(instrument.annualYieldPercent, 2)} + FX REM " +
                    "${percent(fxReturn, 2)} → ${percent(arsReturn, 2)} nominal $horizon."
        }
    }

    private fun recommendation(
        best: RemInstrumentComparison?,
        rows: List<RemInstrumentComparison>,
        horizon: YearMonth,
        peg: Double,
    ): String {
        if (rows.isEmpty()) {
            return "Cargá instrumentos para comparar contra el crawling peg de ${percent(peg, 2)} mensual."
        }
        if (best == null) return "No hay instrumentos activos para comparar."
        val month = monthLabel(horizon)
        return if (best.vsCrawlingPegPp >= 0.0) {
            "${best.instrument.symbol} lidera a $month: ${percent(best.expectedArsReturnPercent, 2)} " +
                "nominal (${percent(best.expectedMonthlyReturnPercent, 2)} mensual), " +
                "${signedPp(best.vsCrawlingPegPp)} sobre el crawling peg de ${percent(peg, 2)}."
        } else {
            "Ningún instrumento le gana al crawling peg de ${percent(peg, 2)} mensual. " +
                "El mejor es ${best.instrument.symbol} con ${signedPp(best.vsCrawlingPegPp)}."
        }
    }

    internal fun percent(value: Double, decimals: Int): String =
        String.format(locale, "%.${decimals}f%%", value)

    internal fun signedPp(value: Double): String {
        val sign = if (value >= 0.0) "+" else "−"
        return sign + String.format(locale, "%.2f pp", abs(value))
    }

    internal fun monthsOneDecimal(months: Double): String =
        String.format(locale, "%.1f", months)

    internal fun shortDate(date: LocalDate): String = DateUtils.dateLabel(date)

    internal fun monthLabel(month: YearMonth): String = DateUtils.monthLabel(month)
}
