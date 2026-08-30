package com.enviroguard.app.utils

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

data class TimeRange(val startInclusive: Long, val endExclusive: Long)

object DateRangeUtils {
    fun lastHour(now: Long = System.currentTimeMillis()): TimeRange =
        TimeRange((now - 60 * 60 * 1_000L).coerceAtLeast(0L), now + 1L)

    fun today(zoneId: ZoneId = ZoneId.systemDefault()): TimeRange =
        calendarDays(1, LocalDate.now(zoneId), zoneId)

    fun last7Days(zoneId: ZoneId = ZoneId.systemDefault()): TimeRange =
        calendarDays(7, LocalDate.now(zoneId), zoneId)

    fun last30Days(zoneId: ZoneId = ZoneId.systemDefault()): TimeRange =
        calendarDays(30, LocalDate.now(zoneId), zoneId)

    fun calendarDays(
        dayCount: Long,
        today: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): TimeRange {
        require(dayCount > 0)
        return dates(today.minusDays(dayCount - 1), today, zoneId)
    }

    fun dates(
        startDate: LocalDate,
        endDateInclusive: LocalDate,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): TimeRange {
        require(!endDateInclusive.isBefore(startDate))
        return TimeRange(
            startDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            endDateInclusive.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
        )
    }

    // MaterialDatePicker returns UTC-midnight values representing calendar dates.
    fun materialPickerDate(value: Long): LocalDate =
        Instant.ofEpochMilli(value).atZone(ZoneOffset.UTC).toLocalDate()
}
