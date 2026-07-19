package com.lvsmsmch.aichat.character.database

import com.lvsmsmch.aichat._common.AvatarColors
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

@Serializable
data class CharacterDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val createdAt: String = UtcTimestamp.now().toString(),
    val authorId: String,
    val name: String,
    val description: String,
    val prompt: String,
    val initialMessage: String,
    val picUrl: String? = null,
    val picUrlThumbnail: String? = null,
    val visibility: Int,
    val category: String,
    val tags: List<String>,
    val totalChats: Int = 0,
    val totalMessages: Int = 0,
    val totalReviews: Int = 0,
    val totalComments: Int = 0,
    val averageRating: Float = 0f,
    val trendingScore: Float = 0f,
    val trendingScoreUpdatedAt: String? = null,
    val recommendationScore: Float = 0f,
    val recommendationScoreUpdatedAt: String? = null,
    val recommendationsScoreMultiplier: Float? = null,
    val coOccurrenceScore: Map<String, Float> = emptyMap(),
    val coOccurrenceScoreUpdatedAt: String? = null,
    val color: String = AvatarColors.random(),
    // Место в топе своей категории за всё время (1..3); пересчитывается раз в день
    val topRank: Int? = null,
    /** Локализации по языкам ("ru" → перевод); отсутствие языка = оригинал (en). */
    val translations: Map<String, CharacterTranslationDbo> = emptyMap()
)

@kotlinx.serialization.Serializable
data class CharacterTranslationDbo(
    val name: String,
    val description: String,
    val prompt: String,
    val initialMessage: String,
)

/** Поддерживаемые языки локализации персонажей (кроме en-оригинала). */
val SUPPORTED_CHARACTER_LANGUAGES = listOf("ru")

/**
 * Локализованная копия: имя/описание/промпт/приветствие подменяются переводом.
 * en, неизвестный язык или отсутствие перевода — оригинал.
 */
fun CharacterDbo.localized(lang: String?): CharacterDbo {
    if (lang == null || lang == "en") return this
    val t = translations[lang] ?: return this
    return copy(
        name = t.name.ifBlank { name },
        description = t.description.ifBlank { description },
        prompt = t.prompt.ifBlank { prompt },
        initialMessage = t.initialMessage.ifBlank { initialMessage },
    )
}