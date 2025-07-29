package com.lvsmsmch.aichat._common

import com.lvsmsmch.aichat._common.database.DeletedIdsStatsRepository
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.chat.database.ChatRepository
import com.lvsmsmch.aichat.chat.database.MessageRepository
import com.lvsmsmch.aichat.review.database.ReviewRepository
import com.lvsmsmch.aichat.user.database.UserRepository

class IdGenerator(
    private val deletedIdsStatsRepository: DeletedIdsStatsRepository,
    private val userRepository: UserRepository,
    private val characterRepository: CharacterRepository,
    private val chatRepository: ChatRepository,
    private val messageRepository: MessageRepository,
    private val reviewRepository: ReviewRepository
) {

    private val secureRandom = java.security.SecureRandom()
    private val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private val maxAttemptsPerLength = 5

    suspend fun generateId(entityType: EntityType): String {
        var currentLength = entityType.recommendedIdLength
        var totalAttempts = 0
        val maxTotalAttempts = 50

        while (totalAttempts < maxTotalAttempts) {
            var attemptsAtCurrentLength = 0

            while (attemptsAtCurrentLength < maxAttemptsPerLength) {
                val newId = generateRandomId(currentLength)

                if (isIdUnique(entityType, newId)) {
                    return newId
                }

                attemptsAtCurrentLength++
                totalAttempts++
            }

            currentLength++
            println("⚠️ Increased ID length to $currentLength for ${entityType.code}")
        }

        throw RuntimeException("Failed to generate unique ID for ${entityType.code} after $maxTotalAttempts attempts")
    }

    private fun generateRandomId(length: Int): String {
        return (1..length)
            .map { alphabet[secureRandom.nextInt(alphabet.length)] }
            .joinToString("")
    }

    private suspend fun isIdUnique(entityType: EntityType, id: String): Boolean {
        if (deletedIdsStatsRepository.isIdDeleted(entityType, id)) {
            return false
        }

        return when (entityType) {
            EntityType.USER -> userRepository.getUserById(id) == null
            EntityType.CHARACTER -> characterRepository.getCharacter(id) == null
            EntityType.CHAT -> chatRepository.getChatById(id) == null
            EntityType.MESSAGE -> messageRepository.getMessageById(id) == null
            EntityType.REVIEW -> reviewRepository.getReviewById(id) == null
        }
    }

}