package com.lvsmsmch.aichat.character

import com.lvsmsmch.aichat._common.database.DeletedIdsStatsRepository
import com.lvsmsmch.aichat._common.database.EntityType
import com.lvsmsmch.aichat.character.database.*
import com.lvsmsmch.aichat.chat.database.ChatRepository
import com.lvsmsmch.aichat.comment.database.CommentLikeRepository
import com.lvsmsmch.aichat.comment.database.CommentRepository
import com.lvsmsmch.aichat.review.database.ReviewLikeRepository
import com.lvsmsmch.aichat.review.database.ReviewRepository
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.utils.TransactionHelper

/**
 * Персонажи: создание, правка и удаление вместе со всем, что за ними тянется.
 *
 * Каждая операция — одна транзакция: счётчики автора, чаты, отзывы, комменты и
 * лайки должны меняться вместе с самим персонажем, иначе профиль показывает
 * несуществующие цифры, а в лентах остаются ссылки в никуда.
 */
class CharacterService(
    private val characterRepository: CharacterRepository,
    private val userRepository: UserRepository,
    private val chatRepository: ChatRepository,
    private val reviewRepository: ReviewRepository,
    private val reviewLikeRepository: ReviewLikeRepository,
    private val commentRepository: CommentRepository,
    private val commentLikeRepository: CommentLikeRepository,
    private val characterLikeRepository: CharacterLikeRepository,
    private val deletedIdsStatsRepository: DeletedIdsStatsRepository,
    private val transactionHelper: TransactionHelper,
) {

    suspend fun addCharacter(characterDbo: CharacterDbo) {
        transactionHelper.withTransaction { session ->
            characterRepository.addCharacter(session, characterDbo)
            if (characterDbo.visibility == CharacterVisibility.PUBLIC.code) {
                userRepository.incrementPublicCharacterCount(session, characterDbo.authorId, 1)
            } else {
                userRepository.incrementPrivateCharacterCount(session, characterDbo.authorId, 1)
            }
        }
    }

    suspend fun updateCharacter(
        characterId: String,
        userId: String,
        name: String?,
        description: String?,
        prompt: String?,
        initialMessage: String?,
        visibility: Int?,
        pictureUrl: String?,
        pictureUrlThumbnail: String?,
        removePicture: Boolean?,
        category: String?,
        tags: String?,
        /** Обои чата по умолчанию: код встроенной картинки. */
        cover: String? = null,
        oldName: String,
        oldVisibility: Int
    ): CharacterDbo {
        return transactionHelper.withTransaction { session ->
            characterRepository.updateCharacter(
                session = session,
                characterId = characterId,
                name = name,
                description = description,
                prompt = prompt,
                initialMessage = initialMessage,
                visibility = visibility,
                pictureUrl = pictureUrl,
                pictureUrlThumbnail = pictureUrlThumbnail,
                removePicture = removePicture,
                category = category?.let { CharacterCategory.getByCode(it) },
                tags = tags?.let { CharacterTag.fromString(tags) },
                cover = cover,
            )


            if (visibility != null && oldVisibility != visibility) {
                chatRepository.deleteChatsForWhoIsNotAuthor(
                    session = session,
                    characterId = characterId,
                    authorId = userId
                )

                val changedToPrivate = oldVisibility == CharacterVisibility.PUBLIC.code &&
                        visibility == CharacterVisibility.PRIVATE.code

                val publicInc = if (changedToPrivate) -1 else 1
                val privateInc = if (changedToPrivate) 1 else -1

                userRepository.incrementPublicCharacterCount(session, userId, publicInc)
                userRepository.incrementPrivateCharacterCount(session, userId, privateInc)
            }

            return@withTransaction characterRepository.getCharacter(session, characterId)!!
        }
    }


    suspend fun deleteCharacter(characterId: String) {
        transactionHelper.withTransaction { session ->
            val character = characterRepository.getCharacter(session, characterId)!!
            val userId = character.authorId
            characterRepository.deleteCharacter(session, characterId)
            deletedIdsStatsRepository.entityWasDeleted(session, EntityType.CHARACTER, characterId)
            // Лайки удалённого персонажа не нужны никому
            characterLikeRepository.removeAllForCharacters(session, listOf(characterId))
            if (character.visibility == CharacterVisibility.PUBLIC.code) {
                userRepository.incrementPublicCharacterCount(session, userId, -1)
            } else {
                userRepository.incrementPrivateCharacterCount(session, userId, -1)
            }
            chatRepository.deleteAllChatsByCharacterId(session, characterId)

            val reviewIds = reviewRepository.getReviewIdsByCharacterId(session, characterId)
            reviewRepository.deleteReviewsByIds(session, reviewIds)
            deletedIdsStatsRepository.entitiesWereDeleted(session, EntityType.REVIEW, reviewIds)
            reviewLikeRepository.removeAllLikesForReviews(session, reviewIds)

            val commentIds = commentRepository.getCommentIdsByCharacterIds(session, listOf(characterId))
            commentRepository.deleteCommentsByIds(session, commentIds)
            deletedIdsStatsRepository.entitiesWereDeleted(session, EntityType.COMMENT, commentIds)
            commentLikeRepository.removeAllLikesForComments(session, commentIds)
        }
    }
    /**
     * Лайк персонажа. Счётчик обновляется только если лайк реально добавился —
     * повторный тап не должен накручивать число.
     */
    suspend fun like(characterId: String, userId: String): Boolean {
        val added = characterLikeRepository.like(userId, characterId)
        if (added) characterRepository.incrementLikesCount(characterId, 1)
        return added
    }

    suspend fun unlike(characterId: String, userId: String): Boolean {
        val removed = characterLikeRepository.unlike(userId, characterId)
        if (removed) characterRepository.incrementLikesCount(characterId, -1)
        return removed
    }
}
