package com.lvsmsmch.aichat.character.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toCharacterLikeDbo
import com.lvsmsmch.aichat.utils.DbSession
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

/** Лайк персонажа: id = "<userId>_<characterId>" (естественная уникальность). */
@Serializable
data class CharacterLikeDbo(
    @BsonId val id: String,
    val userId: String,
    val characterId: String,
    val likedAt: String = UtcTimestamp.now().toString(),
)

class CharacterLikeRepository {

    private fun likeId(userId: String, characterId: String) = "${userId}_${characterId}"

    /** @return true, если лайк реально добавлен (а не повтор). */
    suspend fun like(userId: String, characterId: String): Boolean = dbQuery {
        val id = likeId(userId, characterId)
        val exists = Tables.CharacterLikes.selectAll()
            .where { Tables.CharacterLikes.id eq id }
            .limit(1)
            .any()
        if (exists) {
            false
        } else {
            Tables.CharacterLikes.insert {
                it.from(CharacterLikeDbo(id = id, userId = userId, characterId = characterId))
            }
            true
        }
    }

    /** @return true, если лайк реально снят. */
    suspend fun unlike(userId: String, characterId: String): Boolean = dbQuery {
        Tables.CharacterLikes.deleteWhere {
            Tables.CharacterLikes.id eq likeId(userId, characterId)
        } > 0
    }

    suspend fun isLiked(userId: String, characterId: String): Boolean = dbQuery {
        Tables.CharacterLikes.selectAll()
            .where { Tables.CharacterLikes.id eq likeId(userId, characterId) }
            .limit(1)
            .any()
    }

    /** Батч-проверка для списков: какие из [characterIds] лайкнуты юзером. */
    suspend fun getLikedIds(userId: String, characterIds: List<String>): Set<String> {
        if (characterIds.isEmpty()) return emptySet()
        return dbQuery {
            Tables.CharacterLikes.selectAll()
                .where {
                    (Tables.CharacterLikes.userId eq userId) and
                        (Tables.CharacterLikes.characterId inList characterIds)
                }
                .map { it[Tables.CharacterLikes.characterId] }
                .toSet()
        }
    }

    /** Лайкнутые персонажи юзера, свежие сверху; курсор — likedAt. */
    suspend fun getLikedCharacterIds(
        userId: String,
        cursor: String?,
        size: Int,
    ): Pair<List<String>, String?> = dbQuery {
        val items = Tables.CharacterLikes.selectAll()
            .where {
                val base = Tables.CharacterLikes.userId eq userId
                if (cursor == null) base else base and (Tables.CharacterLikes.likedAt less cursor)
            }
            .orderBy(Tables.CharacterLikes.likedAt to SortOrder.DESC)
            .limit(size + 1)
            .map { it.toCharacterLikeDbo() }
        val page = items.take(size)
        val next = if (items.size > size) page.lastOrNull()?.likedAt else null
        page.map { it.characterId } to next
    }

    suspend fun countForCharacter(characterId: String): Int = dbQuery {
        Tables.CharacterLikes.selectAll()
            .where { Tables.CharacterLikes.characterId eq characterId }
            .count()
            .toInt()
    }

    suspend fun removeAllForCharacters(session: DbSession, characterIds: List<String>) {
        if (characterIds.isEmpty()) return
        dbQuery {
            Tables.CharacterLikes.deleteWhere {
                Tables.CharacterLikes.characterId inList characterIds
            }
        }
    }

    /** Лайки, поставленные юзером (при удалении аккаунта). */
    suspend fun getLikesByUser(session: DbSession, userId: String): List<CharacterLikeDbo> = dbQuery {
        Tables.CharacterLikes.selectAll()
            .where { Tables.CharacterLikes.userId eq userId }
            .map { it.toCharacterLikeDbo() }
    }

    suspend fun removeAllByUser(session: DbSession, userId: String) {
        dbQuery { Tables.CharacterLikes.deleteWhere { Tables.CharacterLikes.userId eq userId } }
    }
}
