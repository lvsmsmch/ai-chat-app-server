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
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import kotlin.random.Random

object AiMessageGeneratorUtil {

    private val openAiApiUrl = System.getenv("OPEN_AI_API_URL") ?: throw Exception("Missing OPEN_AI_API_URL key")
    private val openAiApiKey = System.getenv("OPEN_AI_API_KEY") ?: throw Exception("Missing OPEN_AI_API_KEY key")
    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(defaultJson)
        }
    }

    /**
     * Генерация с настоящим стримингом от OpenAI
     */



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
            val messages = buildMessageHistory(chatDbo, characterDbo, participants, messagesHistory)
            val requestBody = buildRequestBody(messages, stream = true)

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
                return
            }

            val response = httpClient.post(openAiApiUrl) {
                header(HttpHeaders.Authorization, "Bearer $openAiApiKey")
                header(HttpHeaders.Accept, "text/event-stream")
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }

            processStreamingResponse(response, onChunk, onFinished)
        } catch (e: Exception) {
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
        }
    }

    /**
     * Строим тело запроса
     */
    private fun buildRequestBody(
        messages: List<Map<String, String>>,
        stream: Boolean
    ): Map<String, Any> {
        return mapOf(
            "model" to "gpt-3.5-turbo",
            "messages" to messages,
            "temperature" to 0.8,
            "max_tokens" to 1000,
            "top_p" to 1.0,
            "frequency_penalty" to 0.3,
            "presence_penalty" to 0.3,
            "stream" to stream
        )
    }

    /**
     * Обрабатываем стриминг ответ от OpenAI
     */
    private suspend fun processStreamingResponse(
        response: HttpResponse,
        onChunk: suspend (String) -> Unit,
        onFinished: suspend (String) -> Unit
    ) {
        val channel = response.bodyAsChannel()
        val fullMessage = StringBuilder()

        try {
            // Читаем SSE поток
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
                                fullMessage.append(content)
                                onChunk(fullMessage.toString())
                            }

                            // Проверяем завершение
                            val finishReason = choices[0].jsonObject["finish_reason"]?.jsonPrimitive?.contentOrNull
                            if (finishReason == "stop") {
                                onFinished(fullMessage.toString())
                                return@collect
                            }
                        }
                    } catch (e: Exception) {
                        // Игнорируем ошибки парсинга отдельных чанков
                        println("Error parsing chunk: ${e.message}")
                    }
                }
        } catch (e: Exception) {
            throw Exception("Streaming error: ${e.message}")
        }

        val finalMessage = fullMessage.toString()
        if (finalMessage.isBlank()) {
            throw Exception("No content received from stream")
        }

        onFinished(finalMessage)
    }

    /**
     * Расширение для чтения UTF8 строк из канала
     */
    private suspend fun ByteReadChannel.readUTF8LineSequence(): Flow<String> = flow {
        val buffer = StringBuilder()

        while (!isClosedForRead) {
            val byte = readByte().toInt().toChar()

            if (byte == '\n') {
                if (buffer.isNotEmpty()) {
                    emit(buffer.toString().trimEnd('\r'))
                    buffer.clear()
                }
            } else {
                buffer.append(byte)
            }
        }

        // Последняя строка без \n
        if (buffer.isNotEmpty()) {
            emit(buffer.toString().trimEnd('\r'))
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
            delay(Random.nextLong(30, 120))
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