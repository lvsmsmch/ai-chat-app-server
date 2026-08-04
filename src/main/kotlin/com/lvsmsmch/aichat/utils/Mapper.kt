package com.lvsmsmch.aichat.utils

import com.lvsmsmch.aichat.auth.network.UserLoginInfoDto
import com.lvsmsmch.aichat.auth.network.UserPrivateInfoDto
import com.lvsmsmch.aichat.cache.CachedCharactersResult
import com.lvsmsmch.aichat.character.database.*
import com.lvsmsmch.aichat.character.network.*
import com.lvsmsmch.aichat.chat.database.*
import com.lvsmsmch.aichat.chat.network.*
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
    val characterLikeRepository: com.lvsmsmch.aichat.character.database.CharacterLikeRepository,
) {
    /** Кэш языка персонажей по юзеру (инвалидируется при смене языка). */
    val userLanguageCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    suspend fun languageOf(userId: String?): String? {
        if (userId == null) return null
        userLanguageCache[userId]?.let { return it }
        val lang = userRepository.getUserById(userId)?.characterLanguage ?: "en"
        userLanguageCache[userId] = lang
        return lang
    }
}

/**
 * Авторы списка персонажей ОДНИМ запросом. Без этого каждый элемент ленты
 * тянул своего автора отдельно: страница каталога в 20 плиток давала 20
 * лишних запросов, а список чатов — по запросу на участника.
 */
suspend fun Mapper.authorsOf(characters: Collection<CharacterDbo>): Map<String, UserDto> {
    val ids = characters.map { it.authorId }.toSet()
    return userRepository.getUsersByIds(ids).associate { it.id to it.toUserDto(this) }
}

/**
 * Персонажи по списку id: ОДИН запрос за персонажами, ОДИН за авторами.
 * Порядок [ids] сохраняется — на нём держатся ленты рекомендаций и секции.
 * Пропавшие id молча выпадают, как и раньше.
 */
suspend fun Mapper.charactersDtoByIds(
    ids: List<String>,
    lang: String? = null,
    likedIds: Set<String>? = null,
    publicOnly: Boolean = false,
): List<CharacterDto> {
    if (ids.isEmpty()) return emptyList()
    val dbos = characterRepository.getByIds(ids)
        .let { list ->
            if (publicOnly) {
                list.filter { it.visibility == CharacterVisibility.PUBLIC.code }
            } else {
                list
            }
        }
    val authors = authorsOf(dbos)
    val byId = dbos.associateBy { it.id }
    return ids.mapNotNull { id ->
        byId[id]?.toCharacterDto(this, lang, likedIds = likedIds, authors = authors)
    }
}

/**
 * Автора не нашли (удалён, а персонаж остался). Раньше здесь стоял `!!` и
 * ОДНА такая запись роняла весь список пятисоткой; теперь ломается ровно одна
 * плитка, и то косметически.
 */
private fun deletedAuthorDto(authorId: String) = UserDto(
    id = authorId,
    username = "deleted",
    name = null,
    profilePicUrlThumbnail = null,
    color = "",
)

suspend fun UserDbo.toUserDto(mapper: Mapper): UserDto {
    return UserDto(
        id = id,
        username = username,
        name = name,
        profilePicUrlThumbnail = profilePictureUrlThumbnail ?: profilePictureUrl,
        color = color,
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

/**
 * [likerId] — чей лайк проверяем (текущий юзер); [likedIds] — заранее собранный
 * батч лайков для списков (чтобы не ходить в базу на каждый элемент).
 */
suspend fun CharacterDbo.toCharacterDto(
    mapper: Mapper,
    lang: String? = null,
    likerId: String? = null,
    likedIds: Set<String>? = null,
    /** Заранее собранные авторы (см. [authorsOf]) — для списков. */
    authors: Map<String, UserDto>? = null,
): CharacterDto {
    val c = this.localized(lang)
    val liked = when {
        likedIds != null -> id in likedIds
        likerId != null -> mapper.characterLikeRepository.isLiked(likerId, id)
        else -> false
    }
    return CharacterDto(
        id = id,
        createdAt = createdAt.toString(),
        author = authors?.get(authorId)
            ?: mapper.userRepository.getUserById(authorId)?.toUserDto(mapper)
            ?: deletedAuthorDto(authorId),
        visibility = visibility,
        name = c.name,
        description = c.description,
        category = category,
        tags = tags,
        totalChats = totalChats,
        totalMessages = totalMessages,
        totalReviews = totalReviews,
        totalComments = totalComments,
        averageRating = averageRating,
        picUrl = picUrl,
        picUrlThumbnail = picUrlThumbnail ?: picUrl,
        color = color,
        topRank = topRank,
        totalLikes = totalLikes,
        isLikedByCurrentUser = liked,
    )
}

suspend fun CachedCharactersResult.toDto(
    mapper: Mapper,
    lang: String? = null,
    likerId: String? = null,
): CachedCharactersResultDto {
    // Лайки списка — ОДНИМ батч-запросом, а не по персонажу
    val liked = likerId?.let {
        mapper.characterLikeRepository.getLikedIds(it, items.map { c -> c.id })
    }
    val authors = mapper.authorsOf(items)
    return CachedCharactersResultDto(
        refreshed = refreshed,
        items = items.map { it.toCharacterDto(mapper, lang, likedIds = liked, authors = authors) },
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

suspend fun CharacterDbo.toCharacterPrivateInfoDto(mapper: Mapper, lang: String? = null): CharacterPrivateInfoDto {
    val c = this.localized(lang)
    return CharacterPrivateInfoDto(
        id = id,
        prompt = c.prompt,
        initialMessage = c.initialMessage
    )
}

suspend fun CharacterDbo.toCharacterFullInfoDto(mapper: Mapper, demanderId: String): CharacterFullInfoDto {
    val lang = mapper.languageOf(demanderId)
    return CharacterFullInfoDto(
        character = toCharacterDto(mapper, lang, likerId = demanderId),
        characterDetails = toCharacterDetailsDto(mapper, demanderId),
        characterPrivateInfo = toCharacterPrivateInfoDto(mapper, lang)
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
    // Язык владельца чата: имена/авы участников локализованы и в списке чатов
    val lang = mapper.languageOf(userId)
    // Участники и их авторы — двумя запросами на чат вместо двух на участника
    val characters = mapper.charactersDtoByIds(characterIds, lang)

    return ChatDto(
        id = clientId,
        chatType = type.code,
        customName = customName,
        characters = characters,
        createdAt = createdAt,
        color = color,
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
        failReason = failReason,
        nsfw = nsfw,
        imageUrl = imageUrl,
        isImage = isImage,
        imageDebugInfo = imageDebugInfo
    )
}
