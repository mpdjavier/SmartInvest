package com.smartinvest.domain.engine

import com.smartinvest.domain.model.RemInstrument
import com.smartinvest.domain.model.RemInstrumentKind
import com.smartinvest.domain.model.RemMonthPoint
import com.smartinvest.domain.model.RemSnapshot
import com.smartinvest.domain.model.RemVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class RemComparisonEngineTest {

    private val today = LocalDate.of(2026, 11, 1)

    private fun snapshot(
        fx: List<Pair<String, Double>> = listOf("2026-11" to 1_000.0, "2026-12" to 1_010.0),
        inflation: Map<String, Double> = mapOf("2026-11" to 1.0, "2026-12" to 1.0),
    ): RemSnapshot = RemSnapshot(
        publishedAt = "2026-11-05",
        reference = "REM test",
        points = fx.map { (month, value) ->
            RemMonthPoint(
                month = month,
                inflationMonthlyPercent = inflation[month] ?: 0.0,
                officialFx = value,
            )
        },
    )

    private fun instrument(
        id: String,
        symbol: String,
        kind: RemInstrumentKind,
        maturity: String,
        yield: Double,
        effective: Boolean = true,
    ) = RemInstrument(
        id = id,
        symbol = symbol,
        name = symbol,
        kind = kind.name,
        maturity = maturity,
        annualYieldPercent = yield,
        yieldIsEffective = effective,
    )

    @Test
    fun `fixed rate letter beats the one percent crawling peg`() {
        val comparison = RemComparisonEngine.compare(
            snapshot = snapshot(),
            instruments = listOf(
                instrument("t", "T30A7", RemInstrumentKind.PESO_FIXED, "2026-12-31", 30.0),
            ),
            horizonMonth = YearMonth.of(2026, 12),
            crawlingPegMonthlyPercent = 1.0,
            today = today,
        )

        val row = comparison.rows.single()
        assertEquals(RemVerdict.CONVIENE, row.verdict)
        assertTrue(row.expectedArsReturnPercent in 4.0..4.8)
        assertTrue(row.vsCrawlingPegPp > 1.5)
        assertEquals(1.97, comparison.months, 0.05)
    }

    @Test
    fun `dollar linked wins when the REM FX rises faster than the peg`() {
        val comparison = RemComparisonEngine.compare(
            snapshot = snapshot(fx = listOf("2026-11" to 1_000.0, "2026-12" to 1_100.0)),
            instruments = listOf(
                instrument("fixed", "T30A7", RemInstrumentKind.PESO_FIXED, "2026-12-31", 30.0),
                instrument("dl", "D15E7", RemInstrumentKind.DOLLAR_LINKED, "2026-12-31", 5.0),
            ),
            horizonMonth = YearMonth.of(2026, 12),
            crawlingPegMonthlyPercent = 1.0,
            today = today,
        )

        assertEquals("D15E7", comparison.rows.first().instrument.symbol)
        assertTrue(comparison.rows.first().expectedArsReturnPercent > 10.0)
        assertTrue(comparison.rows.first().vsCrawlingPegPp > 6.0)
        assertTrue(comparison.warnings.any { it.contains("por encima del crawling peg") })
    }

    @Test
    fun `caps the horizon at april 2027`() {
        val months = listOf(
            "2026-11", "2026-12", "2027-01", "2027-02",
            "2027-03", "2027-04", "2027-05", "2027-06",
        )
        val comparison = RemComparisonEngine.compare(
            snapshot = snapshot(fx = months.map { it to 1_000.0 }),
            instruments = emptyList(),
            horizonMonth = YearMonth.of(2027, 12),
            crawlingPegMonthlyPercent = 1.0,
            today = today,
        )

        assertTrue(comparison.horizonCapped)
        assertEquals(YearMonth.of(2027, 4), comparison.horizon)
        assertEquals(LocalDate.of(2027, 4, 30), comparison.horizonDate)
    }

    @Test
    fun `missing REM fx does not crash and warns`() {
        val comparison = RemComparisonEngine.compare(
            snapshot = snapshot(fx = emptyList(), inflation = mapOf("2026-11" to 1.0)),
            instruments = listOf(
                instrument("fixed", "T30A7", RemInstrumentKind.PESO_FIXED, "2026-12-31", 30.0),
            ),
            horizonMonth = YearMonth.of(2026, 12),
            crawlingPegMonthlyPercent = 1.0,
            today = today,
        )

        assertFalse(comparison.hasFx)
        assertEquals(1, comparison.rows.size)
        assertTrue(comparison.warnings.any { it.contains("tipo de cambio") })
    }

    @Test
    fun `cer bond adds rem inflation to its real yield`() {
        val comparison = RemComparisonEngine.compare(
            snapshot = snapshot(
                fx = listOf("2026-11" to 1_000.0, "2026-12" to 1_020.0),
                inflation = mapOf("2026-11" to 2.0, "2026-12" to 2.0),
            ),
            instruments = listOf(
                instrument("cer", "TZXA7", RemInstrumentKind.CER, "2026-12-31", 2.0),
            ),
            horizonMonth = YearMonth.of(2026, 12),
            crawlingPegMonthlyPercent = 1.0,
            today = today,
        )

        val row = comparison.rows.single()
        assertTrue(row.expectedArsReturnPercent > 4.0)
        assertTrue(row.realReturnPercent > 0.2)
        assertTrue(row.realReturnPercent < 1.5)
    }
}
