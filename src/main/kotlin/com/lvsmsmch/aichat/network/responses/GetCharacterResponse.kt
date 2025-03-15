package com.lvsmsmch.aichat.network.responses

import com.lvsmsmch.aichat.domain.network.objects.CharacterDto
import kotlinx.serialization.Serializable

@Serializable
data class GetCharacterResponse(
    val success: Int,
    val errorMessage: String,
    val character: CharacterDto?,
)