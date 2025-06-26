package com.lvsmsmch.aichat._common.database

import com.lvsmsmch.aichat.utils.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection


@Serializable enum class ReportEntity(val code: String) {
    User("user"), Character("character"), Review("review");
}

@Serializable
data class ReportDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val reportedAt: UtcTimestamp = UtcTimestamp.now(),
    val reportedBy: String,
    val entityType: String,
    val entityId: String,
    val reason: String,
    val text: String,
)

class ReportRepository(
    private val collection: CoroutineCollection<ReportDbo>
) {

    /**
     * Initialize indexes for the collection
     */

    init {
        initializeIndexes()
    }

    private fun initializeIndexes() {
        runBlocking {
            collection.ensureIndex(ascending(ReportDbo::entityId))
            collection.ensureIndex(ascending(ReportDbo::entityType))
            collection.ensureIndex(ascending(ReportDbo::reportedBy))
            collection.ensureIndex(ascending(ReportDbo::reportedAt))
            collection.ensureIndex(ascending(ReportDbo::entityType, ReportDbo::entityId))
        }
    }


    /**
     * FLOW
     */

    val databaseEventsFlow = createDatabaseEventsFlow(collection)


    /**
     * CREATE
     */
    suspend fun addReport(reportDbo: ReportDbo) {
        // Проверяем, что пользователь еще не жаловался на эту сущность
        val existingReport = collection.findOne(
            and(
                ReportDbo::reportedBy eq reportDbo.reportedBy,
                ReportDbo::entityType eq reportDbo.entityType,
                ReportDbo::entityId eq reportDbo.entityId
            )
        )

        if (existingReport == null) {
            collection.insertOne(reportDbo)
        }
    }

    /**
     * READ
     */

    suspend fun getReportsFor(entityId: String): List<ReportDbo> {
        return collection.find(ReportDbo::entityId eq entityId)
            .sort(descending(ReportDbo::reportedAt))
            .toList()
    }

    suspend fun getEntitiesWithTooManyReports(
        entityType: String,
        minReports: Int
    ): Map<String, List<ReportDbo>> {
        val allReports = collection.find(ReportDbo::entityType eq entityType).toList()

        return allReports
            .groupBy { it.entityId }
            .filterValues { reports -> reports.size >= minReports }
    }

    suspend fun getReportsByUser(userId: String, page: Int = 1, size: Int = 10): List<ReportDbo> {
        val skip = (page - 1) * size
        return collection.find(ReportDbo::reportedBy eq userId)
            .sort(descending(ReportDbo::reportedAt))
            .skip(skip)
            .limit(size)
            .toList()
    }

    suspend fun getReportsByEntityType(
        entityType: String,
        page: Int = 1,
        size: Int = 10
    ): List<ReportDbo> {
        val skip = (page - 1) * size
        return collection.find(ReportDbo::entityType eq entityType)
            .sort(descending(ReportDbo::reportedAt))
            .skip(skip)
            .limit(size)
            .toList()
    }

    suspend fun countReportsForEntity(entityId: String): Int {
        return collection.countDocuments(ReportDbo::entityId eq entityId).toInt()
    }

    suspend fun countReportsByUser(userId: String): Int {
        return collection.countDocuments(ReportDbo::reportedBy eq userId).toInt()
    }

    /**
     * UPDATE
     */


    /**
     * DELETE
     */
    suspend fun removeReport(reportId: String) {
        collection.deleteOne(ReportDbo::id eq reportId)
    }

    suspend fun removeAllReportsByUserId(userId: String) {
        // Удаляем все жалобы, поданные этим пользователем
        collection.deleteMany(ReportDbo::reportedBy eq userId)
    }

    suspend fun removeAllReportsForEntity(entityId: String) {
        // Удаляем все жалобы на конкретную сущность
        collection.deleteMany(ReportDbo::entityId eq entityId)
    }

    suspend fun removeAllReportsForEntityType(entityType: String) {
        // Удаляем все жалобы определенного типа сущности
        collection.deleteMany(ReportDbo::entityType eq entityType)
    }
}