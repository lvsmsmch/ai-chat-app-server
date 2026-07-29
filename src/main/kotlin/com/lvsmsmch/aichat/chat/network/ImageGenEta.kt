package com.lvsmsmch.aichat.chat.network

import java.util.concurrent.ConcurrentHashMap

/**
 * Ожидаемое время генерации картинки по провайдерам — клиент рисует по нему
 * прогресс-кружок. Сиды замерены вручную (29.07.2026, по 3 генерации через
 * edit-эндпоинты с референсом): Seedream 4.5 ≈ 18–22с, Grok Imagine ≈ 9с;
 * сверху ~2–3с на докачку с CDN и даунскейл. Дальше значения уточняются
 * скользящим средним по живым генерациям.
 */
object ImageGenEta {

    private val avgMs = ConcurrentHashMap(
        mapOf(
            "seedream" to 24_000L,
            "grok" to 11_000L,
            "gemini" to 16_000L,
        )
    )

    /** Тот же выбор провайдера, что в [AiImageGeneratorUtil.generateImage]. */
    fun providerKey(useTopModel: Boolean): String {
        val debugModel = com.lvsmsmch.aichat.utils.DebugOverrides.imageModel
        return when {
            debugModel?.startsWith("grok") == true -> "grok"
            debugModel?.startsWith("flux") == true ||
                debugModel?.startsWith("seedream") == true -> "seedream"
            debugModel != null -> "gemini"
            useTopModel -> "seedream"
            else -> "grok"
        }
    }

    fun expectedMs(useTopModel: Boolean): Long =
        avgMs[providerKey(useTopModel)] ?: 25_000L

    /** Скользящее среднее: 70% накопленного + 30% нового замера. */
    fun record(key: String, ms: Long) {
        avgMs.merge(key, ms) { old, new -> (old * 7 + new * 3) / 10 }
    }
}
