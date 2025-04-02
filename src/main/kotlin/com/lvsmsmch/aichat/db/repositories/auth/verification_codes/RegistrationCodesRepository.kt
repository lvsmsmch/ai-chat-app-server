package com.lvsmsmch.aichat.db.repositories.auth.verification_codes

import org.litote.kmongo.coroutine.CoroutineCollection

class RegistrationCodesRepository(
    collection: CoroutineCollection<VerificationCodeDbo>
) : BaseCodesRepository(collection)