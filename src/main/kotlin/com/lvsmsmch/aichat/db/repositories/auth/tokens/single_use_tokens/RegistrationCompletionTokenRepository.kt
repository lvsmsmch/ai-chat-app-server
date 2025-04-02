package com.lvsmsmch.aichat.db.repositories.auth.tokens.single_use_tokens

import com.lvsmsmch.aichat.db.repositories.auth.tokens.TokenDbo
import com.lvsmsmch.aichat.db.repositories.auth.tokens.TokenVerifier
import com.lvsmsmch.aichat.utils.UtcTimestamp
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
) : TokenVerifier<RegistrationCompletionTokenDbo>, UsableTokensRepository<RegistrationCompletionTokenDbo> {

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