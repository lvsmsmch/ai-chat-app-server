package com.lvsmsmch.aichat.database_impl.mongo_db

import com.mongodb.ConnectionString
import com.mongodb.MongoClientSettings
import com.mongodb.client.MongoClient
import com.mongodb.client.MongoClients

fun configureMongoClient(): MongoClient {
    val clientSettings = MongoClientSettings.builder()
        .applyConnectionString(ConnectionString("mongodb://localhost:27017"))
        .build()
    return MongoClients.create(clientSettings)
}