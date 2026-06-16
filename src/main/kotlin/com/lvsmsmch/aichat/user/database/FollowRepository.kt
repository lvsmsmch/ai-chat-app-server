package com.lvsmsmch.aichat.user.database

import com.lvsmsmch.aichat.utils.*
import com.mongodb.reactivestreams.client.ClientSession
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection




class FollowRepository(
    private val collection: CoroutineCollection<FollowDbo>
) {


    init {
        initializeIndexes()
    }

    private fun initializeIndexes() {
        runBlocking {
            collection.ensureIndex(ascending(FollowDbo::followerId))
            collection.ensureIndex(ascending(FollowDbo::followeeId))
            collection.ensureIndex(ascending(FollowDbo::followerId, FollowDbo::followeeId))
            collection.ensureIndex(descending(FollowDbo::followedAt))
        }
    }



    val databaseEventsFlow = createDatabaseEventsFlow(collection)


    suspend fun addConnection(session: ClientSession, followerId: String, followeeId: String) {
        if (followerId == followeeId) {
            throw IllegalArgumentException("User cannot follow themselves")
        }

        val existingConnection = collection.findOne(
            and(
                FollowDbo::followerId eq followerId,
                FollowDbo::followeeId eq followeeId
            )
        )

        if (existingConnection == null) {
            collection.insertOne(
                session,
                FollowDbo(
                    id = "${followerId}_${followeeId}",
                    followerId = followerId,
                    followeeId = followeeId
                )
            )
        }
    }


    suspend fun getFollowers(userId: String, beforeTime: UtcTimestamp?, size: Int): List<FollowDbo> {
        return collection.find(
            and(
                FollowDbo::followeeId eq userId,
                if (beforeTime != null) FollowDbo::followedAt lt beforeTime.toString() else EMPTY_BSON
            )
        ).sort(descending(FollowDbo::followedAt))
            .limit(size)
            .toList()
    }

    suspend fun getFollowings(userId: String, beforeTime: UtcTimestamp?, size: Int): List<FollowDbo> {
        return collection.find(
            and(
                FollowDbo::followerId eq userId,
                if (beforeTime != null) FollowDbo::followedAt lt beforeTime.toString() else EMPTY_BSON
            )
        ).sort(descending(FollowDbo::followedAt))
            .limit(size)
            .toList()
    }

    suspend fun getAllFollowerIds(session: ClientSession, userId: String): List<String> {
        return collection.find(
            session,
            FollowDbo::followeeId eq userId
        ).toList()
            .map { it.followerId }
    }

    suspend fun getAllFollowingIds(session: ClientSession, userId: String): List<String> {
        return collection.find(
            session,
            FollowDbo::followerId eq userId
        ).toList()
            .map { it.followeeId }
    }

    suspend fun countFollowers(userId: String): Int {
        return collection.countDocuments(FollowDbo::followeeId eq userId).toInt()
    }

    suspend fun countFollowings(userId: String): Int {
        return collection.countDocuments(FollowDbo::followerId eq userId).toInt()
    }

    suspend fun doesConnectionExist(followerId: String, followeeId: String): Boolean {
        return collection.findOne(
            and(
                FollowDbo::followerId eq followerId,
                FollowDbo::followeeId eq followeeId
            )
        ) != null
    }



    suspend fun removeConnection(session: ClientSession, followerId: String, followeeId: String) {
        collection.deleteOne(
            session,
            and(
                FollowDbo::followerId eq followerId,
                FollowDbo::followeeId eq followeeId
            )
        )
    }

    suspend fun removeAllConnectionsContainingUserId(session: ClientSession, userId: String) {
        collection.deleteMany(
            session,
            or(
                FollowDbo::followerId eq userId,
                FollowDbo::followeeId eq userId
            )
        )
    }
}