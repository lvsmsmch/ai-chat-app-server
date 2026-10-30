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
 * HOT — это «с ним сейчас много разговаривают РАЗНЫЕ люди».
 *
 * Скор = база + импульс:
 * - база — общая популярность по счётчикам чатов и сообщений (логарифм:
 *   9000 сообщений не в сто раз «горячее» 90). Она гарантирует, что HOT
 *   всегда есть, даже в тихую неделю;
 * - импульс — живая активность за [windowDays] дней по журналу действий:
 *   число разных собеседников и их сообщения, у каждого не больше
 *   [PER_USER_CAP] — один фанат с тысячей сообщений не делает персонажа
 *   горячим. Импульс считается, только если собеседников хотя бы
 *   [MIN_RECENT_USERS].
 *
 * Сообщения АВТОРА персонажа не считаются нигде в журнале. Кроме того,
 * попасть в HOT может только персонаж, с которым за всё время говорили
 * хотя бы [MIN_PROVEN_USERS] не-автора, либо с большим числом чатов: автор,
 * который сам написал своему новому персонажу сто сообщений, HOT не получит.
 *
 * Берём [HOT_COUNT] лучших.
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
                val allTime = byCharacter(characterActivityLogRepository.messageCountsByCharacterAndUser(null))

                val scored = characters.mapNotNull { c ->
                    val proven = (allTime[c.id]?.size ?: 0) >= MIN_PROVEN_USERS || c.totalChats >= PROVEN_CHATS
                    if (!proven) return@mapNotNull null
                    val base = 2 * ln(1.0 + c.totalChats) + ln(1.0 + c.totalMessages)
                    val users = recent[c.id].orEmpty()
                    val momentum = if (users.size >= MIN_RECENT_USERS) {
                        4.0 * users.size + users.sumOf { min(it.messages, PER_USER_CAP) } / 40.0
                    } else {
                        0.0
                    }
                    c.id to base + momentum
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

/** Сколько персонажей носят HOT. */
private const val HOT_COUNT = 30

/** Разных собеседников (не автора) за окно, чтобы активность считалась импульсом. */
private const val MIN_RECENT_USERS = 2

/** Разных собеседников (не автора) за всё время — «проверенный» персонаж. */
private const val MIN_PROVEN_USERS = 2

/** Либо столько чатов по общему счётчику — давно популярные персонажи каталога. */
private const val PROVEN_CHATS = 100

/** Больше этого сообщений от одного человека в окне не учитываем. */
private const val PER_USER_CAP = 400
