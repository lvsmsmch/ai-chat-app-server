package com.lvsmsmch.aichat.auth.database.verification_codes

import com.lvsmsmch.aichat.utils.createDatabaseEventsFlow
import org.litote.kmongo.coroutine.CoroutineCollection

class LoginCodesRepository(
    collection: CoroutineCollection<VerificationCodeDbo>
) : BaseCodesRepository(collection) {
    /**
     * FLOW
     */

    val databaseEventsFlow = createDatabaseEventsFlow(collection)
}