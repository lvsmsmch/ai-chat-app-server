package com.lvsmsmch.aichat.chat.network

import com.lvsmsmch.aichat.character.database.CharacterDbo
import com.lvsmsmch.aichat.chat.database.MessageDbo
import com.lvsmsmch.aichat.utils.ImageServer
import com.lvsmsmch.aichat.utils.defaultJson
import com.lvsmsmch.aichat.utils.logger
import io.ktor.client.*
import io.ktor.client.call.*
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
        // Замер полного времени (генерация + докачка + даунскейл) — уточняет
        // ожидаемое время для прогресс-кружка на клиенте
        val etaKey = ImageGenEta.providerKey(useTopModel)
        val startedAt = System.currentTimeMillis()
        return generateImageInner(characterDbo, messagesHistory, useTopModel).also {
            ImageGenEta.record(etaKey, System.currentTimeMillis() - startedAt)
        }
    }

    private suspend fun generateImageInner(
        characterDbo: CharacterDbo,
        messagesHistory: List<MessageDbo>,
        useTopModel: Boolean,
    ): ImageGenResult {
        // [DEBUG] Оверрайд из настроек приложения бьёт и гибрид, и тиры
        val debugModel = com.lvsmsmch.aichat.utils.DebugOverrides.imageModel
        val imageModel = debugModel?.takeIf { !it.startsWith("grok") }
            ?: if (useTopModel) imageModelTop else imageModelMid

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

        // Куда пойдёт запрос: [DEBUG] оверрайд решает сам, иначе гибрид:
        // ОСНОВНОЙ провайдер — Seedream (fal); юзер «переел» (превысил месячный
        // топ-порог) — переключаем на Grok (дешевле). Gemini остался только
        // через дебаг-оверрайд
        val goXai = if (debugModel != null) debugModel.startsWith("grok")
        else !useTopModel
        val goFal = if (debugModel != null) {
            debugModel.startsWith("flux") || debugModel.startsWith("seedream")
        } else useTopModel

        // Референс внешности/стиля: последняя сгенерированная картинка этого чата
        // (держим дизайн, стиль и ОБОИХ участников сцены — юзер иначе рисуется
        // каждый раз по-новому), для первой генерации — аватарка персонажа
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
                    "\nThe attached image is the previous scene of this chat. Keep the SAME " +
                        "character design and art style; staying in the same location is fine " +
                        "if the conversation continues there, but you MUST render a completely " +
                        "new shot: a clearly DIFFERENT camera angle, different distance and " +
                        "framing, updated poses and details for the current moment. Returning " +
                        "the same or nearly the same composition as the reference is a failure."
                )
                avatarFile != null -> append(
                    "\nThe attached image is ONLY a face/appearance reference for the character. " +
                        "Do NOT reproduce, crop, zoom or restyle that exact picture and do NOT " +
                        "reuse its framing or visual effects. Paint a completely different " +
                        "image: a new scene from the conversation, a different camera angle " +
                        "and distance, a full composition with a background."
                )
            }
            append(
                "\nStyle: high quality digital art. Correct anatomy is critical: every person " +
                    "has exactly two arms and two hands, no extra, missing or deformed limbs. " +
                    "STRICT: depict ONLY the people who are actually part of this conversation " +
                    "and scene - the character and, if present in the scene, the user. Do NOT " +
                    "add any extra people, bystanders, crowds or background characters. " +
                    "No text or captions in the image."
            )
        }

        if (goXai) return generateViaXai(prompt, refFile)
        if (goFal) return generateViaFal(debugModel ?: "seedream-4.5", prompt, refFile)

        // Gemini: генерация + QA-проверка анатомии дешёвой vision-моделью.
        // Каша с конечностями — главный провал модели; ловим и перегенерируем один раз
        var attempt = generateViaGeminiOnce(prompt, refFile, imageModel)
        if (runCatching { hasAnatomyDefects(attempt.first) }.getOrDefault(false)) {
            logger.info("Image QA: anatomy defects detected, regenerating once")
            // Вторая попытка тоже может уйти в цензуру/ошибку — тогда оставляем первую
            runCatching { generateViaGeminiOnce(prompt, refFile, imageModel) }.getOrNull()?.let {
                attempt = it.copy(second = it.second + " · QA retry")
            }
        }
        val (bytes, debugInfo) = attempt

        val tempFile = File.createTempFile("gen_image_", ".png")
        return try {
            tempFile.writeBytes(bytes)
            val uploadedUrl = ImageServer.uploadImageOnServer(tempFile).originalUrl
            ImageGenResult(uploadedUrl, finalDims(uploadedUrl) + debugInfo)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Разрешение ФИНАЛЬНОГО файла (после даунскейла ImageServer до 1024):
     * модели генерят 2048+, а храним мы сжатую версию — дебаг-подпись в чате
     * должна совпадать с тем, что реально открывается на фул-скрине.
     */
    private fun finalDims(uploadedUrl: String): String =
        runCatching {
            ImageServer.localFileForUrl(uploadedUrl)?.let { f ->
                javax.imageio.ImageIO.read(f)?.let {
                    "${it.width}x${it.height} · ${(f.length() / 1024).coerceAtLeast(1)} KB · "
                }
            }
        }.getOrNull() ?: ""

    /** Один заход в Gemini: картинка (байты) + дебаг-строка. */
    private suspend fun generateViaGeminiOnce(
        prompt: String,
        refFile: File?,
        imageModel: String,
    ): Pair<ByteArray, String> {
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
        val price = pricing[imageModel]
        val cost = if (price != null) inTok / 1_000_000.0 * price.inputPerMTok + price.perImageOut else null
        val costStr = cost?.let { " · ~$" + ((it * 10000).toInt() / 10000.0) } ?: ""
        val debugInfo = "$imageModel · in $inTok tok · out $outTok tok$costStr"

        return bytes to debugInfo
    }

    /**
     * QA-проверка анатомии дешёвой vision-моделью: лишние/недостающие конечности,
     * дублированные лица, ничьи руки. Сбой проверки = дефектов нет (не блокируем выдачу).
     */
    private suspend fun hasAnatomyDefects(imageBytes: ByteArray): Boolean {
        val qaModel = System.getenv("IMAGE_QA_MODEL") ?: "gemini-3.1-flash-lite"
        val requestBody = buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") {
                        addJsonObject {
                            put(
                                "text",
                                "You are a strict image quality checker. Look at the image and " +
                                    "answer with exactly one word: YES or NO. Answer YES only if " +
                                    "the image has obvious anatomy errors: extra or missing arms, " +
                                    "hands or legs, a hand with more than five fingers, duplicated " +
                                    "or misplaced faces or heads, or body parts that belong to " +
                                    "nobody. Minor style imperfections or stylized proportions are NO."
                            )
                        }
                        addJsonObject {
                            putJsonObject("inlineData") {
                                put("mimeType", "image/png")
                                put("data", Base64.getEncoder().encodeToString(imageBytes))
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", 0)
                put("maxOutputTokens", 10)
            }
        }
        val response = httpClient.post("$geminiApiUrl/$qaModel:generateContent?key=$geminiApiKey") {
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        if (response.status != HttpStatusCode.OK) {
            logger.error("Image QA check failed: ${response.status}")
            return false
        }
        val answer = Json.parseToJsonElement(response.bodyAsText()).jsonObject["candidates"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray
            ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
            ?.joinToString(" ")?.trim().orEmpty()
        logger.debug("Image QA answer: $answer")
        return answer.uppercase().startsWith("YES")
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
        val costStr = cost?.let { " · ~$" + ((it * 10000).toInt() / 10000.0) } ?: ""
        val debugInfo = "$xaiImageModel$costStr"

        val tempFile = File.createTempFile("gen_image_", ".jpg")
        return try {
            tempFile.writeBytes(bytes)
            val uploadedUrl = ImageServer.uploadImageOnServer(tempFile).originalUrl
            ImageGenResult(uploadedUrl, finalDims(uploadedUrl) + debugInfo)
        } finally {
            tempFile.delete()
        }
    }

    // ---------- fal.ai: FLUX.2 [pro] и Seedream 4.5 (тестовый провайдер) ----------

    private val falApiKey
        get() = System.getenv("FAL_API_KEY") ?: throw Exception("Missing FAL_API_KEY key")

    /**
     * Синхронный вызов fal.run. Референс (аватар) уходит как data-URI в image_urls
     * edit-эндпоинта; без референса — text-to-image. Safety checker выключен:
     * цензуру нам честнее показывает сама модель.
     */
    private suspend fun generateViaFal(model: String, prompt: String, refFile: File?): ImageGenResult {
        val isFlux = model.startsWith("flux")
        val falModelId = when {
            isFlux && refFile != null -> "fal-ai/flux-2-pro/edit"
            isFlux -> "fal-ai/flux-2-pro"
            refFile != null -> "fal-ai/bytedance/seedream/v4.5/edit"
            else -> "fal-ai/bytedance/seedream/v4.5/text-to-image"
        }
        val requestBody = buildJsonObject {
            put("prompt", prompt)
            if (refFile != null) {
                putJsonArray("image_urls") {
                    add(JsonPrimitive("data:image/jpeg;base64," +
                        Base64.getEncoder().encodeToString(refFile.readBytes())))
                }
            }
            put("enable_safety_checker", false)
        }
        val response = httpClient.post("https://fal.run/$falModelId") {
            header(HttpHeaders.Authorization, "Key $falApiKey")
            contentType(ContentType.Application.Json)
            setBody(requestBody)
        }
        if (response.status != HttpStatusCode.OK) {
            val errorBody = response.bodyAsText()
            logger.error("fal API error ($falModelId): ${response.status}, body: ${errorBody.take(500)}")
            // Модерация fal/модели — честная цензура, а не сбой
            if (errorBody.contains("nsfw", ignoreCase = true) ||
                errorBody.contains("safety", ignoreCase = true) ||
                errorBody.contains("content policy", ignoreCase = true)
            ) {
                throw CensoredException("fal moderation: ${errorBody.take(200)}")
            }
            throw Exception("fal API error: ${response.status}")
        }
        val jsonResponse = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val imageUrl = jsonResponse["images"]?.jsonArray
            ?.firstOrNull()?.jsonObject
            ?.get("url")?.jsonPrimitive?.contentOrNull
            ?: throw Exception("No image in fal response ($falModelId)")
        // Картинка приходит ссылкой на CDN fal — забираем байты к себе
        val bytes: ByteArray = httpClient.get(imageUrl).body()

        // Ориентировочные цены fal: flux-2-pro ~$0.03/MP, seedream 4.5 ~$0.04/img
        val cost = if (isFlux) 0.03 else 0.04
        val debugInfo = "$model (fal) · ~$" + ((cost * 10000).toInt() / 10000.0)

        val tempFile = File.createTempFile("gen_image_", ".jpg")
        return try {
            tempFile.writeBytes(bytes)
            val uploadedUrl = ImageServer.uploadImageOnServer(tempFile).originalUrl
            ImageGenResult(uploadedUrl, finalDims(uploadedUrl) + debugInfo)
        } finally {
            tempFile.delete()
        }
    }
}
