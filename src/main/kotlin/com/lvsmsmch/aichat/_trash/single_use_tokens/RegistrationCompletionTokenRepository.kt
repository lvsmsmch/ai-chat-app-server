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
data class RegistrationCompletionTokenDbo(
    @BsonId override val token: String,
    override val createdAt: UtcTimestamp = UtcTimestamp.now(),
    override val expiresAt: UtcTimestamp = UtcTimestamp.now().addHours(24),
    override val isUsed: Boolean = false,
    val email: String,
) : TokenDbo, Usable

class RegistrationCompletionTokenRepository(
    override val collection: CoroutineCollection<RegistrationCompletionTokenDbo>
) : TokenRepository<RegistrationCompletionTokenDbo>,
    UsableTokensRepository<RegistrationCompletionTokenDbo> {


    /**
     * FLOW
     */

    val databaseEventsFlow = createDatabaseEventsFlow(collection)


    suspend fun createAndSaveNewToken(email: String): RegistrationCompletionTokenDbo {
        val token = generateToken()
        val obj = RegistrationCompletionTokenDbo(
            email = email,
            token = token,
        )
        collection.insertOne(obj)
        return obj
    }
}