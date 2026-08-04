package com.lvsmsmch.aichat.cache.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toDiscoverSectionsCacheDbo
import com.lvsmsmch.aichat.utils.UtcTimestamp
import org.bson.codecs.pojo.annotations.BsonId
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.upsert

/**
 * Секции вкладки For you (Discover): готовые списки id персонажей на юзера.
 * Главные секции (for_you / trending / most_popular) — по 30 id (клиент
 * пагинирует по 10), категорийные — по 10. Документ "__default" — набор для
 * свежерегнутых юзеров: копируется им мгновенно, без расчёта.
 */
@kotlinx.serialization.Serializable
data class DiscoverSectionDbo(
    val key: String,
    val characterIds: List<String>,
)

data class DiscoverSectionsCacheDbo(
    @BsonId val id: String, // userId или "__default"
    val sections: List<DiscoverSectionDbo> = emptyList(),
    val updatedAt: String = UtcTimestamp.now().toString(),
)

const val DEFAULT_DISCOVER_CACHE_ID = "__default"

class DiscoverSectionsCacheRepository {

    suspend fun upsert(id: String, sections: List<DiscoverSectionDbo>) {
        val dbo = DiscoverSectionsCacheDbo(id = id, sections = sections)
        dbQuery { Tables.DiscoverSectionsCache.upsert { it.from(dbo) } }
    }

    suspend fun get(id: String): DiscoverSectionsCacheDbo? = dbQuery {
        Tables.DiscoverSectionsCache.selectAll()
            .where { Tables.DiscoverSectionsCache.id eq id }
            .limit(1)
            .firstOrNull()
            ?.toDiscoverSectionsCacheDbo()
    }

    /** Кэш юзера, а если его ещё нет — дефолтный набор новорега. */
    suspend fun getForUserOrDefault(userId: String): DiscoverSectionsCacheDbo? =
        get(userId) ?: get(DEFAULT_DISCOVER_CACHE_ID)

    /** Мгновенный кэш для свежерегнутого юзера — копия дефолтного набора. */
    suspend fun copyDefaultTo(userId: String) {
        val default = get(DEFAULT_DISCOVER_CACHE_ID) ?: return
        upsert(userId, default.sections)
    }

    suspend fun delete(id: String) {
        dbQuery {
            Tables.DiscoverSectionsCache.deleteWhere { Tables.DiscoverSectionsCache.id eq id }
        }
    }
}
