package com.lvsmsmch.aichat.chat.network

import com.lvsmsmch.aichat.character.database.CharacterDbo
import com.lvsmsmch.aichat.chat.database.MessageDbo
import kotlinx.coroutines.delay
import kotlin.random.Random

object TestAiMessageGeneratorUtil {

    // Массив возможных ответов для разных типов сообщений
    private val possibleResponses = listOf(
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
        "I think you're being too hard on yourself. You're doing great!",
        "That's a really good observation. I hadn't considered that angle.",
        "You always ask the most thoughtful questions.",
        "I can relate to that feeling. It's more common than you might think.",
        "That sounds like it was quite an adventure!",
        "I'm impressed by how you handled that situation.",
        "You're such a good listener. Thank you for being here.",
        "That's a fascinating topic. I could talk about it for hours!",
        "I think you made the right choice there. Trust your instincts.",
        "You have a way of explaining things that makes them so clear.",
        "I'm curious to hear more about your thoughts on this.",
        "That must have been quite an experience for you!"
    )

    // Ответы специально для персонажей из аниме
    private val animeResponses = listOf(
        "Senpai, you're so thoughtful! (◕‿◕)",
        "Ehh?! Really?! That's amazing! ✨",
        "Hmm, that's quite interesting... *adjusts glasses*",
        "Uwaa! I never thought about it like that before!",
        "Ara ara~ you're so clever! 💕",
        "That sounds like the beginning of an epic adventure!",
        "Kyaa! You surprised me with that response!",
        "Hehe, you're really something special, you know that?",
        "*blushes* T-that's really sweet of you to say...",
        "Sugoi! You're incredible at explaining things!",
        "Mou~ you're making me think too hard! (>_<)",
        "That reminds me of something from my favorite manga!",
        "Wah! You're so cool! I wish I could be like you!",
        "*nods enthusiastically* Yes, yes! I totally agree!",
        "Ehehe~ you always know just what to say! ♪"
    )

    /**
     * Тестовая версия генерации AI сообщения с имитацией стриминга
     */
    suspend fun generateAiMessageWithStreaming(
        characterDbo: CharacterDbo,
        messagesHistory: List<MessageDbo>,
        onChunk: suspend (String) -> Unit,
        onFinished: suspend (String) -> Unit,
        onError: suspend (String) -> Unit
    ) {
        try {
            // Выбираем случайный ответ
            val response = selectRandomResponse(characterDbo)
            
            // Имитируем стриминг по символам
            simulateStreamingResponse(response, onChunk, onFinished)
            
        } catch (e: Exception) {
            onError("Test error: ${e.message}")
        }
    }

    /**
     * Выбираем случайный ответ в зависимости от персонажа
     */
    private fun selectRandomResponse(characterDbo: CharacterDbo): String {
        // Если персонаж из аниме/манги - используем аниме ответы
        val isAnimeCharacter = characterDbo.category == "anime_manga" || 
                              characterDbo.tags.any { it in listOf("tsundere", "yandere", "cute") }
        
        return if (isAnimeCharacter && Random.nextBoolean()) {
            animeResponses.random()
        } else {
            possibleResponses.random()
        }
    }

    /**
     * Имитируем стриминг ответа
     */
    private suspend fun simulateStreamingResponse(
        fullResponse: String,
        onChunk: suspend (String) -> Unit,
        onFinished: suspend (String) -> Unit
    ) {
        val currentMessage = StringBuilder()
        
        // Стримим по словам для более реалистичного эффекта
        val words = fullResponse.split(" ")
        
        for (i in words.indices) {
            // Добавляем слово
            if (currentMessage.isNotEmpty()) {
                currentMessage.append(" ")
            }
            currentMessage.append(words[i])
            
            // Отправляем текущий чанк
            onChunk(currentMessage.toString())
            
            // Случайная задержка между словами (50-200мс)
            delay(Random.nextLong(50, 200))
        }
        
        // Финальная задержка перед завершением
        delay(300)
        
        // Завершаем стриминг
        onFinished(currentMessage.toString())
    }

    /**
     * Версия без стриминга для быстрого тестирования
     */
    suspend fun generateAiMessageInstant(
        characterDbo: CharacterDbo,
        messagesHistory: List<MessageDbo>
    ): String {
        // Небольшая задержка для реализма
        delay(Random.nextLong(500, 1500))
        
        return selectRandomResponse(characterDbo)
    }

    /**
     * Альтернативная версия с более контекстными ответами
     */
    suspend fun generateContextualResponse(
        characterDbo: CharacterDbo,
        messagesHistory: List<MessageDbo>,
        onChunk: suspend (String) -> Unit,
        onFinished: suspend (String) -> Unit,
        onError: suspend (String) -> Unit
    ) {
        try {
            val lastUserMessage = messagesHistory.lastOrNull { it.isSentByUser }?.text ?: ""
            
            val response = when {
                lastUserMessage.contains("?") -> 
                    "That's a great question! ${possibleResponses.random()}"
                
                lastUserMessage.lowercase().contains("hello") || lastUserMessage.lowercase().contains("hi") ->
                    "Hello there! It's wonderful to see you again! How are you doing today?"
                
                lastUserMessage.lowercase().contains("thank") ->
                    "You're very welcome! I'm always happy to help you out."
                
                lastUserMessage.lowercase().contains("sorry") ->
                    "No need to apologize! Everything is perfectly fine."
                
                lastUserMessage.length > 100 ->
                    "Wow, that's quite a lot to think about! ${possibleResponses.random()}"
                
                else -> selectRandomResponse(characterDbo)
            }
            
            simulateStreamingResponse(response, onChunk, onFinished)
            
        } catch (e: Exception) {
            onError("Contextual response error: ${e.message}")
        }
    }
}