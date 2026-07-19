package com.lvsmsmch.aichat.comment.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.mongodb.reactivestreams.client.ClientSession
import org.litote.kmongo.EMPTY_BSON
import org.litote.kmongo.and
import org.litote.kmongo.ascending
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.descending
import org.litote.kmongo.eq
import org.litote.kmongo.gt
import org.litote.kmongo.inc
import org.litote.kmongo.lt
import org.litote.kmongo.`in`

class CommentRepository(
    private val collection: CoroutineCollection<CommentDbo>
) {

    suspend fun ensureIndexes() {
        collection.ensureIndex(ascending(CommentDbo::characterId, CommentDbo::parentId, CommentDbo::createdAt))
        collection.ensureIndex(ascending(CommentDbo::parentId, CommentDbo::createdAt))
        collection.ensureIndex(ascending(CommentDbo::authorId))
    }

    suspend fun addComment(session: ClientSession, comment: CommentDbo) {
        collection.insertOne(session, comment)
    }

    suspend fun getCommentById(id: String): CommentDbo? = collection.findOneById(id)

    /** Корневые комменты персонажа, новые сверху; курсор — createdAt последнего. */
    suspend fun getRootComments(
        characterId: String,
        beforeTime: UtcTimestamp?,
        size: Int
    ): List<CommentDbo> {
        return collection.find(
            and(
                CommentDbo::characterId eq characterId,
                CommentDbo::parentId eq null,
                if (beforeTime != null) CommentDbo::createdAt lt beforeTime.toString() else EMPTY_BSON
            )
        ).sort(descending(CommentDbo::createdAt))
            .limit(size)
            .toList()
    }

    /** Ответы ветки, старые сверху (как на YouTube); курсор — createdAt последнего. */
    suspend fun getReplies(
        parentId: String,
        afterTime: UtcTimestamp?,
        size: Int
    ): List<CommentDbo> {
        return collection.find(
            and(
                CommentDbo::parentId eq parentId,
                if (afterTime != null) CommentDbo::createdAt gt afterTime.toString() else EMPTY_BSON
            )
        ).sort(ascending(CommentDbo::createdAt))
            .limit(size)
            .toList()
    }

    suspend fun getReplyIds(session: ClientSession, parentId: String): List<String> {
        return collection.find(session, CommentDbo::parentId eq parentId).toList().map { it.id }
    }

    suspend fun updateText(commentId: String, text: String) {
        collection.updateOneById(commentId, org.litote.kmongo.setValue(CommentDbo::text, text))
    }

    suspend fun incrementLikesCount(session: ClientSession, commentId: String, increment: Int) {
        collection.updateOneById(session, commentId, inc(CommentDbo::likesCount, increment))
    }

    suspend fun incrementRepliesCount(session: ClientSession, commentId: String, increment: Int) {
        collection.updateOneById(session, commentId, inc(CommentDbo::repliesCount, increment))
    }

    suspend fun deleteCommentsByIds(session: ClientSession, ids: List<String>) {
        if (ids.isEmpty()) return
        collection.deleteMany(session, CommentDbo::id `in` ids)
    }

    suspend fun getCommentIdsByCharacterIds(session: ClientSession, characterIds: List<String>): List<String> {
        if (characterIds.isEmpty()) return emptyList()
        return collection.find(session, CommentDbo::characterId `in` characterIds).toList().map { it.id }
    }

    suspend fun getCommentsByUserId(session: ClientSession, userId: String): List<CommentDbo> {
        return collection.find(session, CommentDbo::authorId eq userId).toList()
    }

    suspend fun getRepliesByParentIds(session: ClientSession, parentIds: List<String>): List<CommentDbo> {
        if (parentIds.isEmpty()) return emptyList()
        return collection.find(session, CommentDbo::parentId `in` parentIds).toList()
    }
}
