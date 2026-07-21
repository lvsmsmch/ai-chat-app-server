package com.lvsmsmch.aichat.chat.network

import com.lvsmsmch.aichat.character.database.CharacterDbo
import com.lvsmsmch.aichat.chat.database.ChatDbo
import com.lvsmsmch.aichat.chat.database.MessageDbo
import com.lvsmsmch.aichat.utils.defaultJson
import com.lvsmsmch.aichat.utils.logger
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
import kotlin.random.Random

/** Генерация заблокирована фильтром контента — юзеру показывается отдельная причина. */
class CensoredException(message: String) : Exception(message)

/** Коды причин FAILED-сообщений (хранятся в MessageDbo.failReason и уходят клиенту). */
object FailReason {
    const val CENSORED = "censored"
    const val ERROR = "error"
}

object AiMessageGeneratorUtil {

    private val groqApiUrl
        get() = System.getenv("GROQ_API_URL") ?: throw Exception("Missing GROQ_API_URL key")
    private val groqApiKey
        get() = System.getenv("GROQ_API_KEY") ?: throw Exception("Missing GROQ_API_KEY key")
    private val groqModel
        get() = System.getenv("GROQ_MODEL") ?: throw Exception("Missing GROQ_MODEL key")
    private val useGroq
        get() = (System.getenv("USE_GROQ") ?: throw Exception("Missing USE_GROQ key")).toBoolean()

    private val openAiApiUrl
        get() = System.getenv("OPEN_AI_API_URL") ?: throw Exception("Missing OPEN_AI_API_URL key")
    private val openAiApiKey
        get() = System.getenv("OPEN_AI_API_KEY") ?: throw Exception("Missing OPEN_AI_API_KEY key")
    private val openAiModel
        get() = System.getenv("OPEN_AI_MODEL") ?: throw Exception("Missing OPEN_AI_MODEL key")
    private val useOpenAi
        get() = (System.getenv("USE_OPEN_AI") ?: throw Exception("Missing USE_OPEN_AI key")).toBoolean()

    private val geminiApiUrl
        get() = System.getenv("GEMINI_API_URL") ?: "https://generativelanguage.googleapis.com/v1beta/models"
    private val geminiApiKey
        get() = System.getenv("GEMINI_API_KEY") ?: throw Exception("Missing GEMINI_API_KEY key")
    private val geminiModel
        get() = System.getenv("GEMINI_MODEL") ?: "gemini-1.5-flash"
    // Тиры умного даунгрейда: MID после месячного порога (фри) / 200 в день
    // (премиум), LOW после 500 в день у премиума. Логика — ModelTierPicker.
    private val geminiModelMid
        get() = System.getenv("GEMINI_MODEL_MID") ?: "gemini-3.1-flash-lite"
    private val geminiModelLow
        get() = System.getenv("GEMINI_MODEL_LOW") ?: "gemini-2.5-flash-lite"
    private val useGemini
        get() = (System.getenv("USE_GEMINI") ?: throw Exception("Missing USE_GEMINI key")).toBoolean()

    private val temperature
        get() = (System.getenv("AI_TEMPERATURE") ?: throw Exception("Missing AI_TEMPERATURE key")).toFloat()
    private val aiPrompt
        get() = System.getenv("AI_PROMPT") ?: throw Exception("Missing AI_PROMPT key")
    private val aiGroupChatPrompt
        get() = System.getenv("AI_GROUP_CHAT_PROMPT") ?: throw Exception("Missing AI_GROUP_CHAT_PROMPT key")


    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(defaultJson)
        }
    }

    /**
     * Защита от сжигания токенов ретраями: сообщение, чья генерация уже словила
     * цензуру во ВСЕХ попытках, повторно в API не отправляется — короткая пауза
     * для видимости и сразу censored. Изменённое сообщение = новый ключ = новый шанс.
     */
    private val censoredMessages = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun censorKey(chatDbo: ChatDbo, messagesHistory: List<MessageDbo>): String =
        chatDbo.id + ":" + (messagesHistory.lastOrNull { it.isSentByUser }?.text?.hashCode() ?: 0)

    suspend fun generateAiMessageWithStreaming(
        chatDbo: ChatDbo,
        characterDbo: CharacterDbo,
        participants: List<CharacterDbo>,
        messagesHistory: List<MessageDbo>,
        responseLanguage: String? = null,
        ownerDbo: com.lvsmsmch.aichat.user.database.UserDbo? = null,
        onMsgTextUpdate: suspend (String) -> Unit,
        onFinished: suspend (String) -> Unit,
        onError: suspend (String) -> Unit
    ) {
        try {
            // Сервер сам решает, будут ли в ЭТОМ ответе *действия* — по режиму диалога:
            // обычная переписка короткими репликами — ~20% сообщений с действием;
            // юзер пишет развёрнуто — ~50%; юзер сам ролеплеит со звёздочками —
            // действия свободно, сколько угодно и где угодно.
            val recentUser = messagesHistory.filter { it.isSentByUser }.takeLast(3).map { it.text }
            val userRoleplays = recentUser.any { it.contains("*") }
            val lastUserLen = recentUser.lastOrNull()?.length ?: 0
            val avgUserLen = if (recentUser.isEmpty()) 0 else recentUser.sumOf { it.length } / recentUser.size
            val actionChance = when {
                userRoleplays -> 1.0
                lastUserLen > 100 || avgUserLen > 80 -> 0.5
                else -> 0.2
            }
            val allowAction = kotlin.random.Random.nextDouble() < actionChance
            // Позиция действия тоже рандомизируется — иначе модель лепит всё в начало
            val positionRoll = kotlin.random.Random.nextDouble()
            val actionPosition = if (positionRoll < 0.4) "end" else if (positionRoll < 0.75) "middle" else "start"
            // Формулировки МЯГКИЕ (prefer/feel free, не запреты): умная модель
            // следует им слишком буквально, а живость требует свободы — персонаж,
            // который физически что-то делает, должен мочь показать это действием
            val actionNudge = when {
                userRoleplays ->
                    " The user is roleplaying with *actions* in asterisks: match their style - use actions freely, " +
                    "several are fine, anywhere in the message."
                allowAction ->
                    " Feel free to include a brief action in *asterisks* (say, at the " + actionPosition +
                    " of the message) if it fits the moment."
                else ->
                    " Prefer plain speech without asterisks this time - but if your character is physically " +
                    "doing something or reacting strongly, you may show it with a brief *action*."
            }

            // Длина: базово равняемся на сообщение юзера (короткое - коротко),
            // но с явным разрешением развернуться, когда сцена живая - иначе
            // послушная модель отвечает парой слов даже посреди активной игры.
            // Копировать длину СВОИХ прошлых ответов запрещено (шаблонная ловушка).
            val lengthNudge = " As a baseline, match the length of your reply to the user's LAST message: " +
                "roughly the same amount of text, up to twice as long; a short message usually gets " +
                "a short reply. But trust the moment: if they ask a question or ask you to tell " +
                "something, or the scene is lively (a game, physical activity, strong emotion), " +
                "a longer, more expressive reply is welcome. " +
                "NEVER base the length on your own previous replies."

            val styleNudge = actionNudge + lengthNudge

            if (messagesHistory.isEmpty() && characterDbo.initialMessage.isNotBlank()) {
                simulateStreaming(characterDbo.initialMessage, onMsgTextUpdate, onFinished)
            } else if (useGroq || useOpenAi) {

                val url = if (useGroq) groqApiUrl else openAiApiUrl
                val key = if (useGroq) groqApiKey else openAiApiKey
                val model = if (useGroq) groqModel else openAiModel

                val messages = buildMessageHistory(chatDbo, characterDbo, participants, messagesHistory, responseLanguage) +
                    listOf(mapOf(
                        "role" to "user",
                        "content" to "[Instruction for your next reply - do not mention it:" + styleNudge + "]"
                    ))
                val requestBody = buildRequestBody(messages, model = model, stream = false)

                logger.debug("Messages count: ${messages.size}")

                var lastException: Exception? = null
                var isSuccessfulGeneration = false
                var attempt = 0
                val maxRetries = 3
                val retryDelayMs = 500L

                while (!isSuccessfulGeneration && attempt < maxRetries) {
                    try {
                        attempt++
                        logger.debug("Attempt ${attempt} of $maxRetries")

                        val response = httpClient.post(url) {
                            header(HttpHeaders.Authorization, "Bearer $key")
                            header(HttpHeaders.Accept, "text/event-stream")
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                        }

                        logger.debug("Response status: ${response.status}")

                        val fullMessage = processNonStreamingResponse(response)
                            .removePrefixIgnoringCase("${characterDbo.name}: ")
                        simulateStreaming(fullMessage, onMsgTextUpdate, onFinished)

                        isSuccessfulGeneration = true
                    } catch (e: Exception) {
                        lastException = e
                        logger.error("Attempt ${attempt} failed: ${e.message}")

                        if (attempt < maxRetries) {
                            logger.debug("Retrying in ${retryDelayMs}ms...")
                            delay(retryDelayMs)
                        }
                    }
                }

                if (!isSuccessfulGeneration) {
                    lastException?.let {
                        throw Exception("Failed to generate after $maxRetries attempts with error: $it")
                    }
                }
            } else if (useGemini) {
                // Инструкция стиля — ПОСЛЕДНИМ сообщением: у истории чата больший
                // вес, чем у системного промпта, и модель слушает то, что ближе к концу
                val styleMessage = mapOf(
                    "role" to "user",
                    "parts" to listOf(mapOf("text" to
                        "[Instruction for your next reply - do not mention it:" + styleNudge + "]"))
                )
                val messages = buildGeminiMessageHistory(chatDbo, characterDbo, participants, messagesHistory) + styleMessage
                val requestBody = buildGeminiRequestBody(messages, chatDbo, characterDbo, responseLanguage)

                logger.debug("Sending request to Gemini API")

                // Это сообщение уже цензурилось всеми попытками — в API не ходим
                val censorKey = censorKey(chatDbo, messagesHistory)
                if (censorKey in censoredMessages) {
                    delay(1500)
                    throw CensoredException("message already censored before, skipping API call")
                }

                // Ретраи и на сетевые ошибки, и на цензуру: входной фильтр Gemini
                // вероятностный и даёт ложные блоки невинных диалогов волнами —
                // повтор почти всегда проходит. Реальный запрещённый контент
                // блокируется во всех попытках и честно уходит юзеру как censored.
                // Модель по тиру юзера: фри после месячного порога и премиум
                // после дневных порогов едут на модели дешевле
                val tier = ownerDbo?.let { com.lvsmsmch.aichat.user.database.ModelTierPicker.pick(it) }
                    ?: com.lvsmsmch.aichat.user.database.ModelTierPicker.Tier.TOP
                val tierModel = when (tier) {
                    com.lvsmsmch.aichat.user.database.ModelTierPicker.Tier.TOP -> geminiModel
                    com.lvsmsmch.aichat.user.database.ModelTierPicker.Tier.MID -> geminiModelMid
                    com.lvsmsmch.aichat.user.database.ModelTierPicker.Tier.LOW -> geminiModelLow
                }
                logger.debug("Gemini model tier: $tier ($tierModel)")

                var fullMessage: String? = null
                var lastError: Exception? = null
                for (attempt in 1..3) {
                    try {
                        val response = httpClient.post("$geminiApiUrl/$tierModel:generateContent?key=$geminiApiKey") {
                            contentType(ContentType.Application.Json)
                            setBody(requestBody)
                        }
                        logger.debug("Response status: ${response.status} (attempt $attempt)")
                        fullMessage = processGeminiResponse(response, characterDbo)
                        break
                    } catch (e: Exception) {
                        lastError = e
                        logger.error("Gemini attempt $attempt failed: ${e.message}")
                        if (attempt < 3) delay(400)
                    }
                }
                if (fullMessage == null) {
                    // Все попытки в цензуру — чёрный список до изменения сообщения
                    if (lastError is CensoredException) censoredMessages.add(censorKey)
                    throw lastError ?: Exception("Gemini generation failed")
                }
                simulateStreaming(fullMessage, onMsgTextUpdate, onFinished)
            } else {
                simulateStreaming(possibleFakeResponses.random(), onMsgTextUpdate, onFinished)
            }
        } catch (e: CensoredException) {
            logger.error("Generation blocked by content filter: ${e.message}")
            onError(FailReason.CENSORED)
        } catch (e: Exception) {
            logger.error("Full error: ${e.message}", e)
            onError(FailReason.ERROR)
        }
    }

    private fun buildMessageHistory(
        chatDbo: ChatDbo,
        characterDbo: CharacterDbo,
        participants: List<CharacterDbo>,
        messagesHistory: List<MessageDbo>,
        responseLanguage: String? = null,
        maxCharacters: Int = 4000
    ): List<Map<String, String>> {
        val systemMessage = mapOf(
            "role" to "system",
            "content" to buildSystemPrompt(chatDbo, characterDbo, responseLanguage)
        )

        val selectedMessages = mutableListOf<MessageDbo>()
        var currentCharacterCount = 0

        for (message in messagesHistory.reversed()) {
            if (message.text.isBlank()) continue

            val messageLength = message.text.length

            if (currentCharacterCount + messageLength > maxCharacters) {
                break
            }

            selectedMessages.add(message)
            currentCharacterCount += messageLength
        }

        val historyMessages = selectedMessages.reversed().map { message ->
            val messageMap = mutableMapOf<String, String>()

            messageMap["role"] = if (message.isSentByUser) "user" else "assistant"

            messageMap["content"] = if (!message.isSentByUser && chatDbo.characterIds.size > 1) {
                val senderName = participants.find { it.id == message.senderId }?.name ?: "Unknown"
                "$senderName: ${message.text}"
            } else {
                message.text
            }

            if (!message.isSentByUser) {
                participants.find { it.id == message.senderId }?.let { sender ->
                    messageMap["name"] = sender.name
                        .replace(Regex("[^a-zA-Z0-9_]"), "_")
                        .take(64)
                }
            }

            messageMap.toMap()
        }

        return listOf(systemMessage) + historyMessages
    }

    private fun buildSystemPrompt(
        chatDbo: ChatDbo,
        characterDbo: CharacterDbo,
        responseLanguage: String? = null,
    ): String {
        val template = aiPrompt + if (chatDbo.characterIds.size > 1) aiGroupChatPrompt else ""

        // Жёсткая языковая инструкция: даже с локализованным промптом модель
        // иногда сползает в английский без явного требования
        val languageInstruction = when (responseLanguage) {
            "ru" -> "\n\nIMPORTANT: Always respond ONLY in Russian (по-русски). " +
                "Every message you write must be in Russian, including actions in asterisks."
            else -> ""
        }

        return template
            .replace("{CHARACTER_NAME}", characterDbo.name)
            .replace("{USER_PROMPT}", characterDbo.prompt.takeIf { it.isNotBlank() } ?: "") +
            languageInstruction
    }

    private fun buildRequestBody(
        messages: List<Map<String, String>>,
        model: String,
        stream: Boolean
    ): JsonObject {
        return buildJsonObject {
            put("model", model)
            putJsonArray("messages") {
                messages.forEach { message ->
                    addJsonObject {
                        put("role", message["role"]!!)
                        put("content", message["content"]!!)
                    }
                }
            }
            // Потолок-страховка от «полотен»: краткость требует промпт, а это кап
            put("max_completion_tokens", 400)
            put("temperature", temperature)
            put("stream", stream)
        }
    }

    private suspend fun processNonStreamingResponse(response: HttpResponse): String {
        if (response.status != HttpStatusCode.OK) {
            val errorBody = response.bodyAsText()
            logger.error("API error: ${response.status}, body: $errorBody")
            throw Exception("API error: ${response.status} - $errorBody")
        }

        val responseBody = response.bodyAsText()
        val jsonResponse = Json.parseToJsonElement(responseBody).jsonObject
        val choices = jsonResponse["choices"]?.jsonArray
        val content = choices?.get(0)?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonPrimitive?.content

        if (content == "") throw Exception("Empty content in response")

        return content ?: throw Exception("Null content in response")
    }

    private suspend fun processStreamingResponse(
        response: HttpResponse,
        onMsgTextUpdate: suspend (String) -> Unit,
        onFinished: suspend (String) -> Unit,
        responseLanguage: String? = null
    ) {
        if (response.status != HttpStatusCode.OK) {
            val errorBody = response.bodyAsText()
            logger.error("API error: ${response.status}, body: $errorBody")
            throw Exception("API error: ${response.status} - $errorBody")
        }

        val channel = response.bodyAsChannel()
        val fullMessage = StringBuilder()
        var hasContent = false

        try {
            channel.readUTF8LineSequence()
                .filter { line -> line.startsWith("data: ") && line != "data: [DONE]" }
                .map { line -> line.removePrefix("data: ") }
                .filter { data -> data.isNotBlank() }
                .collect { data ->
                    try {

                        val jsonData = Json.parseToJsonElement(data).jsonObject
                        val choices = jsonData["choices"]?.jsonArray

                        if (!choices.isNullOrEmpty()) {
                            val delta = choices[0].jsonObject["delta"]?.jsonObject
                            val content = delta?.get("content")?.jsonPrimitive?.contentOrNull

                            if (!content.isNullOrEmpty()) {
                                hasContent = true
                                fullMessage.append(content)


                                onMsgTextUpdate(fullMessage.toString())
                            }

                            val finishReason = choices[0].jsonObject["finish_reason"]?.jsonPrimitive?.contentOrNull
                            if (finishReason == "stop") {
                                onFinished(fullMessage.toString())
                                return@collect
                            }
                        }
                    } catch (e: Exception) {
                        logger.error("Error parsing chunk: ${e.message}")
                    }
                }
        } catch (e: Exception) {
            throw Exception("Streaming error: ${e.message}")
        }

        if (!hasContent) {
            throw Exception("No content received from stream (empty response)")
        }

        onFinished(fullMessage.toString())
    }


    private fun buildGeminiMessageHistory(
        chatDbo: ChatDbo,
        characterDbo: CharacterDbo,
        participants: List<CharacterDbo>,
        messagesHistory: List<MessageDbo>,
        maxCharacters: Int = 4000
    ): List<Map<String, Any>> {
        val selectedMessages = mutableListOf<MessageDbo>()
        var currentCharacterCount = 0

        for (message in messagesHistory.reversed()) {
            if (message.text.isBlank()) continue

            val messageLength = message.text.length
            if (currentCharacterCount + messageLength > maxCharacters) {
                break
            }

            selectedMessages.add(message)
            currentCharacterCount += messageLength
        }

        val isGroupChat = chatDbo.characterIds.size > 1

        val history = selectedMessages.reversed().map { message ->
            val role = if (message.isSentByUser) "user" else "model"
            val content = if (!message.isSentByUser && isGroupChat) {
                val senderName = participants.find { it.id == message.senderId }?.name ?: "Unknown"
                message.text.addCharacterName(senderName)
            } else {
                message.text
            }

            mapOf(
                "role" to role,
                "parts" to listOf(mapOf("text" to content))
            )
        }

        val nextMessageForGroupChat = mapOf(
            "role" to "user",
            "parts" to listOf(mapOf("text" to "Continue dialog for [${characterDbo.name}], send next message."))
        )

        return if (isGroupChat) {
            history + nextMessageForGroupChat
        } else {
            history
        }
    }

    private fun buildGeminiRequestBody(
        messages: List<Map<String, Any>>,
        chatDbo: ChatDbo,
        characterDbo: CharacterDbo,
        responseLanguage: String? = null
    ): JsonObject {
        val systemPrompt = buildSystemPrompt(chatDbo, characterDbo, responseLanguage)

        return buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    addJsonObject {
                        put("text", systemPrompt)
                    }
                }
            }
            putJsonArray("contents") {
                messages.forEach { message ->
                    addJsonObject {
                        put("role", message["role"] as String)
                        putJsonArray("parts") {
                            (message["parts"] as List<Map<String, String>>).forEach { part ->
                                addJsonObject {
                                    put("text", part["text"]!!)
                                }
                            }
                        }
                    }
                }
            }
            putJsonObject("generationConfig") {
                put("temperature", temperature)
                put("maxOutputTokens", 400)
                // Thinking-модели (gemini-3.5-flash) без этого тратят весь лимит
                // токенов на размышления и возвращают пустой ответ
                putJsonObject("thinkingConfig") { put("thinkingBudget", 0) }
            }
            // Мягкие категории фильтра — выкл: дефолтные пороги ложно блокируют
            // безобидный ролеплей. Жёсткий фильтр (PROHIBITED_CONTENT) настройке
            // не поддаётся и продолжает работать.
            putJsonArray("safetySettings") {
                listOf(
                    "HARM_CATEGORY_HARASSMENT",
                    "HARM_CATEGORY_HATE_SPEECH",
                    "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                    "HARM_CATEGORY_DANGEROUS_CONTENT",
                ).forEach { category ->
                    addJsonObject {
                        put("category", category)
                        put("threshold", "BLOCK_NONE")
                    }
                }
            }
        }
    }

    private suspend fun processGeminiResponse(response: HttpResponse, characterDbo: CharacterDbo): String {
        if (response.status != HttpStatusCode.OK) {
            val errorBody = response.bodyAsText()
            logger.error("Gemini API error: ${response.status}, body: $errorBody")
            throw Exception("Gemini API error: ${response.status} - $errorBody")
        }

        val jsonResponse = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        // Цензура: промпт заблокирован целиком или кандидат оборван safety-фильтром
        jsonResponse["promptFeedback"]?.jsonObject
            ?.get("blockReason")?.jsonPrimitive?.contentOrNull?.let {
                throw CensoredException("promptFeedback.blockReason=$it")
            }

        val candidates = jsonResponse["candidates"]?.jsonArray
        val firstCandidate = candidates?.firstOrNull()?.jsonObject
        val finishReason = firstCandidate?.get("finishReason")?.jsonPrimitive?.contentOrNull
        if (finishReason in setOf("SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII", "IMAGE_SAFETY")) {
            throw CensoredException("finishReason=$finishReason")
        }

        val content = firstCandidate
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray
            ?.get(0)?.jsonObject
            ?.get("text")?.jsonPrimitive?.content

        if (content.isNullOrBlank()) {
            throw Exception("Empty or null content in Gemini response")
        }

        return content
            .removeBracketContent()
            // Модель иногда выплёвывает огрызки HTML из обучающих данных
            // (</blockquote>, <b> и т.п.) — юзеру такое видеть незачем
            .replace(Regex("</?[a-zA-Z][a-zA-Z0-9]*[^>]*>"), "")
            .replace("\n", "")
            .trim()
    }

    private suspend fun ByteReadChannel.readUTF8LineSequence(): Flow<String> = flow {
        val buffer = ByteArray(8192)
        val stringBuilder = StringBuilder()

        while (!isClosedForRead) {
            try {
                val bytesRead = readAvailable(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    val text = String(buffer, 0, bytesRead, Charsets.UTF_8)
                    stringBuilder.append(text)

                    val lines = stringBuilder.toString().split('\n')

                    for (i in 0 until lines.size - 1) {
                        val line = lines[i].trimEnd('\r')
                        if (line.isNotEmpty()) {
                            emit(line)
                        }
                    }

                    stringBuilder.clear()
                    stringBuilder.append(lines.last())
                }
            } catch (e: Exception) {
                break
            }
        }

        val remaining = stringBuilder.toString().trimEnd('\r')
        if (remaining.isNotEmpty()) {
            emit(remaining)
        }
    }

    private suspend fun simulateStreaming(
        message: String,
        onChunk: suspend (String) -> Unit,
        onFinished: suspend (String) -> Unit,
    ) {
        val currentMessage = StringBuilder()
        val words = message.split(" ")

        for (i in words.indices) {
            if (currentMessage.isNotEmpty()) {
                currentMessage.append(" ")
            }
            currentMessage.append(words[i])
            onChunk(currentMessage.toString())
            delay(Random.nextLong(50, 150))
        }


        delay(100)
        onFinished(currentMessage.toString())
    }

    private val possibleFakeResponses = listOf(
        "That's really interesting! Tell me more about that.",
        "I see what you mean. It reminds me of something similar I experienced.",
        "Wow, I hadn't thought about it that way before. Thanks for sharing!",
        "That sounds challenging, but I believe you can handle it.",
        "Haha, that's funny! You always know how to make me smile.",
        "I completely agree with you on that point.",
        "That's a great question. Let me think about it for a moment...",
        "I appreciate you telling me this. It means a lot to me.",
        "You're absolutely right about that. I've noticed the same thing.",
        "That's exciting news! I'm happy for you.",
        "I understand how you feel. Sometimes things can be overwhelming.",
        "You have such a unique perspective on things. I really admire that.",
        "That reminds me of a time when something similar happened to me.",
        "I think you're being too hard on yourself. You're doing great! " +
                "That's a really good observation. " +
                "I hadn't considered that angle. " +
                "You always ask the most thoughtful questions.",
        "I can relate to that feeling. It's more common than you might think. " +
                "That sounds like it was quite an adventure! " +
                "I'm impressed by how you handled that situation.",
        "You're such a good listener. Thank you for being here. " +
                "hat's a fascinating topic. I could talk about it for hours! " +
                "I think you made the right choice there. Trust your instincts.",
        "You have a way of explaining things that makes them so clear. " +
                "I'm curious to hear more about your thoughts on this. " +
                "That must have been quite an experience for you!",
        "Senpai, you're so thoughtful! (◕‿◕) Ehh?! Really?! That's amazing! ✨ " +
                "Hmm, that's quite interesting... *adjusts glasses* " +
                "Uwaa! I never thought about it like that before! " +
                "Ara ara~ you're so clever! 💕 " +
                "That sounds like the beginning of an epic adventure! " +
                "Kyaa! You surprised me with that response! " +
                "Hehe, you're really something special, you know that? " +
                "*blushes* T-that's really sweet of you to say... " +
                "Sugoi! You're incredible at explaining things! " +
                "Mou~ you're making me think too hard! (>_<) " +
                "That reminds me of something from my favorite manga! " +
                "Wah! You're so cool! I wish I could be like you! " +
                "*nods enthusiastically* Yes, yes! I totally agree! " +
                "Ehehe~ you always know just what to say! ♪"
    )

    private fun String.removePrefixIgnoringCase(prefix: String): String {
        if (startsWith(prefix, ignoreCase = true)) {
            return substring(prefix.length)
        }
        return this
    }

    fun String.removeBracketContent(): String {
        return this.replace(Regex("\\[.*?\\]"), "")
    }

    fun String.addCharacterName(characterName: String): String {
        return "[$characterName] $this"
    }
}
