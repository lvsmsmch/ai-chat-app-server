package com.lvsmsmch.aichat.domain.network_dto.requests

import kotlinx.serialization.Serializable

@Serializable
data class DeleteCharacterRequest(
    val characterId: String,
)