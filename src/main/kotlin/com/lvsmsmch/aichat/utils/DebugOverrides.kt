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
}
