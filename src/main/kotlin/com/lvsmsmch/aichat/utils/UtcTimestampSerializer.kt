package com.lvsmsmch.aichat.utils

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

object UtcTimestampSerializer : KSerializer<UtcTimestamp> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("UtcTimestamp", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: UtcTimestamp) {
        encoder.encodeLong(value.instant.toEpochMilli()) // Save as epoch millis
    }

    override fun deserialize(decoder: Decoder): UtcTimestamp {
        return UtcTimestamp(Instant.ofEpochMilli(decoder.decodeLong()))
    }
}
