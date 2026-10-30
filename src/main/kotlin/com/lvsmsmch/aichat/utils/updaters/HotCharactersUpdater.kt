package com.lvsmsmch.aichat.utils.updaters

import com.lvsmsmch.aichat.character.database.CharacterActivityLogRepository
import com.lvsmsmch.aichat.character.database.CharacterRepository
import com.lvsmsmch.aichat.character.database.CharacterUserActivity
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.logger
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.math.min

/**
 * Персонажи с меткой HOT — набор в памяти, пересчитывается раз в час.
 * Выдача персонажей (toCharacterDto) только смотрит в него.
 */
object HotCharacters {
    @Volatile
    var ids: Set<String> = emptySet()
        internal set
}

/**
 * HOT — это «с ним сейчас разговаривают РАЗНЫЕ люди». Метка редкая: только
 * живая активность за [windowDays] дней, без запаса по старым счётчикам
 * (засеянные счётчики каталога делали горячими почти все популярные ленты).
 *
 * Кандидат — персонаж, с которым за окно говорили хотя бы [MIN_RECENT_USERS]
 * разных собеседника. Скор = собеседники + их сообщения (у каждого не больше
 * [PER_USER_CAP] — один фанат с тысячей сообщений не делает персонажа
 * горячим) + немного общей популярности, чтобы развести равных.
 * Сообщения АВТОРА персонажа не считаются.
 *
 * Берём не больше [HOT_COUNT] лучших; в тихую неделю HOT может не быть вовсе.
 */
fun configureHotCharactersUpdater(
    databaseScope: CoroutineScope,
    characterRepository: CharacterRepository,
    characterActivityLogRepository: CharacterActivityLogRepository,
    updateIntervalMinutes: Long = 60,
    windowDays: Long = 7,
): Job {
    val parentJob = SupervisorJob()
    val updaterScope = CoroutineScope(databaseScope.coroutineContext + parentJob)

    updaterScope.launch {
        while (isActive) {
            try {
                val characters = characterRepository.getAllPublicCharacters()
                val authorOf = characters.associate { it.id to it.authorId }
                // Только чужие сообщения: журнал пишет и автора, если он сам
                // разговаривает со своим персонажем
                fun byCharacter(rows: List<CharacterUserActivity>) = rows
                    .filter { row -> authorOf[row.characterId].let { it != null && it != row.userId } }
                    .groupBy { it.characterId }
                val recent = byCharacter(
                    characterActivityLogRepository.messageCountsByCharacterAndUser(
                        UtcTimestamp.now().subtractDays(windowDays),
                    )
                )

                val scored = characters.mapNotNull { c ->
                    val users = recent[c.id].orEmpty()
                    if (users.size < MIN_RECENT_USERS) return@mapNotNull null
                    val momentum = 4.0 * users.size + users.sumOf { min(it.messages, PER_USER_CAP) } / 40.0
                    val base = ln(1.0 + c.totalChats) / 2
                    c.id to momentum + base
                }
                val hot = scored.sortedByDescending { it.second }.take(HOT_COUNT).map { it.first }.toSet()
                HotCharacters.ids = hot
                logger.info("Hot characters updated: ${hot.size} of ${scored.size} eligible")

                delay(TimeUnit.MINUTES.toMillis(updateIntervalMinutes))
            } catch (e: CancellationException) {
                break
            } catch (e: Exception) {
                logger.error("Error during hot characters update: ${e.message}", e)
                delay(TimeUnit.MINUTES.toMillis(10))
            }
        }
    }

    return parentJob
}

/** Сколько персонажей самое большее носят HOT. */
private const val HOT_COUNT = 8

/** Разных собеседников (не автора) за окно, чтобы персонаж мог стать HOT. */
private const val MIN_RECENT_USERS = 3

/** Больше этого сообщений от одного человека в окне не учитываем. */
private const val PER_USER_CAP = 400
