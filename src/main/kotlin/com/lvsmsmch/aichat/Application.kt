package com.lvsmsmch.aichat  // Use your package name

import com.lvsmsmch.aichat.utils.configureDatabase
import com.lvsmsmch.aichat.utils.configureJson
import com.lvsmsmch.aichat.utils.configureRepositories
import com.lvsmsmch.aichat.utils.configureRouting
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
    val database = configureDatabase()
    val repositories = configureRepositories(database)
    configureRouting(database = database, repositories = repositories)
}