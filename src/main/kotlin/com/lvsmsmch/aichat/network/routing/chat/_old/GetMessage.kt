package com.lvsmsmch.aichat.network.routing.chat._old

import com.lvsmsmch.aichat.utils.loadConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun Routing.configureGetMessageRouting() {

    val openAiApiKey = loadConfig().getProperty("OPEN_AI_API_KEY")

    val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    @Serializable
    data class Message(
        val role: String, // "system", "user", or "assistant"
        val content: String
    )

    @Serializable
    data class Request(
        val characterName: String,
        val characterPrompt: String,
        val history: List<Message>
    )

    @Serializable
    data class OpenAiRequest(
        val model: String = "gpt-4",
        val messages: List<Message>,
        val temperature: Double = 0.7
    )

    @Serializable
    data class Choice(
        val message: Message
    )

    @Serializable
    data class OpenAiResponse(
        val choices: List<Choice>
    )

    @Serializable
    data class Response(
        val message: String
    )

    post("/chat/next-message") {
        val request = call.receive<Request>()

        val messages = mutableListOf<Message>()

        // Add the character's prompt as the system message
        messages.add(Message(role = "system", content = request.characterPrompt))

        // Add the chat history
        messages.addAll(request.history)

        try {
            val openAiResponse: HttpResponse = httpClient.post("https://api.openai.com/v1/chat/completions") {
                header(HttpHeaders.Authorization, "Bearer $openAiApiKey")
                contentType(ContentType.Application.Json)
                setBody(OpenAiRequest(messages = messages))
            }

            val responseBody: OpenAiResponse = openAiResponse.body()

            val botMessage = responseBody.choices.firstOrNull()?.message?.content ?: "No response from AI"

            call.respond(HttpStatusCode.OK, Response(message = botMessage))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.InternalServerError, Response(message = "Error: ${e.localizedMessage}"))
        }
    }
}
