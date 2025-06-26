package com.lvsmsmch.aichat.auth.database.tokens.verification_tokens

import org.litote.kmongo.coroutine.CoroutineCollection

class PasswordResetTokenRepository(
    collection: CoroutineCollection<VerificationTokenDbo>
): VerificationTokenRepository(collection)