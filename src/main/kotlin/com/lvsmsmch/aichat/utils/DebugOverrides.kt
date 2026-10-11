package com.lvsmsmch.aichat.utils

/**
 * [DEBUG] Глобальные оверрайды моделей — для живого тестирования из настроек
 * приложения (debug-сборка). Хранятся в памяти: рестарт сервера сбрасывает
 * на дефолты (тиринг/гибрид). Работают только при DEBUG_ENDPOINTS=true.
 */
object DebugOverrides {
    /** Текстовая модель вместо тиринга; null — обычная логика. */
    @Volatile
    var textModel: String? = null

    /** Модель картинок вместо гибрида Gemini-топ/провайдер; null — обычная логика. */
    @Volatile
    var imageModel: String? = null

    /**
     * Юзеры с урезанным втрое бесплатным лимитом сообщений — переключатель в
     * DEBUG-разделе настроек тестовой сборки, чтобы быстро дойти до лимита.
     * В памяти: после рестарта приложение присылает своё состояние заново.
     */
    val reducedLimitUsers: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Урезанный втрое лимит: 50 → 17. */
    fun reduced(base: Int): Int = (base + 2) / 3

    /** Бесплатный лимит юзера с учётом урезания. */
    fun freeLimit(userId: String, base: Int): Int =
        if (userId in reducedLimitUsers) reduced(base) else base
}
