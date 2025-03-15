package com.lvsmsmch.aichat.network.responses

import kotlinx.serialization.Serializable

@Serializable
data class DeleteCharacterResponse(
    val success: Int,
    val errorMessage: String,
)