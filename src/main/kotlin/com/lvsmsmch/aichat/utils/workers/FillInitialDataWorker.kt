package com.lvsmsmch.aichat.utils.workers

import com.lvsmsmch.aichat._common.IdGenerator
import com.lvsmsmch.aichat._common.UsernameGenerator
import com.lvsmsmch.aichat.utils.ComplexQueryHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

fun fillInitialData(
    databaseScope: CoroutineScope,
    userRepository: com.lvsmsmch.aichat.user.database.UserRepository,
    idGenerator: IdGenerator,
    usernameGenerator: UsernameGenerator,
    complexQueryHelper: ComplexQueryHelper
): Job {
    return SupervisorJob()
}
