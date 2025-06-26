package com.lvsmsmch.aichat.character.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CharacterFullInfoDto(
    @SerialName("character") val character: CharacterDto,
    @SerialName("characterDetails") val characterDetails: CharacterDetailsDto,
    @SerialName("characterPrivateInfo") val characterPrivateInfo: CharacterPrivateInfoDto
)