package com.lvsmsmch.aichat.domain.network_dto.requests

import com.lvsmsmch.aichat.domain.other.filters.CharacterFilter
import kotlinx.serialization.Serializable

@Serializable
data class GetCharactersRequest(
    val filter: Int = CharacterFilter.MOST_POPULAR.code,
    val searchQuery: String = "",
    val limit: Int = 10,
    val skip: Int = 0
)