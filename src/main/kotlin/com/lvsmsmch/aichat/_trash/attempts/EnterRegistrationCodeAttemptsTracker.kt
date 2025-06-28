package com.lvsmsmch.aichat._trash.attempts

import com.lvsmsmch.aichat.utils.createDatabaseEventsFlow
import org.litote.kmongo.coroutine.CoroutineCollection


class EnterRegistrationCodeAttemptsTracker(
    collection: CoroutineCollection<AttemptDbo>
) : BaseAttemptTracker(collection) {
    /**
     * FLOW
     */

    val databaseEventsFlow = createDatabaseEventsFlow(collection)
}