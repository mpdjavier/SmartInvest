package com.smartinvest.ui.util

import com.smartinvest.core.money.MoneyFormatter
import com.smartinvest.core.time.DateUtils
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale
import kotlin.math.abs

object Fmt {

    fun ars(cents: Long): String =
        if (MoneyVisibility.hidden) "$ ${MoneyVisibility.MASK}" else MoneyFormatter.ars(cents)

    fun usd(cents: Long): String =
        if (MoneyVisibility.hidden) "US$ ${MoneyVisibility.MASK}" else MoneyFormatter.usd(cents)

    fun compact(cents: Long): String =
        if (MoneyVisibility.hidden) "$ ${MoneyVisibility.MASK}" else MoneyFormatter.compactArs(cents)

    fun signedArs(cents: Long): String =
        if (MoneyVisibility.hidden) {
            (if (cents >= 0) "+ " else "− ") + "$ ${MoneyVisibility.MASK}"
        } else {
            (if (cents >= 0) "+ " else "− ") + MoneyFormatter.ars(abs(cents))
        }

    fun signedUsd(cents: Long): String =
        if (MoneyVisibility.hidden) {
            (if (cents >= 0) "+ " else "− ") + "US$ ${MoneyVisibility.MASK}"
        } else {
            (if (cents >= 0) "+ " else "− ") + MoneyFormatter.usd(abs(cents))
        }

    fun percent(value: Double?, decimals: Int = 1): String =
        if (value == null) "—" else String.format(Locale.forLanguageTag("es-AR"), "%.${decimals}f%%", value)

    fun signedPercent(value: Double?, decimals: Int = 1): String {
        if (value == null) return "—"
        val sign = if (value > 0) "+" else ""
        return sign + String.format(Locale.forLanguageTag("es-AR"), "%.${decimals}f%%", value)
    }

    fun rate(tna: Double): String =
        String.format(Locale.forLanguageTag("es-AR"), "%.2f%%", tna)

    fun pp(value: Double): String =
        String.format(Locale.forLanguageTag("es-AR"), "%.1f pp", value)

    fun month(month: YearMonth): String = DateUtils.monthLabel(month)

    fun shortDate(date: LocalDate): String = DateUtils.dateLabel(date)

    fun longDate(date: LocalDate): String = DateUtils.longDateLabel(date)

    fun coverage(months: Double): String =
        String.format(Locale.forLanguageTag("es-AR"), "%.1f meses", months)
}
