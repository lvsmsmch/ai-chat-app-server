package com.lvsmsmch.aichat.character

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Встроенные обложки чата. Идентификаторы номерные: смысловые коды привязывали
 * файл к сюжету, и после перерисовки начинали врать, а обойти клиентский кэш
 * можно было только версией в URL. Новый набор просто получает следующие
 * номера.
 *
 * Список берётся из `chat-covers/catalog.json` — того же файла, что раздаётся
 * клиенту рядом с картинками. Один источник правды: если картинка появилась,
 * а в каталоге её нет (или наоборот), это видно сразу.
 */
object ChatCovers {

    /**
     * Что изображено на обложке. [description] и [setting] нужны, чтобы
     * подбирать персонажу подходящий фон, а не случайный номер.
     */
    @Serializable
    data class Cover(
        val id: String,
        val title: String,
        /** anime | real | cyber | fantasy */
        val style: String,
        /** morning | day | evening | night */
        val time: String,
        /** school, room, city, forest… — место действия одним словом. */
        val setting: String,
        val description: String,
    )

    @Serializable
    private data class Catalog(val version: Int = 1, val items: List<Cover> = emptyList())

    val catalog: List<Cover> by lazy {
        val json = javaClass.classLoader
            ?.getResourceAsStream("chat-covers/catalog.json")
            ?.bufferedReader()
            ?.use { it.readText() }
            ?: return@lazy emptyList()
        Json { ignoreUnknownKeys = true }.decodeFromString<Catalog>(json).items
    }

    val builtIn: List<String> by lazy { catalog.map { it.id } }

    fun byId(id: String?): Cover? = catalog.firstOrNull { it.id == id }

    /** Обложки, подходящие по стилю: основа будущего подбора под персонажа. */
    fun byStyle(style: String): List<Cover> = catalog.filter { it.style == style }

    /**
     * Обложка по умолчанию — стабильно выведена из id персонажа, а не выбрана
     * случайно в момент вызова: у одного персонажа она всегда одна и та же,
     * и при этом по каталогу они раскиданы равномерно.
     */
    fun defaultFor(characterId: String): String {
        // По НОМЕРУ, а не по месту в каталоге: порядок в каталоге задаёт вид
        // списка в приложении и может меняться, а раскидка от него зависеть
        // не должна — иначе каждая перестановка тасует обложки у всех
        val ids = builtIn.sortedBy { it.toIntOrNull() ?: 0 }
        if (ids.isEmpty()) return ""
        val hash = characterId.fold(0) { acc, c -> (acc * 31 + c.code) and 0x7fffffff }
        return ids[hash % ids.size]
    }
}
