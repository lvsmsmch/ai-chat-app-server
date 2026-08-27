package com.lvsmsmch.aichat.character

/**
 * Коды встроенных обложек чата. Список ОБЯЗАН совпадать с WebP-файлами в
 * `src/main/resources/chat-covers`: сервер раздаёт клиенту и код, и сам файл
 * по `/chat-covers/<code>.webp`. Неизвестный код клиент покажет градиентом,
 * так что рассинхрон не ломает экран, но и смысла в нём нет.
 */
object ChatCovers {

    val builtIn = listOf(
        "night_city", "neon_bar", "room", "party", "fantasy",
        "space", "sunset", "ocean", "forest", "snow",
        "school", "street", "cyber", "sakura", "desert",
    )

    /**
     * Обложка по умолчанию — стабильно выведена из id персонажа, а не выбрана
     * случайно в момент вызова: у одного персонажа она всегда одна и та же,
     * и при этом по каталогу они раскиданы равномерно.
     */
    fun defaultFor(characterId: String): String {
        val hash = characterId.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7fffffff }
        return builtIn[hash % builtIn.size]
    }
}
