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

    // Холодный старт: первая генерация после долгой паузы заметно дольше
    // (наблюдалось 40–50с у Seedream против обычных ~20с) — если провайдер
    // давно не дёргали, ожидаемое время удваиваем
    private val lastGenAt = ConcurrentHashMap<String, Long>()
    private const val WARM_WINDOW_MS = 20 * 60_000L

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

    fun expectedMs(useTopModel: Boolean): Long {
        val key = providerKey(useTopModel)
        val base = avgMs[key] ?: 25_000L
        val last = lastGenAt[key]
        val cold = last == null || System.currentTimeMillis() - last > WARM_WINDOW_MS
        return if (cold) base * 2 else base
    }

    /**
     * Скользящее среднее: 70% накопленного + 30% нового замера. Холодные
     * (после паузы) замеры в среднее не мешаем — они бы завышали тёплые оценки.
     */
    fun record(key: String, ms: Long) {
        val last = lastGenAt.put(key, System.currentTimeMillis())
        val wasWarm = last != null && System.currentTimeMillis() - ms - last < WARM_WINDOW_MS
        if (wasWarm) avgMs.merge(key, ms) { old, new -> (old * 7 + new * 3) / 10 }
    }
}
