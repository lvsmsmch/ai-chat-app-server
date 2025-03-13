package com.lvsmsmch.aichat.domain.network_dto.requests

import kotlinx.serialization.Serializable

@Serializable
data class AddCharacterRequest(
    val userId: String,
    val name: String,
    val description: String,
    val picUrl: String,
    val prompt: String,
    val publishedBy: String,
)