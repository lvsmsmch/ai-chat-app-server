package com.lvsmsmch.aichat._common.database

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.db.from
import com.lvsmsmch.aichat.db.toReportDbo
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll

@Serializable
enum class ReportEntity(val code: String) {
    User("user"), Character("character"), Review("review"), Message("message"), Comment("comment");
}

@Serializable
data class ReportDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val reportedAt: String = UtcTimestamp.now().toString(),
    val reportedBy: String,
    val entityType: String,
    val entityId: String,
    val reason: String,
    val text: String,
)

class ReportRepository {

    /** Повторная жалоба того же юзера на ту же сущность игнорируется. */
    suspend fun addReport(reportDbo: ReportDbo) {
        dbQuery {
            val exists = Tables.Reports.selectAll()
                .where {
                    (Tables.Reports.reportedBy eq reportDbo.reportedBy) and
                        (Tables.Reports.entityType eq reportDbo.entityType) and
                        (Tables.Reports.entityId eq reportDbo.entityId)
                }
                .limit(1)
                .any()
            if (!exists) Tables.Reports.insert { it.from(reportDbo) }
        }
    }

    suspend fun getReportsFor(entityId: String): List<ReportDbo> = dbQuery {
        Tables.Reports.selectAll()
            .where { Tables.Reports.entityId eq entityId }
            .orderBy(Tables.Reports.reportedAt to SortOrder.DESC)
            .map { it.toReportDbo() }
    }

    suspend fun getEntitiesWithTooManyReports(
        entityType: String,
        minReports: Int,
    ): Map<String, List<ReportDbo>> = dbQuery {
        Tables.Reports.selectAll()
            .where { Tables.Reports.entityType eq entityType }
            .map { it.toReportDbo() }
            .groupBy { it.entityId }
            .filterValues { reports -> reports.size >= minReports }
    }

    suspend fun getReportsByUser(userId: String, page: Int = 1, size: Int = 10): List<ReportDbo> =
        dbQuery {
            Tables.Reports.selectAll()
                .where { Tables.Reports.reportedBy eq userId }
                .orderBy(Tables.Reports.reportedAt to SortOrder.DESC)
                .limit(size)
                .offset(((page - 1) * size).toLong())
                .map { it.toReportDbo() }
        }

    suspend fun getReportsByEntityType(
        entityType: String,
        page: Int = 1,
        size: Int = 10,
    ): List<ReportDbo> = dbQuery {
        Tables.Reports.selectAll()
            .where { Tables.Reports.entityType eq entityType }
            .orderBy(Tables.Reports.reportedAt to SortOrder.DESC)
            .limit(size)
            .offset(((page - 1) * size).toLong())
            .map { it.toReportDbo() }
    }

    suspend fun countReportsForEntity(entityId: String): Int = dbQuery {
        Tables.Reports.selectAll().where { Tables.Reports.entityId eq entityId }.count().toInt()
    }

    suspend fun countReportsByUser(userId: String): Int = dbQuery {
        Tables.Reports.selectAll().where { Tables.Reports.reportedBy eq userId }.count().toInt()
    }

    suspend fun removeReport(reportId: String) {
        dbQuery { Tables.Reports.deleteWhere { Tables.Reports.id eq reportId } }
    }

    suspend fun removeAllReportsByUserId(userId: String) {
        dbQuery { Tables.Reports.deleteWhere { Tables.Reports.reportedBy eq userId } }
    }

    suspend fun removeAllReportsForEntity(entityId: String) {
        dbQuery { Tables.Reports.deleteWhere { Tables.Reports.entityId eq entityId } }
    }

    suspend fun removeAllReportsForEntityType(entityType: String) {
        dbQuery { Tables.Reports.deleteWhere { Tables.Reports.entityType eq entityType } }
    }
}
