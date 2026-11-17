package com.smartinvest.core.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Quantities (cuotapartes, nominales, CEDEARs) are decimals, not cents.
 *
 * They can be large (millions of cuotapartes) and keep up to six decimals, so they must
 * never be rendered in scientific notation and must accept the Argentine format the
 * keyboard produces ("13.551.401,075"). Without this, editing an FCI holding would lose
 * its quantity and the automatic valuation would silently fall back to the manual value.
 */
object QuantityParser {

    private val VALID = Regex("""\d+(\.\d{1,6})?""")

    /** Parses "13.551.401,075", "13551401,075", "13551401.075" and "13551401". */
    fun parse(raw: String): Double? {
        var value = raw.trim()
            .replace("\u00A0", "")
            .replace(" ", "")

        if (value.isEmpty() || value.startsWith("-")) return null

        val hasComma = value.contains(',')
        val hasDot = value.contains('.')
        value = when {
            hasComma && hasDot -> value.replace(".", "").replace(',', '.')
            hasComma -> value.replace(',', '.')
            // "3.154.364" only makes sense as thousands; a single dot stays decimal so
            // "13551401.075" never becomes 13.551.401.075 by mistake.
            hasDot -> if (value.count { it == '.' } > 1) value.replace(".", "") else value
            else -> value
        }

        if (!VALID.matches(value)) return null
        return value.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
    }

    /** Renders without scientific notation, with a comma decimal and no trailing zeros. */
    fun format(value: Double): String {
        if (!value.isFinite() || value <= 0.0) return ""
        return BigDecimal.valueOf(value)
            .setScale(6, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
            .replace('.', ',')
    }
}
