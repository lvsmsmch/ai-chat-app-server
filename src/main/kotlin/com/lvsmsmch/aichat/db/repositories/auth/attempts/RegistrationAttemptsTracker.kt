package com.lvsmsmch.aichat.db.repositories.auth.attempts

import org.litote.kmongo.coroutine.CoroutineCollection

class RegistrationAttemptsTracker(
    collection: CoroutineCollection<AttemptDbo>
) : BaseAttemptTracker(collection)