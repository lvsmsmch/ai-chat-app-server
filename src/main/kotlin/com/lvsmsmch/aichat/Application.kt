package com.lvsmsmch.aichat  // Use your package name

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

fun main() {
    embeddedServer(Netty, port = 8080) {
        module()
    }.start(wait = true)
}

fun Application.module() {
    install(ContentNegotiation) {
        json()
    }

    routing {
        // Basic GET request
        get("/") {
            call.respondText("Hello, Vlad! Katochku?")
        }

        // Simple GET with parameter
        get("/hello/{name}") {
            val name = call.parameters["name"] ?: "stranger"
            call.respondText("Hello, $name!")
        }

        // Simple JSON response
        get("/json") {
            call.respond(mapOf("message" to "This is a JSON response"))
        }

        // POST request to receive JSON and return modified data
        post("/post") {
            val request = call.receive<PostRequest>()
            call.respond(PostResponse(message = "Hello, ${request.name}, age ${request.age}!"))
        }
    }
}

// Define a data class for handling POST requests
@Serializable
data class PostRequest(val name: String, val age: Int)

// Define a data class for responding with JSON
@Serializable
data class PostResponse(val message: String)
