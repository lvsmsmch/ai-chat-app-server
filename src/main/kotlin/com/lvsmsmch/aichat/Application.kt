package com.lvsmsmch.aichat  // Use your package name

import com.lvsmsmch.aichat.utils.configureDatabase
import com.lvsmsmch.aichat.utils.configureRepositories
import com.lvsmsmch.aichat.utils.configureRouting
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.websocket.*
import java.time.Duration

fun main() {
    embeddedServer(Netty, port = 8080) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    install(WebSockets) {
        pingPeriod = Duration.ofSeconds(15)
        timeout = Duration.ofSeconds(60)
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    val database = configureDatabase()
    val repositories = configureRepositories(database)

    configureRouting(database = database, repositories = repositories)
}