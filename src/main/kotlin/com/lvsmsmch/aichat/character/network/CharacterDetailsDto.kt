package com.lvsmsmch.aichat.character.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CharacterDetailsDto(
    @SerialName("id") val id: String,
    @SerialName("description") val description: String,
)