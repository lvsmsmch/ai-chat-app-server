package com.lvsmsmch.aichat.domain.network_dto.responses

import kotlinx.serialization.Serializable

@Serializable
data class DeleteCharacterResponse(
    val success: Int,
    val errorMessage: String,
)