package com.lvsmsmch.aichat  // Use your package name

import com.lvsmsmch.aichat.db.Database
import com.lvsmsmch.aichat.utils.configureJson
import com.lvsmsmch.aichat.network.configureRouting
import com.lvsmsmch.aichat.network.configureTestRouting
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    embeddedServer(Netty, port = 8080) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    configureJson()
    val database = Database()
    configureTestRouting(database)
    configureRouting(database)
}