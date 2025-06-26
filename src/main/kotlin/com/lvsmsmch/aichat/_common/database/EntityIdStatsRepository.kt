package com.lvsmsmch.aichat._common.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.litote.kmongo.coroutine.CoroutineCollection

enum class EntityType(val code: String, val recommendedIdLength: Int) {
    USER("user", 6),
    CHARACTER("character", 6),
    CHAT("chat", 10),
    MESSAGE("message", 10),
    REVIEW("review", 6);

    companion object {
        fun fromCode(code: String) = entries.first { it.code == code }
    }
}

@Serializable
data class EntityIdStatsDbo(
    @BsonId val entityType: String,
    val deletedIds: Set<String> = emptySet(),
    val totalGenerated: Long = 0,
    val createdAt: UtcTimestamp = UtcTimestamp.now(),
    val lastUpdated: UtcTimestamp = UtcTimestamp.now()
)

class EntityIdStatsRepository(
    private val collection: CoroutineCollection<EntityIdStatsDbo>
) {

    suspend fun isIdDeleted(entityType: EntityType, id: String): Boolean {
        val stats = getOrCreateEntityIdStats(entityType)
        return stats.deletedIds.contains(id)
    }

    suspend fun idWasGenerated(entityType: EntityType) {
        updateEntity(entityType) {
            it.copy(
                totalGenerated = it.totalGenerated + 1,
                lastUpdated = UtcTimestamp.now()
            )
        }
    }

    suspend fun idWasDeleted(entityType: EntityType, id: String) {
        updateEntity(entityType) {
            it.copy(
                deletedIds = it.deletedIds + id,
                lastUpdated = UtcTimestamp.now()
            )
        }
    }

    private suspend fun getOrCreateEntityIdStats(entityType: EntityType): EntityIdStatsDbo {
        return collection.findOneById(entityType.code)
            ?: EntityIdStatsDbo(entityType = entityType.code).also {
                collection.insertOne(it)
            }
    }

    private suspend fun updateEntity(entityType: EntityType, transform: (EntityIdStatsDbo) -> EntityIdStatsDbo) {
        val current = collection.findOneById(entityType.code)
            ?: EntityIdStatsDbo(entityType = entityType.code).also {
                collection.insertOne(it)
            }

        val transformed = transform(current)

        collection.replaceOneById(current.entityType, transformed)
    }

    suspend fun getGenerationStats(entityType: EntityType): EntityGenerationStats {
        val manager = getOrCreateEntityIdStats(entityType)

        return EntityGenerationStats(
            entityType = entityType,
            totalGenerated = manager.totalGenerated,
            deletedIdsCount = manager.deletedIds.size,
            createdAt = manager.createdAt,
            lastUpdated = manager.lastUpdated,
            estimatedCurrentLength = estimateCurrentLength(manager.totalGenerated, manager.deletedIds.size)
        )
    }

    private fun estimateCurrentLength(totalGenerated: Long, deletedCount: Int): Int {
        // Прикидочный расчет текущей длины на основе количества сгенерированных ID
        val activeIds = totalGenerated - deletedCount

        return when {
            activeIds < 10_000_000L -> 6      // До 10M - 6 символов
            activeIds < 500_000_000L -> 7     // До 500M - 7 символов
            activeIds < 25_000_000_000L -> 8  // До 25B - 8 символов
            else -> 9
        }
    }

    suspend fun getAllStats(): List<EntityGenerationStats> {
        return EntityType.entries.map { getGenerationStats(it) }
    }

    suspend fun analyzeStorageUsage(): StorageUsageAnalysis {
        val allStats = getAllStats()

        val totalDeletedIds = allStats.sumOf { it.deletedIdsCount }
        val totalGenerated = allStats.sumOf { it.totalGenerated }

        // Примерный размер одного ID в MongoDB (включая overhead)
        val avgIdSizeInBytes = 10 // ~6-8 символов + BSON overhead

        val deletedIdsStorageBytes = totalDeletedIds * avgIdSizeInBytes
        val deletedIdsStorageMB = deletedIdsStorageBytes / (1024 * 1024)

        return StorageUsageAnalysis(
            totalDeletedIds = totalDeletedIds,
            totalGeneratedIds = totalGenerated,
            deletedIdsStorageMB = deletedIdsStorageMB.toLong(),
            entityStats = allStats
        )
    }
}

data class EntityGenerationStats(
    val entityType: EntityType,
    val totalGenerated: Long,
    val deletedIdsCount: Int,
    val createdAt: UtcTimestamp,
    val lastUpdated: UtcTimestamp,
    val estimatedCurrentLength: Int
)

data class StorageUsageAnalysis(
    val totalDeletedIds: Int,
    val totalGeneratedIds: Long,
    val deletedIdsStorageMB: Long,
    val entityStats: List<EntityGenerationStats>
)