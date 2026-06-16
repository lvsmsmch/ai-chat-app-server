package com.lvsmsmch.aichat.utils

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Serializable(with = UtcTimestampSerializer::class)
class UtcTimestamp(val instant: Instant) : Comparable<UtcTimestamp> {

    val epochMillis get() = instant.toEpochMilli()

    fun addSeconds(seconds: Long) = UtcTimestamp(instant.plus(seconds, ChronoUnit.SECONDS))
    fun addMinutes(minutes: Long) = UtcTimestamp(instant.plus(minutes, ChronoUnit.MINUTES))
    fun addHours(hours: Long) = UtcTimestamp(instant.plus(hours, ChronoUnit.HOURS))
    fun addDays(days: Long) = UtcTimestamp(instant.plus(days, ChronoUnit.DAYS))
    fun addMonths(months: Long) = UtcTimestamp(instant.plus(months, ChronoUnit.MONTHS))
    fun addYears(years: Long) = UtcTimestamp(instant.plus(years, ChronoUnit.YEARS))
    fun subtractSeconds(seconds: Long) = UtcTimestamp(instant.minus(seconds, ChronoUnit.SECONDS))
    fun subtractMinutes(minutes: Long) = UtcTimestamp(instant.minus(minutes, ChronoUnit.MINUTES))
    fun subtractHours(hours: Long) = UtcTimestamp(instant.minus(hours, ChronoUnit.HOURS))
    fun subtractDays(days: Long) = UtcTimestamp(instant.minus(days, ChronoUnit.DAYS))
    fun subtractMonths(months: Long) = UtcTimestamp(instant.minus(months, ChronoUnit.MONTHS))
    fun isInPast() = instant.isBefore(Instant.now())
    fun isInFuture() = instant.isAfter(Instant.now())
    fun isBefore(utcTimestamp: UtcTimestamp) = instant.isBefore(utcTimestamp.instant)
    fun isAfter(utcTimestamp: UtcTimestamp) = instant.isAfter(utcTimestamp.instant)

    override fun toString(): String {
        return DateTimeFormatter.ISO_INSTANT.format(instant)
    }

    override fun compareTo(other: UtcTimestamp): Int {
        return instant.compareTo(other.instant)
    }

    fun format(pattern: String, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val formatter = DateTimeFormatter.ofPattern(pattern).withZone(zoneId)
        return formatter.format(instant)
    }

    companion object {
        fun now(): UtcTimestamp = UtcTimestamp(Instant.now())

        fun year1900(): UtcTimestamp = UtcTimestamp(Instant.parse("1900-01-01T00:00:00Z"))

        fun parse(isoString: String): UtcTimestamp {
            return UtcTimestamp(Instant.parse(isoString))
        }

        fun fromEpochMillis(epochMillis: Long): UtcTimestamp {
            return UtcTimestamp(Instant.ofEpochMilli(epochMillis))
        }
    }
}