package com.lvsmsmch.aichat.character.network

import com.lvsmsmch.aichat.user.network.UserDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ============= RESPONSE MODELS =============

@Serializable
data class CharacterDto(
    @SerialName("id") val id: String,
    @SerialName("createdAt") val createdAt: String,
    @SerialName("author") val author: UserDto,
    @SerialName("visibility") val visibility: Int,
    @SerialName("name") val name: String,
    @SerialName("category") val category: String,
    @SerialName("tags") val tags: List<String>,
    @SerialName("picUrl") val picUrl: String? = null,
    @SerialName("totalChats") val totalChats: Int,
    @SerialName("totalMessages") val totalMessages: Int,
    @SerialName("totalReviews") val totalReviews: Int,
    @SerialName("averageRating") val averageRating: Float
)

@Serializable
data class CharacterDetailsDto(
    @SerialName("id") val id: String,
    @SerialName("description") val description: String,
    @SerialName("isReviewed") val isReviewed: Boolean   // todo
)

@Serializable
data class CharacterPrivateInfoDto(
    @SerialName("id") val id: String,
    @SerialName("prompt") val prompt: String,
    @SerialName("initialMessage") val initialMessage: String
)

@Serializable
data class CharacterFullInfoDto(
    @SerialName("character") val character: CharacterDto,
    @SerialName("characterDetails") val characterDetails: CharacterDetailsDto,
    @SerialName("characterPrivateInfo") val characterPrivateInfo: CharacterPrivateInfoDto
)

@Serializable
data class CachedCharactersResultDto(
    @SerialName("refreshed") val refreshed: Boolean = false,
    @SerialName("items") val items: List<CharacterDto>,
    @SerialName("nextCursor") val nextCursor: String? = null
)

@Serializable
data class CharacterCategoryDto(
    @SerialName("code") val code: String,
    @SerialName("color") val color: String
)

@Serializable
data class CharacterTagDto(
    @SerialName("code") val code: String
)

@Serializable
data class TagSectionDto(
    @SerialName("code") val code: String,
    @SerialName("color") val color: String,
    @SerialName("tags") val tags: List<CharacterTagDto>
)

@Serializable
data class SearchSuggestionsResponse(
    @SerialName("suggestions") val suggestions: List<String>
)

@Serializable
data class SimilarCharactersResponse(
    @SerialName("characters") val characters: List<CharacterDto>
)

// ============= REQUEST MODELS =============

@Serializable
data class SearchCharactersRequest(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("searchQuery") val searchQuery: String = "",
    @SerialName("sortCriteria") val sortCriteria: Int = 0,
    @SerialName("size") val size: Int = 10,
    @SerialName("cursor") val cursor: String? = null
)

@Serializable
data class GetSearchSuggestionsRequest(
    @SerialName("query") val query: String = "",
    @SerialName("size") val size: Int = 5
)

@Serializable
data class GetCharactersByCategoryRequest(
    @SerialName("deviceId") val deviceId: String,
    @SerialName("size") val size: Int = 10,
    @SerialName("cursor") val cursor: String? = null,
    @SerialName("refresh") val refresh: Boolean = false
)

@Serializable
data class ReportCharacterRequest(
    @SerialName("reason") val reason: String,
    @SerialName("text") val text: String = ""
)