package com.lvsmsmch.aichat.db.repositories.auth.tokens.verification_tokens

import org.litote.kmongo.coroutine.CoroutineCollection

class PasswordResetTokenRepository(
    collection: CoroutineCollection<VerificationTokenDbo>
): VerificationTokenRepository(collection)