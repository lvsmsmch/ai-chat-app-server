package com.lvsmsmch.aichat._trash.db._trash.code_trackings

import org.litote.kmongo.coroutine.CoroutineCollection

class CodeTrackingsForRestorePasswordRepository(
    collection: CoroutineCollection<CodeTrackingDbo>
): CodeTrackingsBaseRepository(collection)