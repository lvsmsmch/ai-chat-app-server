package com.lvsmsmch.aichat  // Use your package name

import com.lvsmsmch.aichat.database_impl.mongo_db.characters.CharactersDbRepository
import com.lvsmsmch.aichat.database_impl.mongo_db.configureMongoClient
import com.lvsmsmch.aichat.database_impl.mongo_db.users.UsersDbRepository
import com.lvsmsmch.aichat.other.configureJson
import com.lvsmsmch.aichat.routing.configureRouting
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

    val mongoClient = configureMongoClient()

    val charactersDbRepository = CharactersDbRepository(mongoClient.getDatabase("characters"))
    val usersDbRepository = UsersDbRepository(mongoClient.getDatabase("users"))

    configureRouting(charactersDbRepository, usersDbRepository)
}