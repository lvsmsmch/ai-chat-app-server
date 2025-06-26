package com.lvsmsmch.aichat.cache

import com.lvsmsmch.aichat.character.database.CharacterDbo

data class CachedCharactersResult(
    val refreshed: Boolean = false,
    val items: List<CharacterDbo>,
    val nextCursor: Int
)