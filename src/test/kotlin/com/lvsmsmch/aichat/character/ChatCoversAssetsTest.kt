package com.lvsmsmch.aichat.character

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ChatCoversAssetsTest {

    @Test
    fun `every declared chat cover is packaged as webp`() {
        assertEquals(15, ChatCovers.builtIn.distinct().size)

        ChatCovers.builtIn.forEach { code ->
            val stream = assertNotNull(
                ChatCovers::class.java.getResourceAsStream("/chat-covers/$code.webp"),
                "Missing server asset for chat cover '$code'",
            )
            stream.use {
                val header = it.readNBytes(12)
                assertEquals("RIFF", header.copyOfRange(0, 4).toString(Charsets.US_ASCII))
                assertEquals("WEBP", header.copyOfRange(8, 12).toString(Charsets.US_ASCII))
            }
        }
    }
}
