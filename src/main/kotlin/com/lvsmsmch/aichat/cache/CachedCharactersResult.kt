package com.lvsmsmch.aichat.cache

import com.lvsmsmch.aichat.character.database.CharacterDbo

data class CachedCharactersResult(
    val refreshed: Boolean = false,
    val items: List<CharacterDbo>,
    val nextCursor: Int? = null,
    /**
     * Сколько всего нашлось по этому запросу. Берётся из уже посчитанной
     * копии списка — отдельного COUNT ради строки «12 results» не делаем.
     */
    val totalFound: Int? = null,
)