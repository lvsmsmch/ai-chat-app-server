package com.lvsmsmch.aichat.character.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CharacterPrivateInfoDto(
    @SerialName("id") val id: String,
    @SerialName("prompt") val prompt: String
)