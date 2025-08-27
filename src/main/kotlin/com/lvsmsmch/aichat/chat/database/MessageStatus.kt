package com.lvsmsmch.aichat.chat.database

import kotlinx.serialization.Serializable

@Serializable
enum class MessageStatus(val value: String) {
    STREAMING("streaming"),
    COMPLETED("completed"),
    FAILED("failed")
    ;
}