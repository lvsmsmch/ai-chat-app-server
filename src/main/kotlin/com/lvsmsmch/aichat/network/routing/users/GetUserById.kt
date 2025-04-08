package com.lvsmsmch.aichat.network.routing.users

import com.lvsmsmch.aichat.db.repositories._utils.Mapper
import com.lvsmsmch.aichat.db.repositories._utils.toUserDto
import com.lvsmsmch.aichat.db.repositories.content.UserRepository
import com.lvsmsmch.aichat.network.dto_objects.UserDto
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable

fun Routing.configureGetUserByIdRouting(
    userRepository: UserRepository,
    mapper: Mapper
) {

    @Serializable
    data class Response(
        val userDto: UserDto
    )

    get("/users/{id}") {
        try {
            val userId = call.parameters["id"] ?: ""

            val user = userRepository.getUserById(userId)
                ?: return@get call.respond(HttpStatusCode.NotFound, "User not found")

            val response = Response(user.toUserDto(mapper))

            call.respond(HttpStatusCode.OK, response)
        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}