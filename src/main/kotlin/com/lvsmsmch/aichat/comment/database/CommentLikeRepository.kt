package com.lvsmsmch.aichat.comment.database

import com.mongodb.reactivestreams.client.ClientSession
import org.litote.kmongo.ascending
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.eq
import org.litote.kmongo.`in`

class CommentLikeRepository(
    private val collection: CoroutineCollection<CommentLikeDbo>
) {

    suspend fun ensureIndexes() {
        collection.ensureIndex(ascending(CommentLikeDbo::userId))
        collection.ensureIndex(ascending(CommentLikeDbo::commentId))
    }

    private fun likeId(userId: String, commentId: String) = "${userId}_${commentId}"

    suspend fun likeComment(session: ClientSession, userId: String, commentId: String) {
        if (collection.findOneById(likeId(userId, commentId)) == null) {
            collection.insertOne(
                session,
                CommentLikeDbo(
                    id = likeId(userId, commentId),
                    userId = userId,
                    commentId = commentId
                )
            )
        }
    }

    suspend fun unlikeComment(session: ClientSession, userId: String, commentId: String) {
        collection.deleteOneById(session, likeId(userId, commentId))
    }

    suspend fun isCommentLikedByUser(userId: String, commentId: String): Boolean {
        return collection.findOneById(likeId(userId, commentId)) != null
    }

    /** Батч-проверка лайков текущего юзера для страницы комментов. */
    suspend fun getLikedCommentIds(userId: String, commentIds: List<String>): Set<String> {
        if (commentIds.isEmpty()) return emptySet()
        return collection.find(
            CommentLikeDbo::id `in` commentIds.map { likeId(userId, it) }
        ).toList().map { it.commentId }.toSet()
    }

    suspend fun removeAllLikesForComments(session: ClientSession, commentIds: List<String>) {
        if (commentIds.isEmpty()) return
        collection.deleteMany(session, CommentLikeDbo::commentId `in` commentIds)
    }
}
