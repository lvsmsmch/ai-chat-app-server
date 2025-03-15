package com.lvsmsmch.aichat.network.responses

import com.lvsmsmch.aichat.domain.network.objects.CharacterDto
import kotlinx.serialization.Serializable

@Serializable
data class GetCharactersResponse(
    val success: Int,
    val errorMessage: String,
    val totalItemsMatchingFilter: Int,
    val characters: List<CharacterDto>,
)