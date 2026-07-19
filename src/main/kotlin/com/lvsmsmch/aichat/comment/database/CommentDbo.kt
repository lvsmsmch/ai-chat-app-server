package com.lvsmsmch.aichat.comment.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

/**
 * Комментарий к персонажу (модель как на YouTube):
 * корневые комменты + плоский список ответов под каждым корнем.
 */
@Serializable
data class CommentDbo(
    @BsonId val id: String,
    val createdAt: String = UtcTimestamp.now().toString(),
    val characterId: String,
    val authorId: String,
    /** null — корневой; иначе id КОРНЕВОГО коммента (ответы всегда плоские). */
    val parentId: String? = null,
    /** Кому отвечают внутри ветки — для «@username» в UI (может быть null). */
    val replyToUserId: String? = null,
    val text: String,
    val likesCount: Int = 0,
    /** Только у корневых: сколько ответов в ветке. */
    val repliesCount: Int = 0,
)
