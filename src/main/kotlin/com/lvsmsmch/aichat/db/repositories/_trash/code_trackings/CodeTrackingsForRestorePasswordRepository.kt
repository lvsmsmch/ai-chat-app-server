package com.lvsmsmch.aichat.db.repositories._trash.code_trackings

import org.litote.kmongo.coroutine.CoroutineCollection

class CodeTrackingsForRestorePasswordRepository(
    collection: CoroutineCollection<CodeTrackingDbo>
): CodeTrackingsBaseRepository(collection)