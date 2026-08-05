package com.lvsmsmch.aichat.user.network

import com.lvsmsmch.aichat.character.network.CharacterDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class UserDto(
    @SerialName("id") val id: String,
    @SerialName("username") val username: String,
    @SerialName("name") val name: String? = null,
    @SerialName("profilePicUrlThumbnail") val profilePicUrlThumbnail: String? = null,
    @SerialName("color") val color: String = ""
)

@Serializable
data class UserDetailsDto(
    @SerialName("id") val id: String,
    @SerialName("lastActiveAt") val lastActiveAt: String,
    @SerialName("createdAt") val createdAt: String,
    @SerialName("bio") val bio: String? = null,
    @SerialName("publicCharactersCount") val publicCharactersCount: Int = 0,
    @SerialName("privateCharactersCount") val privateCharactersCount: Int = 0,
    @SerialName("followersCount") val followersCount: Int = 0,
    @SerialName("followingCount") val followingCount: Int = 0,
    @SerialName("totalChatsCount") val totalChatsCount: Int = 0,
    @SerialName("totalMessagesCount") val totalMessagesCount: Int = 0,
    @SerialName("isFollowing") val isFollowing: Boolean = false,
    /** Текущий пользователь заблокировал этого — кнопка в меню меняет подпись. */
    @SerialName("isBlocked") val isBlocked: Boolean = false,
    @SerialName("profilePicUrl") val profilePicUrl: String? = null
)

/** Свой список заблокированных: экран «Blocked users» в настройках. */
@Serializable
data class BlockedUsersResponse(
    @SerialName("users") val users: List<UserDto> = emptyList(),
)

@Serializable
data class UserFullInfoDto(
    @SerialName("user") val user: UserDto,
    @SerialName("userDetails") val userDetails: UserDetailsDto
)

@Serializable
data class FollowerDto(
    @SerialName("follower") val follower: UserDto,
    @SerialName("followedAt") val followedAt: String,
    /** Подписан ли текущий пользователь на этого подписчика. */
    @SerialName("isFollowing") val isFollowing: Boolean = false,
)

@Serializable
data class FollowingDto(
    @SerialName("following") val following: UserDto,
    @SerialName("followedAt") val followedAt: String,
    /** Подписан ли текущий пользователь на этого пользователя. */
    @SerialName("isFollowing") val isFollowing: Boolean = false,
)

@Serializable
data class FollowersResponse(
    @SerialName("followers") val followers: List<FollowerDto>,
    @SerialName("nextCursor") val nextCursor: String? = null
)

@Serializable
data class FollowingResponse(
    @SerialName("following") val following: List<FollowingDto>,
    @SerialName("nextCursor") val nextCursor: String? = null
)

@Serializable
data class UserCharactersResponse(
    @SerialName("characters") val characters: List<CharacterDto>,
    @SerialName("nextCursor") val nextCursor: String? = null
)


@Serializable
data class GetUserCharactersRequest(
    @SerialName("visibility") val visibility: Int? = null,
    @SerialName("cursor") val cursor: String? = null,
    @SerialName("size") val size: Int = 10
)

@Serializable
data class GetFollowersRequest(
    @SerialName("cursor") val cursor: String? = null,
    @SerialName("size") val size: Int = 10
)

@Serializable
data class GetFollowingRequest(
    @SerialName("cursor") val cursor: String? = null,
    @SerialName("size") val size: Int = 10
)

@Serializable
data class ReportUserRequest(
    @SerialName("reason") val reason: String,
    @SerialName("text") val text: String = ""
)
@kotlinx.serialization.Serializable
data class SetCharacterLanguageRequest(
    @kotlinx.serialization.SerialName("language") val language: String
)

@kotlinx.serialization.Serializable
data class SetFcmTokenRequest(
    @kotlinx.serialization.SerialName("token") val token: String
)

/** [DEBUG] Оверрайды моделей: null — не менять, пустая строка — сбросить на дефолт. */
@kotlinx.serialization.Serializable
data class DebugModelsRequest(
    @kotlinx.serialization.SerialName("textModel") val textModel: String? = null,
    @kotlinx.serialization.SerialName("imageModel") val imageModel: String? = null,
)

@kotlinx.serialization.Serializable
data class DebugModelsResponse(
    @kotlinx.serialization.SerialName("textModel") val textModel: String?,
    @kotlinx.serialization.SerialName("imageModel") val imageModel: String?,
)
