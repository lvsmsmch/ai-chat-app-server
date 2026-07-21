package com.lvsmsmch.aichat.notification.network

import com.lvsmsmch.aichat.character.network.CharacterDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class NotificationType(val code: String) {
    Recommendations("recommendations"),
    ProfileStats("profile_stats"),
    CharacterStats("character_stats"),
    ReviewStats("review_stats"),
    CharacterBlocked("character_blocked"),
    ReviewBlocked("review_blocked");

    companion object {
        fun fromCode(code: String) = entries.firstOrNull { it.code == code }
    }
}

@Serializable
data class NotificationDto(
    val type: String,
    val notification: BaseNotificationDto
)

interface BaseNotificationDto

@Serializable
data class RecommendationsDto(
    val characters: List<CharacterDto>,
) : BaseNotificationDto

@Serializable
data class ProfileStatsDto(
    val newFollowersCount: Int
)

@Serializable
data class CharacterStatsDto(
    val newChatsCount: Int,
    val newMessagesCount: Int,
    val newReviewsCount: Int
)

@Serializable
data class ReviewStatsDto(
    val newLikesCount: Int
)

@Serializable
data class CharacterBlockedDto(
    val characterId: String,
    val characterName: String,
    val message: String
)

@Serializable
data class ReviewBlockedDto(
    val reviewId: String,
    val reviewText: String,
    val message: String
)

// ---- Событийная лента уведомлений ----

@Serializable
data class NotificationActorDto(
    @SerialName("id") val id: String,
    @SerialName("username") val username: String,
    @SerialName("picUrl") val picUrl: String? = null,
    @SerialName("color") val color: String = "",
)

@Serializable
data class NotificationCharacterDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("picUrl") val picUrl: String? = null,
)

@Serializable
data class NotificationItemDto(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String,
    @SerialName("at") val at: String,
    @SerialName("isRead") val isRead: Boolean,
    @SerialName("count") val count: Int = 1,
    @SerialName("milestone") val milestone: Int = 0,
    @SerialName("actor") val actor: NotificationActorDto? = null,
    @SerialName("character") val character: NotificationCharacterDto? = null,
    /** Огрызок текста коммента (новый коммент / мой лайкнутый). */
    @SerialName("commentText") val commentText: String? = null,
    /** id корневого коммента ветки — для навигации к комменту. */
    @SerialName("commentId") val commentId: String? = null,
)

@Serializable
data class NotificationFeedResponse(
    @SerialName("items") val items: List<NotificationItemDto>,
    @SerialName("nextCursor") val nextCursor: String? = null,
    @SerialName("unreadCount") val unreadCount: Long = 0,
)

@Serializable
data class MarkNotificationsReadRequest(
    @SerialName("ids") val ids: List<String>,
)

@Serializable
data class UnreadCountResponse(
    @SerialName("count") val count: Long,
)

@Serializable
data class SyncNotificationsRequest(
    @SerialName("timestamp") val timestamp: String
)


@Serializable
data class SyncNotificationsResponse(
    @SerialName("items") val items: List<NotificationDto>
)