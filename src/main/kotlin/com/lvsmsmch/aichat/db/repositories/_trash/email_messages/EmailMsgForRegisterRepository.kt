package com.lvsmsmch.aichat.db.repositories._trash.email_messages

import com.lvsmsmch.aichat.db.repositories._trash.email_messages._base.BaseEmailMsgWithTokenRepository
import com.lvsmsmch.aichat.db.repositories._trash.email_messages._base.EmailMsgWithTokenDbo
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.coroutine.CoroutineCollection

@Serializable
data class EmailMsgForRegisterDbo(
    @BsonId override val id: String = ObjectId().toHexString(),
    override val timestamp: UtcTimestamp = UtcTimestamp.now(),
    override val email: String,
    override val token: String,
    override val tokenExpiresAt: UtcTimestamp = UtcTimestamp.now().addHours(24),
    override val isTokenUsed: Boolean = false,
): EmailMsgWithTokenDbo

class EmailMsgForRegisterRepository(
    collection: CoroutineCollection<EmailMsgForRegisterDbo>
) : BaseEmailMsgWithTokenRepository<EmailMsgForRegisterDbo>(collection) {

    // Implement the factory method
    override fun createEmailMsg(email: String, token: String): EmailMsgForRegisterDbo {
        return EmailMsgForRegisterDbo(
            email = email,
            token = token
        )
    }

    // Any password-reset-specific functionality would go here
}