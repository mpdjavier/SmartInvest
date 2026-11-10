package com.smartinvest.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

object DateUtils {

    val ARGENTINA: ZoneId = ZoneId.of("America/Argentina/Buenos_Aires")

    private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("es-AR"))
    private val shortDateFormatter = DateTimeFormatter.ofPattern("dd MMM", Locale.forLanguageTag("es-AR"))
    private val longDateFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.forLanguageTag("es-AR"))

    fun today(): LocalDate = LocalDate.now(ARGENTINA)

    fun nowMillis(): Long = Instant.now().toEpochMilli()

    fun monthKey(month: YearMonth): String = month.toString()

    fun monthKey(date: LocalDate): String = YearMonth.from(date).toString()

    fun parseMonth(key: String): YearMonth? = runCatching { YearMonth.parse(key) }.getOrNull()

    fun monthLabel(month: YearMonth): String =
        monthFormatter.format(month).replaceFirstChar { it.titlecase(Locale.forLanguageTag("es-AR")) }

    fun dateLabel(date: LocalDate): String = shortDateFormatter.format(date)

    fun longDateLabel(date: LocalDate): String = longDateFormatter.format(date)

    fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)

    fun epochDayToDate(day: Long): LocalDate = LocalDate.ofEpochDay(day)

    fun dateToEpochDay(date: LocalDate): Long = date.toEpochDay()

    fun yearStart(date: LocalDate): LocalDate = LocalDate.of(date.year, 1, 1)

    fun previousMonth(month: YearMonth): YearMonth = month.minusMonths(1)

    fun nextMonth(month: YearMonth): YearMonth = month.plusMonths(1)
}
