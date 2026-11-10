package com.smartinvest.core.money

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale
import kotlin.math.floor

/**
 * Money is always handled as integer cents (Long) inside the app to avoid
 * floating point drift. Formatting is the only place where decimals appear.
 */
object MoneyMath {

    /** Splits [totalCents] in [parts] as evenly as possible, preserving the total. */
    fun allocate(totalCents: Long, parts: Int): List<Long> {
        require(parts > 0) { "parts must be > 0" }
        require(totalCents >= 0) { "total must be >= 0" }
        val base = totalCents / parts
        val remainder = (totalCents % parts).toInt()
        return List(parts) { index -> base + if (index < remainder) 1L else 0L }
    }

    /** Proportional split (largest remainder method), preserving the total exactly. */
    fun allocateByWeights(totalCents: Long, weights: List<Int>): List<Long> {
        require(totalCents >= 0) { "total must be >= 0" }
        if (weights.isEmpty()) return emptyList()
        val clean = weights.map { it.coerceAtLeast(0) }
        val sum = clean.sum()
        if (sum <= 0) return List(weights.size) { if (it == 0) totalCents else 0L }

        val raw = clean.map { totalCents.toDouble() * it / sum }
        val result = raw.map { floor(it).toLong() }.toMutableList()
        var remainder = totalCents - result.sum()
        val order = raw.indices.sortedByDescending { raw[it] - result[it] }
        var cursor = 0
        while (remainder > 0) {
            result[order[cursor % order.size]] += 1
            cursor++
            remainder--
        }
        return result
    }

    fun percentOf(cents: Long, percent: Double): Long =
        BigDecimal.valueOf(cents)
            .multiply(BigDecimal.valueOf(percent))
            .divide(BigDecimal.valueOf(100.0), 0, RoundingMode.HALF_UP)
            .toLong()

    fun percent(part: Long, total: Long): Double =
        if (total <= 0L) 0.0 else part.toDouble() * 100.0 / total.toDouble()

    fun safeRatio(part: Long, total: Long): Double =
        if (total <= 0L) 0.0 else part.toDouble() / total.toDouble()
}

object MoneyFormatter {

    private fun decimalFormat(): java.text.NumberFormat =
        java.text.NumberFormat.getNumberInstance(Locale.forLanguageTag("es-AR")).apply {
            minimumFractionDigits = 2
            maximumFractionDigits = 2
            isGroupingUsed = true
        }

    fun ars(cents: Long): String = "$ " + decimalFormat().format(BigDecimal.valueOf(cents, 2))

    fun usd(cents: Long): String = "US$ " + decimalFormat().format(BigDecimal.valueOf(cents, 2))

    fun arsWhole(cents: Long): String =
        "$ " + java.text.NumberFormat.getIntegerInstance(Locale.forLanguageTag("es-AR"))
            .format(BigDecimal.valueOf(cents, 2).setScale(0, RoundingMode.HALF_UP))

    fun compactArs(cents: Long): String {
        val value = cents / 100.0
        val abs = kotlin.math.abs(value)
        return when {
            abs >= 1_000_000_000 -> "$ " + decimalFormat().format(value / 1_000_000_000) + " MM"
            abs >= 1_000_000 -> "$ " + decimalFormat().format(value / 1_000_000) + " M"
            abs >= 10_000 -> "$ " + java.text.NumberFormat.getIntegerInstance(
                Locale.forLanguageTag("es-AR"),
            ).format(value)
            else -> ars(cents)
        }
    }
}

/** Parses ARS input tolerating "1.234,56", "1234.56", "1234,56" and "1234". */
object MoneyParser {

    fun parseToCents(raw: String): Long? {
        var value = raw.trim()
            .replace("\u00A0", "")
            .replace(" ", "")
            .replace("$", "")
            .replace("ARS", "", ignoreCase = true)
            .replace("USD", "", ignoreCase = true)

        if (value.isEmpty() || value.startsWith("-")) return null

        val hasComma = value.contains(',')
        val hasDot = value.contains('.')
        value = when {
            hasComma && hasDot -> value.replace(".", "").replace(',', '.')
            hasComma -> value.replace(',', '.')
            hasDot -> {
                val chunks = value.split('.')
                when {
                    chunks.size > 2 -> value.replace(".", "")
                    chunks[1].length == 3 && chunks[0].isNotEmpty() -> value.replace(".", "")
                    else -> value
                }
            }
            else -> value
        }

        if (!value.matches(Regex("""\d+(\.\d{1,6})?"""))) return null
        return runCatching {
            BigDecimal(value)
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact()
        }.getOrNull()
    }
}
