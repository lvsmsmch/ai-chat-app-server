package com.lvsmsmch.aichat.utils

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.temporal.ChronoUnit


/**
 * A custom timestamp class that wraps `Instant` for better serialization.
 *
 * 🔹 **Stored in MongoDB as:** Epoch milliseconds (`Long`).
 * 🔹 **Output (toString) format:** ISO-8601 (e.g., `"2025-03-12T14:30:00Z"`).
 */


@Serializable(with = UtcTimestampSerializer::class)
data class UtcTimestamp(val instant: Instant) {

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
    fun subtractYears(years: Long) = UtcTimestamp(instant.minus(years, ChronoUnit.YEARS))
    fun isBeforeNow() = instant.isBefore(Instant.now())
    fun isAfterNow() = instant.isAfter(Instant.now())
    fun isInPast() = isBeforeNow()
    fun isInFuture() = isAfterNow()

    companion object {
        fun now(): UtcTimestamp = UtcTimestamp(Instant.now())
    }
}
