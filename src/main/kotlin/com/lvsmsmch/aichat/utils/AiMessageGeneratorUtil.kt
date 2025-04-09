package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat.db.repositories.content.MessageDbo
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlin.random.Random

object AiMessageGeneratorUtil {

    private val openAiApiKey = loadConfig().getProperty("OPEN_AI_API_KEY")
    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(defaultJson)
        }
    }

    suspend fun generateAiMessage(
        characterName: String,
        characterPrompt: String,
        messagesHistory: List<MessageDbo>
    ): String {
        // Convert message history to OpenAI format
        val messages = messagesHistory.map { message ->
            mapOf(
                "role" to if (message.isSentByUser) "user" else "assistant",
                "content" to message.text
            )
        }

        // Add system message at the beginning if there's no system message yet
        val systemMessage = mapOf(
            "role" to "system",
            "content" to
                    "You are $characterName. Your prompt is $characterPrompt. " +
                    "We are having a chat conversation, now, reply to me with your next message" +
                    "in the language, that we use in chat.",
        )

        // Create request payload
        val requestBody = mapOf(
            "model" to "gpt-3.5-turbo",
            "messages" to messages + listOf(systemMessage),
            "temperature" to 0.7,
            "max_tokens" to 800,
            "top_p" to 1.0,
            "frequency_penalty" to 0.0,
            "presence_penalty" to 0.0
        )

        // Send request to OpenAI API
        val openAiResponse: HttpResponse = httpClient.post("https://api.openai.com/v1/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $openAiApiKey")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        // Parse the response
        val responseBody = openAiResponse.body<Map<String, Any>>()

        // Extract the generated message
        @Suppress("UNCHECKED_CAST")
        val choices = responseBody["choices"] as? List<Map<String, Any>>
            ?: throw Exception("Invalid response format")

        if (choices.isEmpty()) {
            throw Exception("No response generated")
        }

        @Suppress("UNCHECKED_CAST")
        val message = choices[0]["message"] as? Map<String, Any>
            ?: throw Exception("Invalid message format")

        val content = message["content"] as? String
            ?: throw Exception("Message content missing")

        // Add a simulated delay to make the typing indicator more realistic
        delay(Random.nextLong(1000, 3000))

        return content
    }
}