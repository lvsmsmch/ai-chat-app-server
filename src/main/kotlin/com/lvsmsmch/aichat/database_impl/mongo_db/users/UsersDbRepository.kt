package com.lvsmsmch.aichat.database_impl.mongo_db.users

import org.litote.kmongo.*
import com.mongodb.client.MongoDatabase

class UsersDbRepository(database: MongoDatabase) {
    private val collection = database.getCollection<UserDbo>("users")

    suspend fun addUser(user: UserDbo): String {
        val result = collection.insertOne(user)
        return result.insertedId?.asString()?.value ?: "Unknown ID"
    }

    suspend fun getUserById(userId: String): UserDbo? {
        return collection.findOneById(userId)
    }

    suspend fun updateUser(userId: String, user: UserDbo): Boolean {
        val result = collection.updateOneById(userId, user)
        return result.modifiedCount > 0
    }

    suspend fun deleteUser(userId: String): Boolean {
        val result = collection.deleteOneById(userId)
        return result.deletedCount > 0
    }

    suspend fun getAllUsers(): List<UserDbo> {
        return collection.find().toList()
    }
}
