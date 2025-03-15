package com.lvsmsmch.aichat.network

import com.lvsmsmch.aichat.db.Database
import com.lvsmsmch.aichat.network.requests.users.UpdateUserDataRequest
import com.lvsmsmch.aichat.network.utils.parseRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun Application.configureTestRouting(database: Database) {
    routing {
        // Simple GET with parameter
        get("/hello/{name}") {
            val name = call.parameters["name"] ?: "stranger"
            call.respondText("Hello, $name!")
        }

        // Simple JSON response
        get("/json") {
            call.respond(mapOf("message" to "This is a JSON response"))
        }

        post("/profile/update") {
            val request: UpdateUserDataRequest? = call.parseRequest()
            if (request != null) {
                call.respond(HttpStatusCode.OK, "Profile updated successfully!")
            } else {
                call.respond(HttpStatusCode.BadRequest, "Invalid request")
            }
        }
    }
}