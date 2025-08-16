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

@Serializable
data class SyncNotificationsRequest(
    @SerialName("timestamp") val timestamp: String
)


@Serializable
data class SyncNotificationsResponse(
    @SerialName("items") val items: List<NotificationDto>
)