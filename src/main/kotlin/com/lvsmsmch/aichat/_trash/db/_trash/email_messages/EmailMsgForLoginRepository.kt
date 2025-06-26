package com.lvsmsmch.aichat._trash.db._trash.email_messages

import com.lvsmsmch.aichat.db.repositories._trash.email_messages._base.BaseEmailMsgWithCodeRepository
import com.lvsmsmch.aichat.db.repositories._trash.email_messages._base.EmailMsgWithCodeDbo
import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.coroutine.CoroutineCollection

@Serializable
data class EmailMsgForLoginDbo(
    @BsonId override val id: String = ObjectId().toHexString(),
    override val timestamp: UtcTimestamp = UtcTimestamp.now(),
    override val email: String,
    override val code: Int,
    override val codeExpiresAt: UtcTimestamp = UtcTimestamp.now().addHours(24),
    override val isCodeUsed: Boolean = false,
): EmailMsgWithCodeDbo

class EmailMsgForLoginRepository(
    collection: CoroutineCollection<EmailMsgForLoginDbo>
) : BaseEmailMsgWithCodeRepository<EmailMsgForLoginDbo>(collection) {

    override fun createEmailMsg(email: String, code: Int): EmailMsgForLoginDbo {
        return EmailMsgForLoginDbo(
            email = email,
            code = code
        )
    }
}