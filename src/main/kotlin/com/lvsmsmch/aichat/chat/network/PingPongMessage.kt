package com.lvsmsmch.aichat.chat.network
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class PingPongMessage {
    @Serializable
    @SerialName("ping")
    data class Ping(val timestamp: Long = System.currentTimeMillis()) : PingPongMessage()
}