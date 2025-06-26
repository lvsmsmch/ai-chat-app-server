package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat.auth.network.UserLoginInfoDto
import com.lvsmsmch.aichat.auth.network.UserPrivateInfoDto
import com.lvsmsmch.aichat.cache.CachedCharactersResult
import com.lvsmsmch.aichat.character.database.*
import com.lvsmsmch.aichat.character.network.*
import com.lvsmsmch.aichat.chat.database.*
import com.lvsmsmch.aichat.chat.network.*
import com.lvsmsmch.aichat.review.database.ReviewDbo
import com.lvsmsmch.aichat.review.database.ReviewLikeRepository
import com.lvsmsmch.aichat.review.database.ReviewRepository
import com.lvsmsmch.aichat.review.network.ReviewDto
import com.lvsmsmch.aichat.user.database.AccountType
import com.lvsmsmch.aichat.user.database.UserDbo
import com.lvsmsmch.aichat.user.database.UserRepository
import com.lvsmsmch.aichat.user.network.UserDetailsDto
import com.lvsmsmch.aichat.user.network.UserDto

class Mapper(
    val userRepository: UserRepository,
    val characterRepository: CharacterRepository,
    val chatRepository: ChatRepository,
    val messageRepository: MessageRepository,
    val reviewRepository: ReviewRepository,
    val reviewLikeRepository: ReviewLikeRepository,
)

suspend fun UserDbo.toUserDto(mapper: Mapper): UserDto {
    return UserDto(
        id = id,
        username = username,
        name = name,
        profilePicUrl = profilePictureUrl
    )
}

suspend fun UserDbo.toUserDetailsDto(
    mapper: Mapper,
    isDemanderFollowingThisUser: Boolean
): UserDetailsDto {
    return UserDetailsDto(
        id = id,
        lastActiveAt = lastActiveAt.toString(),
        createdAt = createdAt.toString(),
        bio = bio,
        publicCharactersCount = publicCharacterCount,
        privateCharactersCount = privateCharacterCount,
        followersCount = followerCount,
        followingCount = followingCount,
        isFollowing = isDemanderFollowingThisUser
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
        category = category,
        tags = tags,
        picUrl = picUrl,
        totalChats = totalChats,
        totalMessages = totalMessages,
        totalReviews = totalReviews,
        averageRating = averageRating,
    )
}

suspend fun CachedCharactersResult.toDto(mapper: Mapper): CachedCharactersResultDto {
    return CachedCharactersResultDto(
        refreshed = refreshed,
        items = items.map { it.toCharacterDto(mapper) },
        nextCursor = nextCursor,
    )
}

suspend fun CharacterDbo.toCharacterDetailsDto(mapper: Mapper): CharacterDetailsDto {
    return CharacterDetailsDto(
        id = id,
        description = description
    )
}

suspend fun CharacterDbo.toCharacterPrivateInfoDto(mapper: Mapper): CharacterPrivateInfoDto {
    return CharacterPrivateInfoDto(
        id = id,
        prompt = prompt
    )
}

suspend fun CharacterDbo.toCharacterFullInfoDto(mapper: Mapper): CharacterFullInfoDto {
    return CharacterFullInfoDto(
        character = toCharacterDto(mapper),
        characterDetails = toCharacterDetailsDto(mapper),
        characterPrivateInfo = toCharacterPrivateInfoDto(mapper)
    )
}


// В mapper
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

suspend fun ChatDbo.toAddedChatDto(mapper: Mapper): AddedChatDto {
    return AddedChatDto(
        createdAt = createdAt.toString(),
        chatType = chatType.code,
        characterIds = characterIds,
        isChatMuted = isChatMuted,
    )
}

suspend fun ChatDbo.toUpdatedChatDto(mapper: Mapper): UpdatedChatDto {
    return UpdatedChatDto(
        chatType = chatType.code,
        characterIds = characterIds,
        isChatMuted = isChatMuted,
    )
}

suspend fun MessageDbo.toUpdatedMessageDto(mapper: Mapper): UpdatedMessageDto {
    return UpdatedMessageDto(
        messageId = clientId,
        text = text,
        isRead = isRead,
        completedStatus = completedStatus,
    )
}

suspend fun MessageDbo.toAddedMessageDto(mapper: Mapper): AddedMessageDto {
    return AddedMessageDto(
        messageId = clientId,
        createdAt = createdAt.toString(),
        senderId = senderId,
        text = text,
        isRead = isRead,
        completedStatus = completedStatus,
    )
}