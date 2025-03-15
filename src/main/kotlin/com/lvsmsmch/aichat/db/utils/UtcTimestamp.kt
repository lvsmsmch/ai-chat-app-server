package com.lvsmsmch.aichat.db.utils

import kotlinx.serialization.Serializable
import java.time.Instant


/**
 * A custom timestamp class that wraps `Instant` for better serialization.
 *
 * 🔹 **Stored in MongoDB as:** Epoch milliseconds (`Long`).
 * 🔹 **Output (toString) format:** ISO-8601 (e.g., `"2025-03-12T14:30:00Z"`).
 */

@Serializable(with = UtcTimestampSerializer::class)
data class UtcTimestamp(val instant: Instant) {
    override fun toString(): String = instant.toString()
    companion object {
        fun now(): UtcTimestamp = UtcTimestamp(Instant.now())
    }
}