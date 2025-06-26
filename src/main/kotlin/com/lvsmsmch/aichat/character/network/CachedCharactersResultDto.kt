package com.lvsmsmch.aichat.character.network

data class CachedCharactersResultDto(
    val refreshed: Boolean = false,
    val items: List<CharacterDto>,
    val nextCursor: Int
)