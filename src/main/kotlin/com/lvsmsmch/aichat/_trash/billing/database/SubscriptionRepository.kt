package com.lvsmsmch.aichat._trash.billing.database

import com.lvsmsmch.aichat.utils.RequiresPremiumException
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.mongodb.client.model.ReplaceOptions
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection

class SubscriptionRepository(
    private val userSubscriptionCollection: CoroutineCollection<UserSubscription>,
    private val purchaseVerificationLogCollection: CoroutineCollection<PurchaseVerificationLog>
) {

    suspend fun verifyPremium(userId: String) {
        val subscription = getUserSubscription(userId)
        val hasPremium = subscription != null && isSubscriptionActive(subscription)

        if (!hasPremium) {
            throw RequiresPremiumException()
        }
    }

    suspend fun saveSubscription(subscription: UserSubscription) {
        userSubscriptionCollection.replaceOne(
            UserSubscription::userId eq subscription.userId,
            subscription,
            ReplaceOptions().upsert(true)
        )
    }

    suspend fun getUserSubscription(userId: String): UserSubscription? {
        return userSubscriptionCollection.findOne(
            and(
                UserSubscription::userId eq userId,
                UserSubscription::isActive eq true
            )
        )
    }

    suspend fun deactivateUserSubscription(userId: String) {
        userSubscriptionCollection.updateOne(
            UserSubscription::userId eq userId,
            setValue(UserSubscription::isActive, false)
        )
    }

    suspend fun getSubscriptionByToken(purchaseToken: String): UserSubscription? {
        return userSubscriptionCollection.findOne(
            UserSubscription::purchaseToken eq purchaseToken
        )
    }

    suspend fun logVerificationAttempt(log: PurchaseVerificationLog) {
        purchaseVerificationLogCollection.insertOne(log)
    }

    suspend fun getExpiredSubscriptions(): List<UserSubscription> {
        val now = UtcTimestamp.now()

        return userSubscriptionCollection.find(
            and(
                UserSubscription::isActive eq true,
                UserSubscription::expiryTimeMillis ne null,
                UserSubscription::expiryTimeMillis lt now.epochMillis
            )
        ).toList()
    }

    private fun isSubscriptionActive(subscription: UserSubscription): Boolean {
        val now = UtcTimestamp.now()

        return subscription.isActive &&
                (subscription.expiryTimeMillis == null || subscription.expiryTimeMillis > now.epochMillis)
    }
}