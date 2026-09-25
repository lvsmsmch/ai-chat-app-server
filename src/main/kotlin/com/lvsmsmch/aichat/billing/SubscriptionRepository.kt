package com.lvsmsmch.aichat.billing

import com.lvsmsmch.aichat.db.Db.dbQuery
import com.lvsmsmch.aichat.db.Tables
import com.lvsmsmch.aichat.utils.UtcTimestamp
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update

/** Токен покупки на юзера: одна строка, перезаписывается при каждой проверке. */
class SubscriptionRepository {

    suspend fun save(userId: String, purchaseToken: String, productId: String, active: Boolean) = dbQuery {
        val now = UtcTimestamp.now().toString()
        val updated = Tables.Subscriptions.update({ Tables.Subscriptions.userId eq userId }) {
            it[Tables.Subscriptions.purchaseToken] = purchaseToken
            it[Tables.Subscriptions.productId] = productId
            it[Tables.Subscriptions.active] = active
            it[updatedAt] = now
        }
        if (updated == 0) {
            Tables.Subscriptions.insert {
                it[Tables.Subscriptions.userId] = userId
                it[Tables.Subscriptions.purchaseToken] = purchaseToken
                it[Tables.Subscriptions.productId] = productId
                it[Tables.Subscriptions.active] = active
                it[updatedAt] = now
            }
        }
        Unit
    }

    suspend fun clear(userId: String) = dbQuery {
        Tables.Subscriptions.deleteWhere { Tables.Subscriptions.userId eq userId }
        Unit
    }

    suspend fun tokenOf(userId: String): String? = dbQuery {
        Tables.Subscriptions.selectAll()
            .where { Tables.Subscriptions.userId eq userId }
            .limit(1)
            .firstOrNull()
            ?.get(Tables.Subscriptions.purchaseToken)
    }
}
