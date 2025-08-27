package com.lvsmsmch.aichat.user.database

import com.lvsmsmch.aichat.auth.network.SubscriptionStatusRequest
import com.lvsmsmch.aichat.utils.*
import com.mongodb.client.model.Updates
import com.mongodb.reactivestreams.client.ClientSession
import kotlinx.coroutines.runBlocking
import org.bson.conversions.Bson
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection





class UserRepository(
    private val collection: CoroutineCollection<UserDbo>
) {

    /**
     * Initialize indexes for the collection
     */

    init {
        initializeIndexes()
    }


    private fun initializeIndexes() {
        runBlocking {
            collection.ensureIndex(ascending(UserDbo::email))
            collection.ensureIndex(ascending(UserDbo::googleOauthId))
            collection.ensureIndex(ascending(UserDbo::facebookOauthId))
            collection.ensureIndex(ascending(UserDbo::username))
            collection.ensureIndex(ascending(UserDbo::lastActiveAt))
            collection.ensureIndex(ascending(UserDbo::deviceId))
        }
    }


    /**
     * FLOW
     */

    val databaseEventsFlow = createDatabaseEventsFlow(collection)


    /**
     * CREATE
     */

    suspend fun addUser(userDbo: UserDbo) {
        collection.insertOne(userDbo)
    }

    suspend fun addUser(session: ClientSession, userDbo: UserDbo) {
        collection.insertOne(session, userDbo)
    }

    /**
     * READ
     */

    suspend fun getUserById(userId: String): UserDbo? {
        return collection.findOneById(userId)
    }

    suspend fun getUserById(session: ClientSession, userId: String): UserDbo? {
        return collection.findOneById(userId, session)
    }


    suspend fun findByUsername(username: String): UserDbo? {
        return collection.findOne(UserDbo::username eq username)
    }

    suspend fun findUserByEmail(email: String): UserDbo? {
        return collection.findOne(UserDbo::email eq email)
    }

    suspend fun findByGoogleId(googleId: String): UserDbo? {
        return collection.findOne(UserDbo::googleOauthId eq googleId)
    }

    suspend fun findByDeviceId(deviceId: String): UserDbo? {
        return collection.findOne(UserDbo::deviceId eq deviceId)
    }

    suspend fun getActiveUsersSince(since: UtcTimestamp): List<UserDbo> {
        return collection.find(
            UserDbo::lastActiveAt gte since.toString()
        ).toList()
    }

    suspend fun getHasLimitUntil(userId: String): UtcTimestamp? {
        logger.debug(">>> getHasLimitUntil $userId")
        val user = getUserById(userId) ?: return null

        val dailyLimit = if (user.hasSubscription) DAILY_LIMIT_MESSAGES_PREMIUM else DAILY_LIMIT_MESSAGES_REGULAR
        val hourlyLimit = if (user.hasSubscription) HOURLY_LIMIT_MESSAGES_PREMIUM else HOURLY_LIMIT_MESSAGES_REGULAR

        val now = java.time.LocalDateTime.now()

        logger.debug(">>> daily ${user.dailyMessageCount}/$dailyLimit")
        logger.debug(">>> hourly ${user.hourlyMessageCount}/$hourlyLimit")

        if (user.dailyMessageCount >= dailyLimit) {
            val nextDay = now.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
            return UtcTimestamp(nextDay.toInstant(java.time.ZoneOffset.UTC))
        }

        if (user.hourlyMessageCount >= hourlyLimit) {
            val nextHour = now.plusHours(1).withMinute(0).withSecond(0).withNano(0)
            return UtcTimestamp(nextHour.toInstant(java.time.ZoneOffset.UTC))
        }

        return null
    }

    /**
     * UPDATE
     */
    suspend fun updateUser(
        session: ClientSession,
        userId: String,
        email: String? = null,
        username: String? = null,
        name: String? = null,
        bio: String? = null,
        profilePictureUrl: String? = null,
        hashedPassword: String? = null,
    ) {
        collection.findOneById(userId) ?: return
        val updates = mutableListOf<Bson>()
        email?.let { updates.add(setValue(UserDbo::email, it)) }
        username?.let { updates.add(setValue(UserDbo::username, it)) }
        name?.let { updates.add(setValue(UserDbo::name, it)) }
        bio?.let { updates.add(setValue(UserDbo::bio, it)) }
        profilePictureUrl?.let { updates.add(setValue(UserDbo::profilePictureUrl, it)) }
        hashedPassword?.let { updates.add(setValue(UserDbo::hashedPassword, it)) }
        if (updates.isEmpty()) return // Nothing to update
        collection.updateOneById(session, userId, combine(*updates.toTypedArray()))
    }

    suspend fun linkGoogleToUser(
        userId: String,
        googleId: String,
        email: String? = null,
        name: String? = null,
        profilePictureUrl: String? = null,
    ) {
        collection.findOneById(userId) ?: return
        collection.updateOneById(
            userId, combine(
                Updates.set(UserDbo::accountType.name, AccountType.REGISTERED),
                Updates.set(UserDbo::deviceId.name, null),
                Updates.set(UserDbo::googleOauthId.name, googleId),
                Updates.set(UserDbo::email.name, email),
                Updates.set(UserDbo::name.name, name),
                Updates.set(UserDbo::profilePictureUrl.name, profilePictureUrl),
            )
        )
    }

    suspend fun updateSubscriptionStatus(userId: String, hasSubscription: Boolean) {
        collection.updateOneById(
            userId, combine(
                Updates.set(UserDbo::hasSubscription.name, hasSubscription),
            )
        )
    }

    suspend fun incrementFollowingCount(session: ClientSession, userId: String, increment: Int) {
        collection.updateOneById(
            session,
            userId,
            inc(UserDbo::followingCount, increment)
        )
    }

    suspend fun incrementFollowerCount(session: ClientSession, userId: String, increment: Int) {
        collection.updateOneById(
            session,
            userId,
            inc(UserDbo::followerCount, increment)
        )
    }

    suspend fun incrementFollowerCountForUsers(session: ClientSession, userIds: List<String>, increment: Int) {
        collection.updateMany(
            session,
            UserDbo::id `in` userIds,
            inc(UserDbo::followerCount, increment)
        )
    }

    suspend fun incrementFollowingCountForUsers(session: ClientSession, userIds: List<String>, increment: Int) {
        collection.updateMany(
            session,
            UserDbo::id `in` userIds,  // ← KMongo синтаксис
            inc(UserDbo::followingCount, increment)
        )
    }

    suspend fun incrementPublicCharacterCount(session: ClientSession, userId: String, increment: Int) {
        collection.updateOneById(
            session,
            userId,
            inc(UserDbo::publicCharacterCount, increment)
        )
    }

    suspend fun incrementPrivateCharacterCount(session: ClientSession, userId: String, increment: Int) {
        collection.updateOneById(
            session,
            userId,
            inc(UserDbo::privateCharacterCount, increment)
        )
    }

    suspend fun resetHourlyCountersForAllUsers() {
        collection.updateMany(
            filter = UserDbo::hourlyMessageCount gt 0,
            update = setValue(UserDbo::hourlyMessageCount, 0)
        )
    }

    suspend fun resetDailyCountersForAllUsers() {
        collection.updateMany(
            filter = UserDbo::dailyMessageCount gt 0,
            update = setValue(UserDbo::dailyMessageCount, 0)
        )
    }

    suspend fun incrementMessageCounters(session: ClientSession, userId: String) {
        logger.debug("incrementMessageCounters for ${userId}")
        logger.debug("before, hourly count for ${userId} : ${getUserById(userId)?.hourlyMessageCount}")
        collection.updateOneById(
            session,
            userId,
            combine(
                inc(UserDbo::hourlyMessageCount, 1),
                inc(UserDbo::dailyMessageCount, 1),
                inc(UserDbo::totalMessagesCount, 1),
                setValue(UserDbo::lastActiveAt, UtcTimestamp.now().toString())
            )
        )
        logger.debug("after,  hourly count for ${userId} : ${getUserById(userId)?.hourlyMessageCount}")
    }

    suspend fun incrementChatCounters(session: ClientSession, userId: String) {
        logger.debug("incrementChatCounters by 1")
        collection.updateOneById(
            session,
            userId,
            combine(
                inc(UserDbo::totalChatsCount, 1),
                setValue(UserDbo::lastActiveAt, UtcTimestamp.now().toString())
            )
        )
    }



    /**
     * DELETE
     */
    suspend fun deleteUser(session: ClientSession, userId: String): Boolean {
        val deleteResult = collection.deleteOneById(session, userId)
        return deleteResult.deletedCount > 0
    }


    companion object {
        const val DAILY_LIMIT_MESSAGES_REGULAR = 60
        const val HOURLY_LIMIT_MESSAGES_REGULAR = 30
        const val DAILY_LIMIT_MESSAGES_PREMIUM = 200
        const val HOURLY_LIMIT_MESSAGES_PREMIUM = 100
    }
}