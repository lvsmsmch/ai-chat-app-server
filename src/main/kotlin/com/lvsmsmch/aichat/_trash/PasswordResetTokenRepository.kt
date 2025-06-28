package com.lvsmsmch.aichat._trash

import org.litote.kmongo.coroutine.CoroutineCollection

class PasswordResetTokenRepository(
    collection: CoroutineCollection<VerificationTokenDbo>
): VerificationTokenRepository(collection)