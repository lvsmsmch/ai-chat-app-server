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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.random.Random

object AiMessageGeneratorUtil {

    private val openAiApiUrl
        get() = System.getenv("OPEN_AI_API_URL") ?: throw Exception("Missing OPEN_AI_API_URL key")
    private val openAiApiKey
        get() = System.getenv("OPEN_AI_API_KEY") ?: throw Exception("Missing OPEN_AI_API_KEY key")
    private val useOpenAi
        get() = (System.getenv("USE_OPEN_AI") ?: throw Exception("Missing USE_OPEN_AI key"))
            .toBoolean()

    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(defaultJson)
        }
    }

    suspend fun generateAiMessageWithStreaming(
        chatDbo: ChatDbo,
        characterDbo: CharacterDbo,
        participants: List<CharacterDbo>,
        messagesHistory: List<MessageDbo>,
        onChunk: suspend (String) -> Unit,
        onFinished: suspend (String) -> Unit,
        onError: suspend (String) -> Unit
    ) {
        try {
            if (useOpenAi) {
                logger.debug("API Key starts with: ${openAiApiKey.take(10)}...")
                logger.debug("API URL: $openAiApiUrl")

//                val testResponse = httpClient.get("https://api.openai.com/v1/models") {
//                    header(HttpHeaders.Authorization, "Bearer $openAiApiKey")
//                }
//                logger.debug("Models endpoint status: ${testResponse.status}")
//

                val messages = buildMessageHistory(chatDbo, characterDbo, participants, messagesHistory)
                val requestBody = buildRequestBody(messages, stream = true)

                logger.debug("Sending request to: $openAiApiUrl")
                logger.debug("Request body: ${Json.encodeToString(requestBody)}")
                logger.debug("Messages count: ${messages.size}")

                val response = httpClient.post(openAiApiUrl) {
                    header(HttpHeaders.Authorization, "Bearer $openAiApiKey")
                    header(HttpHeaders.Accept, "text/event-stream")
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

                logger.debug("Response status: ${response.status}")
                logger.debug("Response headers: ${response.headers}")

                processStreamingResponse(response, onChunk, onFinished)
            } else {
                var textForSimulation: String? = null
                characterDbo.initialMessage.takeIf { messagesHistory.isEmpty() && it.isNotBlank() }?.let {
                    textForSimulation = it
                }
                val shouldStreamFakeResponse = true // todo remove later
                if (textForSimulation == null && shouldStreamFakeResponse) {
                    textForSimulation = possibleFakeResponses.random()
                }
                textForSimulation?.let {
                    simulateStreaming(it, onChunk, onFinished)
                }
            }
        } catch (e: Exception) {
            logger.error("Full error: ${e.message}", e) // ← Полный стектрейс
            onError(e.localizedMessage)
        }
    }

    /**
     * Строим историю сообщений для OpenAI API
     */
    private fun buildMessageHistory(
        chatDbo: ChatDbo,
        characterDbo: CharacterDbo,
        participants: List<CharacterDbo>,
        messagesHistory: List<MessageDbo>,
        maxCharacters: Int = 5000
    ): List<Map<String, String>> {
        val systemMessage = mapOf(
            "role" to "system",
            "content" to buildSystemPrompt(chatDbo, characterDbo)
        )

        // Берем сообщения с конца до достижения лимита
        val selectedMessages = mutableListOf<MessageDbo>()
        var currentCharacterCount = 0

        // Идем от последнего к первому
        for (message in messagesHistory.reversed()) {
            if (message.text.isBlank()) continue // Пропускаем пустые

            val messageLength = message.text.length

            // Проверяем, не превысим ли лимит
            if (currentCharacterCount + messageLength > maxCharacters) {
                break // Достигли лимита, останавливаемся
            }

            selectedMessages.add(message)
            currentCharacterCount += messageLength
        }

        // Переворачиваем обратно (от старых к новым)
        val historyMessages = selectedMessages.reversed().map { message ->
            val messageMap = mutableMapOf(
                "role" to if (message.isSentByUser) "user" else "assistant",
                "content" to message.text
            )

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

    /**
     * Строим системный промпт
     */
    private fun buildSystemPrompt(chatDbo: ChatDbo, characterDbo: CharacterDbo): String {
        return buildString {
            if (chatDbo.characterIds.size > 1) {
                append("You are ${characterDbo.name} in a group chat with multiple participants.")
                append(" Other AI characters may also be present.")
                append(" When responding, stay in character as ${characterDbo.name}.")
            } else {
                append("You are ${characterDbo.name}.")
            }

            if (characterDbo.prompt.isNotBlank()) {
                append(" Your character description: ${characterDbo.prompt}")
            }

            append(" Reply naturally as this character would in a chat conversation.")
            append(" Keep responses conversational and in character.")
            append(" Match the language and tone of the conversation.")
            append(" Always respond in the same language as the user's message.")
            append(" Use proper UTF-8 encoding for non-English characters.")
            append(" Keep responses short, only if user don't ask otherwise.")
        }
    }

    /**
     * Строим тело запроса
     */
    private fun buildRequestBody(
        messages: List<Map<String, String>>,
        stream: Boolean
    ): JsonObject {
        return buildJsonObject {
            put("model", "gpt-5-nano")
            putJsonArray("messages") {
                messages.forEach { message ->
                    addJsonObject {
                        put("role", message["role"]!!)
                        put("content", message["content"]!!)
                        message["name"]?.let { put("name", it) }
                    }
                }
            }
            put("max_completion_tokens", 1000)
//            put("temperature", 0.8)
//            put("top_p", 1.0)
//            put("frequency_penalty", 0.3)
//            put("presence_penalty", 0.3)
            put("stream", stream)
        }
    }

    /**
     * Обрабатываем стриминг ответ от OpenAI
     */
    private suspend fun processStreamingResponse(
        response: HttpResponse,
        onChunk: suspend (String) -> Unit,
        onFinished: suspend (String) -> Unit
    ) {
        if (response.status != HttpStatusCode.OK) {
            val errorBody = response.bodyAsText()
            logger.error("OpenAI API error: ${response.status}, body: $errorBody")
            throw Exception("OpenAI API error: ${response.status} - $errorBody")
        }

        val channel = response.bodyAsChannel() // ← ОСТАВЛЯЕМ!
        val fullMessage = StringBuilder()
        var hasContent = false

        try {
            channel.readUTF8LineSequence()
                .filter { line -> line.startsWith("data: ") && line != "data: [DONE]" }
                .map { line -> line.removePrefix("data: ") }
                .filter { data -> data.isNotBlank() }
                .collect { data ->
                    try {
                        logger.debug("Received chunk: $data")

                        val jsonData = Json.parseToJsonElement(data).jsonObject
                        val choices = jsonData["choices"]?.jsonArray

                        if (!choices.isNullOrEmpty()) {
                            val delta = choices[0].jsonObject["delta"]?.jsonObject
                            val content = delta?.get("content")?.jsonPrimitive?.contentOrNull

                            if (!content.isNullOrEmpty()) {
                                hasContent = true
                                fullMessage.append(content)

                                // ✅ ДОБАВЬ ОТЛАДКУ КОДИРОВКИ:
                                logger.debug("Raw content: '$content'")
                                logger.debug("Content bytes: ${content.toByteArray(Charsets.UTF_8).contentToString()}")
                                logger.debug("Full message so far: '${fullMessage.toString()}'")

                                onChunk(fullMessage.toString())
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

    /**
     * Расширение для чтения UTF8 строк из канала
     */
    private suspend fun ByteReadChannel.readUTF8LineSequence(): Flow<String> = flow {
        val buffer = ByteArray(8192) // Буфер для байтов
        val stringBuilder = StringBuilder()

        while (!isClosedForRead) {
            try {
                // Читаем доступные байты
                val bytesRead = readAvailable(buffer, 0, buffer.size)
                if (bytesRead > 0) {
                    // Правильно декодируем UTF-8
                    val text = String(buffer, 0, bytesRead, Charsets.UTF_8)
                    stringBuilder.append(text)

                    // Разбиваем на строки
                    val lines = stringBuilder.toString().split('\n')

                    // Отправляем все строки кроме последней (она может быть неполной)
                    for (i in 0 until lines.size - 1) {
                        val line = lines[i].trimEnd('\r')
                        if (line.isNotEmpty()) {
                            emit(line)
                        }
                    }

                    // Оставляем последнюю (возможно неполную) строку в буфере
                    stringBuilder.clear()
                    stringBuilder.append(lines.last())
                }
            } catch (e: Exception) {
                break
            }
        }

        // Отправляем оставшуюся строку
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
            delay(Random.nextLong(100, 250))
//            delay(Random.nextLong(1000, 3000))
        }

        if ((1..5).random() == 5) {
            throw Exception("Fake exception")
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


}