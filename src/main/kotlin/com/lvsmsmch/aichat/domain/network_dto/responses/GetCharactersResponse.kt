package com.lvsmsmch.aichat.domain.network_dto.responses

import com.lvsmsmch.aichat.domain.network_dto.objects.CharacterDto
import kotlinx.serialization.Serializable

@Serializable
data class GetCharactersResponse(
    val success: Int,
    val errorMessage: String,
    val totalItemsMatchingFilter: Int,
    val characters: List<CharacterDto>,
)