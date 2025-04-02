package com.lvsmsmch.aichat.utils

import org.litote.kmongo.coroutine.CoroutineDatabase
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo

fun configureDatabase(): CoroutineDatabase {
    val client = KMongo.createClient("mongodb://localhost:27017").coroutine
    return client.getDatabase("ai_chat_app_database")
}