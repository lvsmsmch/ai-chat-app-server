package com.lvsmsmch.aichat.db.repositories.auth.verification_codes

import org.litote.kmongo.coroutine.CoroutineCollection

class LoginCodesRepository(
    collection: CoroutineCollection<VerificationCodeDbo>
) : BaseCodesRepository(collection)