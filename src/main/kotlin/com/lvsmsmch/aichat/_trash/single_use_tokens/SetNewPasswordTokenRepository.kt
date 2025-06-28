package com.lvsmsmch.aichat._trash.single_use_tokens

import com.lvsmsmch.aichat.auth.database.tokens.TokenDbo
import com.lvsmsmch.aichat.auth.database.tokens.TokenRepository
import com.lvsmsmch.aichat.auth.database.tokens.Usable
import com.lvsmsmch.aichat.auth.database.tokens.UsableTokensRepository
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.createDatabaseEventsFlow
import com.lvsmsmch.aichat.utils.generateToken
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.litote.kmongo.coroutine.CoroutineCollection

@Serializable
data class SetNewPasswordTokenDbo(
    @BsonId override val token: String,
    override val createdAt: UtcTimestamp = UtcTimestamp.now(),
    override val expiresAt: UtcTimestamp = UtcTimestamp.now().addHours(24),
    override val isUsed: Boolean = false,
    val userId: String
) : TokenDbo, Usable

class SetNewPasswordTokenRepository(
    override val collection: CoroutineCollection<SetNewPasswordTokenDbo>
) : TokenRepository<SetNewPasswordTokenDbo>,
    UsableTokensRepository<SetNewPasswordTokenDbo> {


    /**
     * FLOW
     */

    val databaseEventsFlow = createDatabaseEventsFlow(collection)


    suspend fun createAndSaveNewToken(userId: String): SetNewPasswordTokenDbo {
        val token = generateToken()
        val obj = SetNewPasswordTokenDbo(
            userId = userId,
            token = token,
        )
        collection.insertOne(obj)
        return obj
    }
}