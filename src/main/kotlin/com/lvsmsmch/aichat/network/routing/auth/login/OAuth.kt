package com.lvsmsmch.aichat.network.routing.auth.login

import com.lvsmsmch.aichat.db.repositories.auth.tokens.session_tokens.SessionRepository
import com.lvsmsmch.aichat.db.repositories.content.UsersRepository
import com.lvsmsmch.aichat.utils.*
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.logging.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class OAuthUserData(
    val id: String,
    val email: String?,
    val name: String?,
    val profilePictureUrl: String?
)

enum class OAuthProvider(val id: String) {
    GOOGLE("google") {
        override fun buildApiUrl(token: String): String =
            "https://oauth2.googleapis.com/tokeninfo?id_token=$token"

        override fun parseUserData(json: JsonObject): OAuthUserData = OAuthUserData(
            id = json["sub"]?.jsonPrimitive?.content
                ?: throw Exception("Google response missing 'sub' field"),
            email = json["email"]?.jsonPrimitive?.content,
            name = json["name"]?.jsonPrimitive?.content,
            profilePictureUrl = json["picture"]?.jsonPrimitive?.content
        )
    },

    FACEBOOK("facebook") {
        override fun buildApiUrl(token: String): String =
            "https://graph.facebook.com/me?fields=id,name,email,picture&access_token=$token"

        override fun parseUserData(json: JsonObject): OAuthUserData = OAuthUserData(
            id = json["id"]?.jsonPrimitive?.content
                ?: throw Exception("Facebook response missing 'id' field"),
            email = json["email"]?.jsonPrimitive?.content,
            name = json["name"]?.jsonPrimitive?.content,
            profilePictureUrl = json["picture"]?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("url")?.jsonPrimitive?.content
        )
    };

    abstract fun buildApiUrl(token: String): String
    abstract fun parseUserData(json: JsonObject): OAuthUserData
}

fun Routing.configureOauthRouting(
    usersRepository: UsersRepository,
    sessionRepository: SessionRepository,
) {
    @Serializable
    data class Request(
        val oauthAccessToken: String
    )

    @Serializable
    data class Response(
        val authToken: String
    )

    post("/auth/oauth/{provider}") {
        try {
            // Get the provider from path parameter
            val providerName = call.parameters["provider"]?.lowercase()
            val provider = OAuthProvider.values().first { it.id == providerName }

            // Parse request
            val request = call.receive<Request>()

            // Fetch OAuth user data
            val oauthUserData = HttpClient().use { client ->
                val apiUrl = provider.buildApiUrl(request.oauthAccessToken)
                val response = client.get(apiUrl)

                if (response.status != HttpStatusCode.OK) {
                    throw Exception("${provider.id} OAuth API returned ${response.status}: ${response.bodyAsText()}")
                }

                val responseBody = response.bodyAsText()
                val json = Json.parseToJsonElement(responseBody).jsonObject
                provider.parseUserData(json)
            }

            // Get or create user
            val user = usersRepository.getOrCreateUserByOAuthId(provider, oauthUserData)

            // Create session
            val sessionDbo = sessionRepository.createSession(user.id, call.getUserIp())

            // Return auth token
            call.respond(Response(authToken = sessionDbo.token))
        } catch (e: Exception) {
            application.log.error(e)
            call.respond(HttpStatusCode.InternalServerError, "An error occurred processing your request")
        }
    }
}