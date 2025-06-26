// UtcTimestampSerializer.kt
package com.lvsmsmch.aichat.utils

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant
import java.time.format.DateTimeFormatter

object UtcTimestampSerializer : KSerializer<UtcTimestamp> {
    // ISO-8601 formatter for consistent string representation
    private val formatter = DateTimeFormatter.ISO_INSTANT

    // Change the descriptor to handle String instead of Long
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        "UtcTimestamp", PrimitiveKind.STRING
    )

    override fun serialize(encoder: Encoder, value: UtcTimestamp) {
        // Convert to ISO-8601 string format
        val isoString = formatter.format(value.instant)
        encoder.encodeString(isoString)
    }

    override fun deserialize(decoder: Decoder): UtcTimestamp {
        // Parse from ISO-8601 string format
        val isoString = decoder.decodeString()
        return UtcTimestamp(Instant.parse(isoString))
    }
}