package com.lvsmsmch.aichat.chat.database

import kotlinx.serialization.Serializable

@Serializable
enum class ChatType(val code: String) {
    DIRECT("direct"),
    GROUP("group")
}