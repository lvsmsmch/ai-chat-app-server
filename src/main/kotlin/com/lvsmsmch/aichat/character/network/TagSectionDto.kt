package com.lvsmsmch.aichat.character.network

import kotlinx.serialization.Serializable

@Serializable
data class TagSectionDto(
    val code: String, // "relationship", "personality", etc.
    val color: String, // "E91E63", "9C27B0", etc.
    val tags: List<CharacterTagDto>
)
