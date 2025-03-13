package com.lvsmsmch.aichat.domain.network_dto.responses

import com.lvsmsmch.aichat.domain.network_dto.objects.CharacterDto
import kotlinx.serialization.Serializable

@Serializable
data class GetCharacterResponse(
    val success: Int,
    val errorMessage: String,
    val character: CharacterDto?,
)