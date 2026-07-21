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
    // Топ-модель для картинок; после месячного порога юзер едет на mid
    private val imageModelTop
        get() = System.getenv("GEMINI_IMAGE_MODEL") ?: "gemini-3.1-flash-image"
    private val imageModelMid
        get() = System.getenv("GEMINI_IMAGE_MODEL_MID") ?: "gemini-3.1-flash-lite-image"

    // Провайдер генерации: "gemini" (дефолт) или "xai" (Grok Imagine, ~$0.02/картинка)
    private val provider
        get() = System.getenv("IMAGE_PROVIDER") ?: "gemini"
    private val xaiApiKey
        get() = System.getenv("XAI_API_KEY") ?: throw Exception("Missing XAI_API_KEY")
    private val xaiImageModel
        get() = System.getenv("XAI_IMAGE_MODEL") ?: "grok-imagine-image"

    /** xAI — одна модель без тиров: месячный топ-даунгрейд Gemini к нему не применяется. */
    val providerIsXai: Boolean get() = provider == "xai"

    data class ImageGenResult(val url: String, val debugInfo: String)

    /** Прайс для ориентировочной цены в дебаг-инфо: $/1M input-токенов и ~$/картинка. */
    private data class ImagePricing(val inputPerMTok: Double, val perImageOut: Double)
    private val pricing = mapOf(
        "gemini-3.1-flash-image" to ImagePricing(0.50, 0.067),
        "gemini-3.1-flash-lite-image" to ImagePricing(0.25, 0.0336),
        "gemini-2.5-flash-image" to ImagePricing(0.30, 0.039),
    )

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
        useTopModel: Boolean = true,
    ): ImageGenResult {
        val imageModel = if (useTopModel) imageModelTop else imageModelMid

        // История для промпта: ПЕРВОЕ сообщение чата (в приветствиях часто задана
        // сцена) + «[...]» + последние 8 сообщений
        val meaningful = messagesHistory.filter { it.text.isNotBlank() }
        val last8 = meaningful.takeLast(8)
        val opening = meaningful.firstOrNull()?.takeIf { it !in last8 }
        fun line(m: MessageDbo) =
            (if (m.isSentByUser) "User: " else "${characterDbo.name}: ") + m.text.take(200)
        val recent = buildString {
            opening?.let { append(line(it)); append("\n[...]\n") }
            append(last8.joinToString("\n") { line(it) })
        }

        // Референс внешности/стиля: последняя сгенерированная картинка этого чата
        // (держим дизайн и стиль рисовки), а для первой генерации — аватарка персонажа
        val lastGenFile = ImageServer.localFileForUrl(
            messagesHistory.lastOrNull { it.isImage && it.imageUrl != null }?.imageUrl
        )
        val avatarFile = if (lastGenFile == null) ImageServer.localFileForUrl(characterDbo.picUrl) else null
        val refFile = lastGenFile ?: avatarFile

        val prompt = buildString {
            append("Create a single vivid illustration of the character ")
            append(characterDbo.name)
            append(". Character description: ")
            append(characterDbo.prompt.ifBlank { characterDbo.description })
            if (recent.isNotBlank()) {
                append("\nDepict the character in the current scene of this conversation:\n")
                append(recent)
            }
            when {
                lastGenFile != null -> append(
                    "\nThe attached image is the previous scene of this chat: keep the SAME " +
                        "character design and art style, continue the visual continuity."
                )
                avatarFile != null -> append(
                    "\nThe attached image shows this character's appearance: use it as the " +
                        "visual reference for how the character looks."
                )
            }
            append("\nStyle: high quality digital art, expressive, no text or captions in the image.")
        }

        if (providerIsXai) return generateViaXai(prompt, refFile)

        val requestBody = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject { put("text", prompt) }
                        if (refFile != null) {
                            addJsonObject {
                                putJsonObject("inlineData") {
                                    put("mimeType", "image/jpeg")
                                    put("data", Base64.getEncoder().encodeToString(refFile.readBytes()))
                                }
                            }
                        }
                    }
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

        val parts = firstCandidate
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray
        val base64Data = parts
            ?.mapNotNull { it.jsonObject["inlineData"]?.jsonObject }
            ?.firstOrNull()
            ?.get("data")?.jsonPrimitive?.contentOrNull

        if (base64Data == null) {
            // «Мягкий отказ»: STOP без картинки, вместо неё текст «не могу нарисовать».
            // Это тоже цензура (фильтр вероятностный: то мягкий отказ, то IMAGE_SAFETY) —
            // показываем её честно и тратим слот, а не даём бесплатный ретрай.
            val refusalText = parts
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString(" ")?.trim().orEmpty()
            if (finishReason == "STOP") {
                throw CensoredException("model refused to draw: ${refusalText.take(200)}")
            }
            throw Exception("No image data in Gemini response (finishReason=$finishReason)")
        }

        val bytes = Base64.getDecoder().decode(base64Data)

        // Дебаг-инфо: разрешение, модель, токены и ориентировочная цена
        val usage = jsonResponse["usageMetadata"]?.jsonObject
        val inTok = usage?.get("promptTokenCount")?.jsonPrimitive?.intOrNull ?: 0
        val outTok = usage?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull
            ?: usage?.get("totalTokenCount")?.jsonPrimitive?.intOrNull?.minus(inTok) ?: 0
        val dims = runCatching {
            javax.imageio.ImageIO.read(bytes.inputStream())?.let { "${it.width}x${it.height}" }
        }.getOrNull() ?: "?x?"
        val price = pricing[imageModel]
        val cost = if (price != null) inTok / 1_000_000.0 * price.inputPerMTok + price.perImageOut else null
        val costStr = cost?.let { " · ~$" + ((it * 10000).toInt() / 10000.0) } ?: ""
        val debugInfo = "$dims · $imageModel · in $inTok tok · out $outTok tok$costStr"

        val tempFile = File.createTempFile("gen_image_", ".png")
        return try {
            tempFile.writeBytes(bytes)
            ImageGenResult(ImageServer.uploadImageOnServer(tempFile).originalUrl, debugInfo)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Grok Imagine (xAI): без референса — /images/generations, с референсом
     * (аватарка или прошлая сцена) — /images/edits с data-URL картинкой.
     */
    private suspend fun generateViaXai(prompt: String, refFile: File?): ImageGenResult {
        val endpoint = if (refFile != null) {
            "https://api.x.ai/v1/images/edits"
        } else {
            "https://api.x.ai/v1/images/generations"
        }
        val body = buildJsonObject {
            put("model", xaiImageModel)
            put("prompt", prompt)
            put("response_format", "b64_json")
            if (refFile != null) {
                putJsonObject("image") {
                    put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(refFile.readBytes()))
                    put("type", "image_url")
                }
            }
        }

        val response = httpClient.post(endpoint) {
            header(HttpHeaders.Authorization, "Bearer $xaiApiKey")
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        val text = response.bodyAsText()
        if (response.status != HttpStatusCode.OK) {
            logger.error("xAI image API error: ${response.status}, body: ${text.take(400)}")
            if (Regex("(?i)content|policy|moderat|safety|prohibited").containsMatchIn(text)) {
                throw CensoredException("xAI image blocked: ${text.take(200)}")
            }
            throw Exception("xAI image API error: ${response.status}")
        }

        val json = Json.parseToJsonElement(text).jsonObject
        val b64 = json["data"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("b64_json")?.jsonPrimitive?.contentOrNull
            ?: throw Exception("No image data in xAI response")
        val bytes = Base64.getDecoder().decode(b64)

        val ticks = json["usage"]?.jsonObject?.get("cost_in_usd_ticks")?.jsonPrimitive?.longOrNull
        val cost = ticks?.let { it / 10_000_000_000.0 }
        val dims = runCatching {
            javax.imageio.ImageIO.read(bytes.inputStream())?.let { "${it.width}x${it.height}" }
        }.getOrNull() ?: "?x?"
        val costStr = cost?.let { " · ~$" + ((it * 10000).toInt() / 10000.0) } ?: ""
        val debugInfo = "$dims · $xaiImageModel$costStr"

        val tempFile = File.createTempFile("gen_image_", ".jpg")
        return try {
            tempFile.writeBytes(bytes)
            ImageGenResult(ImageServer.uploadImageOnServer(tempFile).originalUrl, debugInfo)
        } finally {
            tempFile.delete()
        }
    }
}
