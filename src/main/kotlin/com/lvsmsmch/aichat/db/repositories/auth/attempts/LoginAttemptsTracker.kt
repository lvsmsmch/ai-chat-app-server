package com.lvsmsmch.aichat.db.repositories.auth.attempts

import org.litote.kmongo.coroutine.CoroutineCollection

class LoginAttemptsTracker(
    collection: CoroutineCollection<AttemptDbo>
) : BaseAttemptTracker(collection)