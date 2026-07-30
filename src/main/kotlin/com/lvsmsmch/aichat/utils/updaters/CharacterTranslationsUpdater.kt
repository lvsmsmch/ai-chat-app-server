package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.character.database.CharacterDbo
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.character.database.CharacterTranslationDbo
import com.lvsmsmch.aichat.character.database.SUPPORTED_CHARACTER_LANGUAGES
import com.lvsmsmch.aichat.utils.defaultJson
import com.lvsmsmch.aichat.utils.logger
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*
import java.util.concurrent.TimeUnit

/**
 * Фоновая дозаливка переводов персонажей: для каждой пары (персонаж, язык) из
 * SUPPORTED_CHARACTER_LANGUAGES, где перевода ещё нет, переводит карточку
 * (имя/описание/промпт/приветствие) дешёвой Gemini-моделью и сохраняет в
 * translations.<lang>. Существующие переводы НЕ трогает (ручные правки — типа
 * имён по Поливанову в ru — не перезатираются). Новые персонажи и новые языки
 * подхватываются следующим проходом автоматически.
 */
fun configureCharacterTranslationsUpdater(
    databaseScope: CoroutineScope,
    characterRepository: CharacterRepository,
    updateIntervalMinutes: Long = 60,
    /** Параллельных запросов к Gemini — flash-lite спокойно тянет десяток. */
    parallelism: Int = 10,
): Job {
    val parentJob = SupervisorJob()
    val updaterScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    updaterScope.launch {
        while (isActive) {
            try {
                val all = characterRepository.getAllPublicCharacters()
                val missing = all.flatMap { c ->
                    SUPPORTED_CHARACTER_LANGUAGES
                        .filter { lang -> c.translations[lang] == null }
                        .map { lang -> c to lang }
                }
                if (missing.isNotEmpty()) {
                    logger.info("Translations: ${missing.size} missing (char, lang) pairs, starting")
                    val sem = Semaphore(parallelism)
                    var ok = 0
                    var failed = 0
                    missing.map { (c, lang) ->
                        launch {
                            sem.withPermit {
                                try {
                                    val t = translateCharacter(c, lang)
                                    characterRepository.updateTranslation(c.id, lang, t)
                                    ok++
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    // Пропускаем — доедет следующим проходом
                                    failed++
                                    if (failed <= 5) {
                                        logger.error("Translation failed for ${c.id}/$lang: ${e.message}")
                                    }
                                }
                            }
                        }
                    }.joinAll()
                    logger.info("Translations: done, ok=$ok failed=$failed")
                }
                delay(TimeUnit.MINUTES.toMillis(updateIntervalMinutes))
            } catch (e: CancellationException) {
                logger.debug("Translations updater cancelled")
                break
            } catch (e: Exception) {
                logger.error("Error in translations updater: ${e.message}", e)
                delay(TimeUnit.MINUTES.toMillis(10))
            }
        }
    }

    return parentJob
}

private val translationModel
    get() = System.getenv("TRANSLATION_MODEL") ?: "gemini-3.1-flash-lite"
private val geminiApiUrl
    get() = System.getenv("GEMINI_API_URL") ?: "https://generativelanguage.googleapis.com/v1beta/models"
private val geminiApiKey
    get() = System.getenv("GEMINI_API_KEY") ?: throw Exception("Missing GEMINI_API_KEY key")

private val languageNames = mapOf(
    "ru" to "Russian", "es" to "Spanish", "pt" to "Portuguese", "fr" to "French",
    "de" to "German", "it" to "Italian", "ja" to "Japanese", "ko" to "Korean",
    "zh" to "Simplified Chinese", "ar" to "Arabic", "hi" to "Hindi",
    "id" to "Indonesian", "tr" to "Turkish", "vi" to "Vietnamese", "th" to "Thai",
    "pl" to "Polish", "nl" to "Dutch", "uk" to "Ukrainian", "ro" to "Romanian",
)

private val httpClient = HttpClient {
    install(ContentNegotiation) { json(defaultJson) }
    install(HttpTimeout) { requestTimeoutMillis = 60_000 }
}

private suspend fun translateCharacter(c: CharacterDbo, lang: String): CharacterTranslationDbo {
    val language = languageNames[lang] ?: lang
    val prompt = buildString {
        append("Translate this roleplay character card from English to $language.\n")
        append("Rules:\n")
        append("- name: use the OFFICIAL localized character name in $language if the franchise ")
        append("has one (anime, games, movies); otherwise transliterate naturally. Real-person ")
        append("or invented names keep their usual local spelling.\n")
        append("- description, prompt, initialMessage: translate fully and naturally, keep the ")
        append("tone and roleplay style. Keep *asterisk action* formatting and any placeholders ")
        append("exactly as in the source. The prompt stays an instruction addressed to the AI.\n")
        append("Return ONLY JSON: {\"name\":\"...\",\"description\":\"...\",\"prompt\":\"...\",\"initialMessage\":\"...\"}\n\n")
        append("name: ").append(c.name).append("\n")
        append("description: ").append(c.description).append("\n")
        append("prompt: ").append(c.prompt).append("\n")
        append("initialMessage: ").append(c.initialMessage)
    }
    val body = buildJsonObject {
        putJsonArray("contents") {
            addJsonObject {
                put("role", "user")
                putJsonArray("parts") { addJsonObject { put("text", prompt) } }
            }
        }
        putJsonObject("generationConfig") {
            put("temperature", 0.2)
            put("responseMimeType", "application/json")
        }
        // Ролевые карточки (и особенно их переводы на ar/uk и т.п.) иногда
        // триггерят фильтр — для ПЕРЕВОДА уже опубликованного контента
        // отключаем блокировку
        putJsonArray("safetySettings") {
            listOf(
                "HARM_CATEGORY_HARASSMENT",
                "HARM_CATEGORY_HATE_SPEECH",
                "HARM_CATEGORY_SEXUALLY_EXPLICIT",
                "HARM_CATEGORY_DANGEROUS_CONTENT",
            ).forEach { cat ->
                addJsonObject {
                    put("category", cat)
                    put("threshold", "BLOCK_NONE")
                }
            }
        }
    }
    val response = httpClient.post("$geminiApiUrl/$translationModel:generateContent?key=$geminiApiKey") {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
    if (response.status != HttpStatusCode.OK) {
        throw Exception("Gemini $translationModel: ${response.status}")
    }
    val rawBody = response.bodyAsText()
    val geminiText = Json.parseToJsonElement(rawBody).jsonObject["candidates"]?.jsonArray
        ?.firstOrNull()?.jsonObject
        ?.get("content")?.jsonObject
        ?.get("parts")?.jsonArray
        ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
        ?.joinToString("")
    // Жёсткий фильтр Gemini (PROHIBITED_CONTENT) BLOCK_NONE не обходит —
    // такие пары переводим Grok'ом, как и цензурные ответы в чатах
    val text = geminiText ?: translateViaGrok(prompt)
    val json = Json.parseToJsonElement(text).jsonObject
    fun field(k: String) = json[k]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val t = CharacterTranslationDbo(
        name = field("name"),
        description = field("description"),
        prompt = field("prompt"),
        initialMessage = field("initialMessage"),
    )
    // Пустые в ОРИГИНАЛЕ поля легитимно остаются пустыми (юзерские персонажи
    // бывают без описания/промпта) — требуем перевод только непустых
    if (t.name.isBlank() ||
        (c.prompt.isNotBlank() && t.prompt.isBlank()) ||
        (c.initialMessage.isNotBlank() && t.initialMessage.isBlank())
    ) {
        throw Exception("Empty translation fields")
    }
    return t
}

/** Фолбэк-переводчик: Grok через OpenAI-совместимый эндпоинт (без фильтра). */
private suspend fun translateViaGrok(prompt: String): String {
    val url = System.getenv("OPEN_AI_API_URL") ?: throw Exception("Missing OPEN_AI_API_URL")
    val key = System.getenv("OPEN_AI_API_KEY") ?: throw Exception("Missing OPEN_AI_API_KEY")
    val model = System.getenv("OPEN_AI_MODEL") ?: throw Exception("Missing OPEN_AI_MODEL")
    val body = buildJsonObject {
        put("model", model)
        putJsonArray("messages") {
            addJsonObject {
                put("role", "user")
                put("content", prompt)
            }
        }
        put("temperature", 0.2)
        putJsonObject("response_format") { put("type", "json_object") }
    }
    val response = httpClient.post(url) {
        header(HttpHeaders.Authorization, "Bearer $key")
        contentType(ContentType.Application.Json)
        setBody(body)
    }
    if (response.status != HttpStatusCode.OK) {
        throw Exception("Grok translate: ${response.status}")
    }
    return Json.parseToJsonElement(response.bodyAsText()).jsonObject["choices"]?.jsonArray
        ?.firstOrNull()?.jsonObject
        ?.get("message")?.jsonObject
        ?.get("content")?.jsonPrimitive?.contentOrNull
        ?: throw Exception("No text in Grok response")
}
