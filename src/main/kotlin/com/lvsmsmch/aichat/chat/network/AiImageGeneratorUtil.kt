package com.lvsmsmch.aichat.chat.network

import com.lvsmsmch.aichat.character.database.CharacterDbo
import com.lvsmsmch.aichat.chat.database.MessageDbo
import com.lvsmsmch.aichat.utils.ImageServer
import com.lvsmsmch.aichat.utils.defaultJson
import com.lvsmsmch.aichat.utils.logger
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import java.io.File
import java.util.*

/**
 * Генерация изображений персонажа (Gemini image-модели / Nano Banana).
 * Модель настраивается через GEMINI_IMAGE_MODEL; готовая картинка сохраняется
 * через ImageServer рядом с иконками персонажей и раздаётся по /images/.
 */
object AiImageGeneratorUtil {

    private val geminiApiUrl
        get() = System.getenv("GEMINI_API_URL") ?: "https://generativelanguage.googleapis.com/v1beta/models"
    private val geminiApiKey
        get() = System.getenv("GEMINI_API_KEY") ?: throw Exception("Missing GEMINI_API_KEY key")
    private val imageModel
        get() = System.getenv("GEMINI_IMAGE_MODEL") ?: "gemini-3.1-flash-lite-image"

    private val httpClient = HttpClient {
        install(ContentNegotiation) { json(defaultJson) }
        install(HttpTimeout) {
            // Картинка генерится заметно дольше текста
            requestTimeoutMillis = 90_000
            socketTimeoutMillis = 90_000
        }
    }

    /**
     * Генерирует изображение по персонажу и последним сообщениям чата,
     * сохраняет на диск и возвращает публичный URL.
     * @throws CensoredException если запрос/результат заблокирован фильтром
     */
    suspend fun generateImage(
        characterDbo: CharacterDbo,
        messagesHistory: List<MessageDbo>,
    ): String {
        val recent = messagesHistory
            .filter { it.text.isNotBlank() }
            .takeLast(8)
            .joinToString("\n") { (if (it.isSentByUser) "User: " else "${characterDbo.name}: ") + it.text.take(200) }

        val prompt = buildString {
            append("Create a single vivid illustration of the character ")
            append(characterDbo.name)
            append(". Character description: ")
            append(characterDbo.prompt.ifBlank { characterDbo.description })
            if (recent.isNotBlank()) {
                append("\nDepict the character in the current scene of this conversation:\n")
                append(recent)
            }
            append("\nStyle: high quality digital art, expressive, no text or captions in the image.")
        }

        val requestBody = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", prompt) } }
                }
            }
            putJsonObject("generationConfig") {
                putJsonArray("responseModalities") {
                    add(JsonPrimitive("TEXT"))
                    add(JsonPrimitive("IMAGE"))
                }
            }
        }

        val response = httpClient.post("$geminiApiUrl/$imageModel:generateContent?key=$geminiApiKey") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }

        if (response.status != HttpStatusCode.OK) {
            val errorBody = response.bodyAsText()
            logger.error("Gemini image API error: ${response.status}, body: ${errorBody.take(500)}")
            throw Exception("Gemini image API error: ${response.status}")
        }

        val jsonResponse = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        jsonResponse["promptFeedback"]?.jsonObject
            ?.get("blockReason")?.jsonPrimitive?.contentOrNull?.let {
                throw CensoredException("image promptFeedback.blockReason=$it")
            }

        val firstCandidate = jsonResponse["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        val finishReason = firstCandidate?.get("finishReason")?.jsonPrimitive?.contentOrNull
        if (finishReason in setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "IMAGE_SAFETY")) {
            throw CensoredException("image finishReason=$finishReason")
        }

        val base64Data = firstCandidate
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray
            ?.mapNotNull { it.jsonObject["inlineData"]?.jsonObject }
            ?.firstOrNull()
            ?.get("data")?.jsonPrimitive?.contentOrNull
            ?: throw Exception("No image data in Gemini response (finishReason=$finishReason)")

        val bytes = Base64.getDecoder().decode(base64Data)
        val tempFile = File.createTempFile("gen_image_", ".png")
        return try {
            tempFile.writeBytes(bytes)
            ImageServer.uploadImageOnServer(tempFile).originalUrl
        } finally {
            tempFile.delete()
        }
    }
}
