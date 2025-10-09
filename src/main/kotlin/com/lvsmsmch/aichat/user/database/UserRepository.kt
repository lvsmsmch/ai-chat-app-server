package com.lvsmsmch.aichat.user.database

import com.lvsmsmch.aichat.chat.network.LimitsResponse
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

    suspend fun getLimits(userId: String): LimitsResponse {
        logger.debug(">>> getLimits $userId")
        val user = getUserById(userId) ?: throw UserNotFoundException()

        val dailyLimit = if (user.hasSubscription) DAILY_LIMIT_MESSAGES_PREMIUM else DAILY_LIMIT_MESSAGES_REGULAR
        val hourlyLimit = if (user.hasSubscription) HOURLY_LIMIT_MESSAGES_PREMIUM else HOURLY_LIMIT_MESSAGES_REGULAR

        val now = java.time.LocalDateTime.now()

        val limitUntil = if (user.extraFreeMessagesCount > 0) {
            null
        } else if (user.dailyMessageCount >= dailyLimit) {
            val nextDay = now.plusDays(1).withHour(0).withMinute(0).withSecond(0).withNano(0)
            UtcTimestamp(nextDay.toInstant(java.time.ZoneOffset.UTC)).toString()
        } else if (user.hourlyMessageCount >= hourlyLimit) {
            val nextHour = now.plusHours(1).withMinute(0).withSecond(0).withNano(0)
            UtcTimestamp(nextHour.toInstant(java.time.ZoneOffset.UTC)).toString()
        } else {
            null
        }

        val limitsResponse = LimitsResponse(
            limitUntil = limitUntil,
            hourlyUsed = user.hourlyMessageCount,
            hourlyLimit = hourlyLimit,
            dailyUsed = user.dailyMessageCount,
            dailyLimit = dailyLimit,
            extraLeft = user.extraFreeMessagesCount,
            extraAmountForReward = EXTRA_AMOUNT_FOR_REWARD
        )

        logger.debug("Limits: $limitsResponse")

        return limitsResponse
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
        profilePictureUrlThumbnail: String? = null,
        removePicture: Boolean? = null,
        hashedPassword: String? = null,
    ) {
        collection.findOneById(userId) ?: return
        val updates = mutableListOf<Bson>()
        email?.let { updates.add(setValue(UserDbo::email, it)) }
        username?.let { updates.add(setValue(UserDbo::username, it)) }
        name?.let { updates.add(setValue(UserDbo::name, it)) }
        bio?.let { updates.add(setValue(UserDbo::bio, it)) }
        profilePictureUrl?.let { updates.add(setValue(UserDbo::profilePictureUrl, it)) }
        profilePictureUrlThumbnail?.let { updates.add(setValue(UserDbo::profilePictureUrlThumbnail, it)) }
        hashedPassword?.let { updates.add(setValue(UserDbo::hashedPassword, it)) }
        removePicture?.let {
            if (it) {
                updates.add(setValue(UserDbo::profilePictureUrl, null))
                updates.add(setValue(UserDbo::profilePictureUrlThumbnail, null))
            }
        }
        if (updates.isEmpty()) return // Nothing to update
        collection.updateOneById(session, userId, combine(*updates.toTypedArray()))
    }

    suspend fun linkGoogleToUser(
        userId: String,
        googleId: String,
        email: String? = null,
    ) {
        collection.findOneById(userId) ?: return
        collection.updateOneById(
            userId, combine(
                Updates.set(UserDbo::accountType.name, AccountType.REGISTERED),
                Updates.set(UserDbo::deviceId.name, null),
                Updates.set(UserDbo::googleOauthId.name, googleId),
                Updates.set(UserDbo::email.name, email),
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

    suspend fun notifyCharacterMessageWasSent(session: ClientSession, userId: String) {
        logger.debug("> incrementMessageCounters for ${userId}")
        logger.debug("before, extra count for ${userId} : ${getUserById(userId)?.extraFreeMessagesCount}")
        logger.debug("before, hourly count for ${userId} : ${getUserById(userId)?.hourlyMessageCount}")

        val userDbo = getUserById(session, userId) ?: return
        val messagesUpdateBson = if (userDbo.extraFreeMessagesCount > 0) {
            inc(UserDbo::extraFreeMessagesCount, -1)
        } else {
            combine(
                inc(UserDbo::hourlyMessageCount, 1),
                inc(UserDbo::dailyMessageCount, 1),
            )
        }

        collection.updateOneById(
            session,
            userId,
            combine(
                messagesUpdateBson,
                inc(UserDbo::totalMessagesCount, 1),
                setValue(UserDbo::lastActiveAt, UtcTimestamp.now().toString())
            )
        )

        logger.debug("after, extra count for ${userId} : ${getUserById(userId)?.extraFreeMessagesCount}")
        logger.debug("after,  hourly count for ${userId} : ${getUserById(userId)?.hourlyMessageCount}")
    }

    suspend fun notifyChatWasCreated(session: ClientSession, userId: String) {
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

    suspend fun addUserLimitsAfterRewardedWasWatched(userId: String) {
        collection.updateOneById(
            userId,
            combine(
                inc(UserDbo::extraFreeMessagesCount, EXTRA_AMOUNT_FOR_REWARD)
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
        const val DAILY_LIMIT_MESSAGES_REGULAR = 100
        const val HOURLY_LIMIT_MESSAGES_REGULAR = 50
        const val DAILY_LIMIT_MESSAGES_PREMIUM = 1000
        const val HOURLY_LIMIT_MESSAGES_PREMIUM = 500
        const val EXTRA_AMOUNT_FOR_REWARD = 25
    }
}