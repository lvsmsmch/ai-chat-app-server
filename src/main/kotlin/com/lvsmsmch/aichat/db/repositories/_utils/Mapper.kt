package com.lvsmsmch.aichat.db.repositories._utils

import com.lvsmsmch.aichat.db.repositories.content.*
import com.lvsmsmch.aichat.network.dto_objects.*
import com.lvsmsmch.aichat.utils.Repositories
import io.ktor.server.plugins.*

class Mapper(val repositories: Repositories)

suspend fun UserDbo.toUserDto(mapper: Mapper): UserDto {
    return UserDto(
        id = id,
        createdAt = createdAt,
        username = username,
        name = name,
        profilePicUrl = profilePicUrl,
    )
}

suspend fun CharacterDbo.toCharacterDto(mapper: Mapper): CharacterDto {
    val repositories = mapper.repositories
    val authorUsername = repositories.userRepository.getUserById(authorId)?.username ?: ""
    return CharacterDto(
        id = id,
        createdAt = createdAt.toString(),
        authorUsername = authorUsername,
        name = name,
        description = description,
        picUrl = picUrl,
        totalMessages = totalMessages,
        totalReviews = totalReviews,
        averageRating = averageRating,
        totalChats = totalChats,
    )
}

suspend fun CharacterDbo.toCharacterDetailsDto(mapper: Mapper): CharacterDetailsDto {
    val repositories = mapper.repositories
    val publisherDto = repositories.userRepository.getUserById(authorId)?.toUserDto(mapper)
    val recentReviewsDto = repositories.reviewRepository.getReviews(
        characterId = id,
        filter = ReviewFilter.NEWEST.code,
        limit = 3,
        skip = 0
    ).map { reviewDbo ->
        reviewDbo.toReviewDto(mapper)
    }

    return CharacterDetailsDto(
        prompt = prompt,
        publisher = publisherDto,
        recentReviews = recentReviewsDto
    )
}


suspend fun ReviewDbo.toReviewDto(mapper: Mapper): ReviewDto {
    val repositories = mapper.repositories
    val publisherDto = repositories.userRepository.getUserById(publisherId)?.toUserDto(mapper)
    return ReviewDto(
        id = id,
        publishedAt = publishedAt.toString(),
        publisher = publisherDto,
        rating = rating,
        text = text,
    )
}

suspend fun MessageDbo.toMessageDto(mapper: Mapper): MessageDto {
    return MessageDto(
        id = id,
        chatId = chatId,
        createdAt = createdAt,
        isSentByUser = isSentByUser,
        text = text,
        isRead = isRead,
        editedAt = editedAt
    )
}

suspend fun ChatDbo.toChatDto(mapper: Mapper): ChatDto {
    val repositories = mapper.repositories

    val character = repositories.characterRepository.getCharacter(characterId)
        ?: throw NotFoundException("Character with id $characterId not found")
    val lastMessageDbo = repositories.messageRepository.getLastMessageInChat(id)
//    val unreadMessageCount = repositories.messageRepository.countUnreadMessagesInChat(id)

    return ChatDto(
        id = id,
        createdAt = createdAt,
        userId = userId,
        characterId = characterId,
        characterName = character.name,
        characterDescription = character.description,
        characterPrompt = character.prompt,
        characterPicUrl = character.picUrl,
        isChatMuted = isChatMuted,
        lastMessage = lastMessage?.toMessageDto(mapper),
        unreadMessageCount = unreadMessageCount,
    )
}