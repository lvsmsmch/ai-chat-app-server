package com.lvsmsmch.aichat.chat.network

import com.lvsmsmch.aichat.character.database.CharacterDbo
import com.lvsmsmch.aichat.chat.database.MessageDbo
import com.lvsmsmch.aichat.utils.defaultJson
import com.lvsmsmch.aichat.utils.loadConfig
import io.ktor.client.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*

object AiMessageGeneratorUtil {

    private val openAiApiUrl = loadConfig().getProperty("OPEN_AI_API_URL")
    private val openAiApiKey = loadConfig().getProperty("OPEN_AI_API_KEY")
    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(defaultJson)
        }
    }

    /**
     * Генерация с настоящим стримингом от OpenAI
     */
    suspend fun generateAiMessageWithStreaming(
        characterDbo: CharacterDbo,
        messagesHistory: List<MessageDbo>,
        onChunk: suspend (String) -> Unit,
        onFinished: suspend (String) -> Unit,
        onError: suspend (String) -> Unit
    ) {
        try {
            val messages = buildMessageHistory(characterDbo, messagesHistory)
            val requestBody = buildRequestBody(messages, stream = true)

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
        characterDbo: CharacterDbo,
        messagesHistory: List<MessageDbo>
    ): List<Map<String, String>> {
        val systemMessage = mapOf(
            "role" to "system",
            "content" to buildSystemPrompt(characterDbo)
        )

        // История сообщений
        val historyMessages = messagesHistory
            .filter { it.text.isNotBlank() } // Фильтруем пустые сообщения
            .map { message ->
                mapOf(
                    "role" to if (message.isSentByUser) "user" else "assistant",
                    "content" to message.text
                )
            }

        return listOf(systemMessage) + historyMessages
    }

    /**
     * Строим системный промпт
     */
    private fun buildSystemPrompt(characterDbo: CharacterDbo): String {
        return buildString {
            append("You are ${characterDbo.name}.")

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

    /**
     * Закрытие HTTP клиента (вызывать при остановке приложения)
     */
    fun close() {
        httpClient.close()
    }
}