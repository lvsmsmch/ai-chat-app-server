package com.lvsmsmch.aichat.db.repositories.auth.tokens.single_use_tokens

import com.lvsmsmch.aichat.db.repositories.auth.tokens.TokenDbo
import com.lvsmsmch.aichat.db.repositories.auth.tokens.VerifiableToken
import com.lvsmsmch.aichat.utils.UtcTimestamp
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
) : VerifiableToken<SetNewPasswordTokenDbo>, UsableTokensRepository<SetNewPasswordTokenDbo> {

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