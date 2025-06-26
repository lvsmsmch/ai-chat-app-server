package com.lvsmsmch.aichat.auth.database.attempts

import com.lvsmsmch.aichat.utils.createDatabaseEventsFlow
import org.litote.kmongo.coroutine.CoroutineCollection


class EnterLoginCodeAttemptsTracker(
    collection: CoroutineCollection<AttemptDbo>
) : BaseAttemptTracker(collection) {
    /**
     * FLOW
     */

    val databaseEventsFlow = createDatabaseEventsFlow(collection)
}