package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat.auth.network.UserLoginInfoDto
import com.lvsmsmch.aichat.auth.network.UserPrivateInfoDto
import com.lvsmsmch.aichat.cache.CachedCharactersResult
import com.lvsmsmch.aichat.character.database.*
import com.lvsmsmch.aichat.character.network.*
import com.lvsmsmch.aichat.chat.database.*
import com.lvsmsmch.aichat.chat.network.*
import com.lvsmsmch.aichat.notification.database.RecommendationsDbo
import com.lvsmsmch.aichat.notification.network.RecommendationsDto
import com.lvsmsmch.aichat.notification.network.NotificationDto
import com.lvsmsmch.aichat.notification.network.NotificationType
import com.lvsmsmch.aichat.review.database.ReviewDbo
import com.lvsmsmch.aichat.review.database.ReviewLikeRepository
import com.lvsmsmch.aichat.review.database.ReviewRepository
import com.lvsmsmch.aichat.review.network.ReviewDto
import com.lvsmsmch.aichat.user.database.AccountType
import com.lvsmsmch.aichat.user.database.FollowRepository
import com.lvsmsmch.aichat.user.database.UserDbo
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.user.network.UserDetailsDto
import com.lvsmsmch.aichat.user.network.UserDto
import com.lvsmsmch.aichat.user.network.UserFullInfoDto

class Mapper(
    val userRepository: UserRepository,
    val characterRepository: CharacterRepository,
    val chatRepository: ChatRepository,
    val messageRepository: MessageRepository,
    val reviewRepository: ReviewRepository,
    val reviewLikeRepository: ReviewLikeRepository,
    val followRepository: FollowRepository,
)

suspend fun UserDbo.toUserDto(mapper: Mapper): UserDto {
    return UserDto(
        id = id,
        username = username,
        name = name,
        profilePicUrlThumbnail = profilePictureUrlThumbnail ?: profilePictureUrl
    )
}

suspend fun UserDbo.toUserDetailsDto(
    mapper: Mapper,
    demanderId: String
): UserDetailsDto {
    return UserDetailsDto(
        id = id,
        lastActiveAt = lastActiveAt.toString(),
        createdAt = createdAt.toString(),
        bio = bio,
        publicCharactersCount = publicCharacterCount,
        privateCharactersCount = if (demanderId == id) privateCharacterCount else 0,
        followersCount = followerCount,
        followingCount = followingCount,
        totalChatsCount = if (demanderId == id) totalChatsCount else 0,
        totalMessagesCount = if (demanderId == id) totalMessagesCount else 0,
        isFollowing = mapper.followRepository.doesConnectionExist(demanderId, id),
        profilePicUrl = profilePictureUrl
    )
}

suspend fun UserDbo.toUserFullInfoDto(
    mapper: Mapper,
    demanderId: String
): UserFullInfoDto {
    return UserFullInfoDto(
        user = toUserDto(mapper),
        userDetails = toUserDetailsDto(mapper, demanderId),
    )
}

suspend fun UserDbo.toUserPrivateInfoDto(
    mapper: Mapper
): UserPrivateInfoDto {
    return UserPrivateInfoDto(
        id = id,
        isGuest = accountType == AccountType.GUEST
    )
}

suspend fun UserDbo.toUserLoginInfoDto(
    mapper: Mapper,
    sessionToken: String
): UserLoginInfoDto {
    return UserLoginInfoDto(
        id = id,
        sessionToken = sessionToken
    )
}

suspend fun CharacterDbo.toCharacterDto(mapper: Mapper): CharacterDto {
    return CharacterDto(
        id = id,
        createdAt = createdAt.toString(),
        author = mapper.userRepository.getUserById(authorId)!!.toUserDto(mapper),
        visibility = visibility,
        name = name,
        description = description,
        category = category,
        tags = tags,
        totalChats = totalChats,
        totalMessages = totalMessages,
        totalReviews = totalReviews,
        averageRating = averageRating,
        picUrl = picUrl,
        picUrlThumbnail = picUrlThumbnail ?: picUrl,
    )
}

suspend fun CachedCharactersResult.toDto(mapper: Mapper): CachedCharactersResultDto {
    return CachedCharactersResultDto(
        refreshed = refreshed,
        items = items.map { it.toCharacterDto(mapper) },
        nextCursor = nextCursor?.toString(),
    )
}

suspend fun CharacterDbo.toCharacterDetailsDto(
    mapper: Mapper,
    demanderId: String
): CharacterDetailsDto {
    val isReviewed = mapper.reviewRepository.getReview(demanderId, id) != null
    return CharacterDetailsDto(
        id = id,
        isReviewed = isReviewed
    )
}

suspend fun CharacterDbo.toCharacterPrivateInfoDto(mapper: Mapper): CharacterPrivateInfoDto {
    return CharacterPrivateInfoDto(
        id = id,
        prompt = prompt,
        initialMessage = initialMessage
    )
}

suspend fun CharacterDbo.toCharacterFullInfoDto(mapper: Mapper, demanderId: String): CharacterFullInfoDto {
    return CharacterFullInfoDto(
        character = toCharacterDto(mapper),
        characterDetails = toCharacterDetailsDto(mapper, demanderId),
        characterPrivateInfo = toCharacterPrivateInfoDto(mapper)
    )
}


suspend fun ReviewDbo.toReviewDto(mapper: Mapper, currentUserId: String? = null): ReviewDto {
    val author = mapper.userRepository.getUserById(this.authorId)?.toUserDto(mapper)
        ?: throw UserNotFoundException(this.authorId)

    val isLikedByCurrentUser = if (currentUserId != null) {
        mapper.reviewLikeRepository.isReviewLikedByUser(currentUserId, this.id)
    } else false

    return ReviewDto(
        id = this.id,
        createdAt = this.createdAt.toString(),
        updatedAt = this.editedAt?.toString(),
        characterId = this.characterId,
        author = author,
        rating = this.rating,
        text = this.text,
        likesCount = this.likesCount,
        isLikedByCurrentUser = isLikedByCurrentUser
    )
}

suspend fun ChatDbo.toChatDto(
    mapper: Mapper
): ChatDto {
    val characters = characterIds.mapNotNull { charId ->
        mapper.characterRepository.getCharacter(charId)?.toCharacterDto(mapper)
    }

    return ChatDto(
        id = clientId,
        chatType = type.code,
        customName = customName,
        characters = characters,
        createdAt = createdAt
    )
}

suspend fun MessageDbo.toMessageDto(mapper: Mapper): MessageDto {
    return MessageDto(
        id = clientId,
        chatId = chatClientId,
        text = text,
        senderId = senderId,
        isFromUser = isSentByUser,
        createdAt = createdAt.toString(),
        isRead = isRead,
        isCompleted = status == MessageStatus.COMPLETED.value,
        isFailedCompleting = status == MessageStatus.FAILED.value,
        nsfw = nsfw
    )
}

suspend fun RecommendationsDbo.toNotificationDto(mapper: Mapper): NotificationDto {
    return NotificationDto(
        type = NotificationType.Recommendations.code,
        notification = RecommendationsDto(
            characters = characterIds.mapNotNull {
                mapper.characterRepository.getCharacter(it)?.toCharacterDto(mapper)
            }
        )
    )
}