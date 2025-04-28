package com.lvsmsmch.aichat.network.routing.chat

import io.ktor.websocket.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val SESSION_TIMEOUT_MS = 300_000L // 5 minutes
private const val PING_INTERVAL_MS = 30_000L // 30 seconds

@Serializable
sealed class PingPongMessage {
    @Serializable
    @SerialName("ping")
    data class Ping(val timestamp: Long = System.currentTimeMillis()) : PingPongMessage()
}

class PingPongManager(private val webSocketSession: WebSocketSession) {

    private var lastActivity = System.currentTimeMillis()
    private val json = Json { ignoreUnknownKeys = true }

    // Ping job that sends pings to the client
    val pingJob = CoroutineScope(Dispatchers.IO).launch {
        while (isActive) {
            try {
                // Check for session timeout
                if (System.currentTimeMillis() - lastActivity > SESSION_TIMEOUT_MS) {
                    webSocketSession.close(CloseReason(CloseReason.Codes.NORMAL, "Session timeout"))
                    break
                }

                // Send ping
                val ping = PingPongMessage.Ping()
                webSocketSession.send(Frame.Text(json.encodeToString(ping)))
                delay(PING_INTERVAL_MS)
            } catch (e: Exception) {
                // If an error occurs, break the loop
                break
            }
        }
    }

    // Function to stop all jobs
    fun stop() {
        pingJob.cancel()
    }

    // Update activity timestamp (call this when receiving any message)
    fun updateActivity() {
        lastActivity = System.currentTimeMillis()
    }
}