package com.smartinvest.core.time

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZonedDateTime
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

    fun isWeekend(date: LocalDate): Boolean =
        date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    /**
     * BYMA / Rava trading opens at 11:00 (America/Argentina/Buenos_Aires) Monday–Friday.
     * Before 11:05 AM on a weekday, or at any time on a weekend, today's market session
     * has not operated yet, so any quote must belong to the previous business day.
     */
    fun hasMarketOperatedToday(at: ZonedDateTime = ZonedDateTime.now(ARGENTINA)): Boolean =
        !isWeekend(at.toLocalDate()) && !at.toLocalTime().isBefore(LocalTime.of(11, 5))

    /**
     * Latest date on which the Argentine market could have operated at [at].
     */
    fun latestMarketSessionDate(at: ZonedDateTime = ZonedDateTime.now(ARGENTINA)): LocalDate {
        var date = at.toLocalDate()
        if (!hasMarketOperatedToday(at)) {
            date = date.minusDays(1)
        }
        while (isWeekend(date)) {
            date = date.minusDays(1)
        }
        return date
    }
}
