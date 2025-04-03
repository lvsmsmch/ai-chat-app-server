package com.lvsmsmch.aichat.db.repositories.content

import com.lvsmsmch.aichat.network.routing.auth.login.OAuthProvider
import com.lvsmsmch.aichat.network.routing.auth.login.OAuthUserData
import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.generateUniqueUsername
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.eq
import org.litote.kmongo.setValue

@Serializable
data class UserDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val createdAt: UtcTimestamp = UtcTimestamp.now(),
    val email: String = "",
    val hashedPassword: String = "",
    val googleOauthId: String = "",
    val facebookOauthId: String = "",
    val username: String = generateUniqueUsername(),
    val name: String = "",
    val profilePicUrl: String = "",
)

class UserRepository(
    private val collection: CoroutineCollection<UserDbo>
) {

    suspend fun getUserById(userId: String): UserDbo? {
        return collection.findOneById(userId)
    }

    suspend fun findUserByEmail(email: String): UserDbo? {
        return collection.findOne(UserDbo::email eq email)
    }

    suspend fun getOrCreateUserByOAuthId(
        oauthProvider: OAuthProvider,
        oauthUserData: OAuthUserData,
    ): UserDbo {
        val filter = when (oauthProvider) {
            OAuthProvider.GOOGLE -> UserDbo::googleOauthId eq oauthUserData.id
            OAuthProvider.FACEBOOK -> UserDbo::facebookOauthId eq oauthUserData.id
        }

        val existingUser = collection.findOne(filter)
        if (existingUser != null) {
            return existingUser
        }

        return UserDbo(
            googleOauthId = if (oauthProvider == OAuthProvider.GOOGLE) oauthUserData.id else "",
            facebookOauthId = if (oauthProvider == OAuthProvider.FACEBOOK) oauthUserData.id else "",
            name = oauthUserData.name ?: "",
            profilePicUrl = oauthUserData.profilePictureUrl ?: ""
        ).also { addUser(it) }
    }

    suspend fun findUserByGoogleOauthId(oauthId: String): UserDbo? {
        return collection.findOne(UserDbo::googleOauthId eq oauthId)
    }

    suspend fun findUserByFacebookOauthId(oauthId: String): UserDbo? {
        return collection.findOne(UserDbo::facebookOauthId eq oauthId)
    }

    suspend fun addUser(userDbo: UserDbo) {
        collection.insertOne(userDbo)
    }

    suspend fun updateUsername(userId: String, username: String): Boolean {
        val updateResult = collection.updateOneById(
            userId,
            setValue(UserDbo::username, username)
        )
        return updateResult.modifiedCount > 0
    }

    suspend fun updateName(userId: String, name: String): Boolean {
        val updateResult = collection.updateOneById(
            userId,
            setValue(UserDbo::name, name)
        )
        return updateResult.modifiedCount > 0
    }

    suspend fun updateUserProfilePicture(userId: String, profilePictureUrl: String): Boolean {
        val updateResult = collection.updateOneById(
            userId,
            setValue(UserDbo::profilePicUrl, profilePictureUrl)
        )
        return updateResult.modifiedCount > 0
    }

    suspend fun updatePassword(userId: String, hashedPassword: String): Boolean {
        val updateResult = collection.updateOneById(
            userId,
            setValue(UserDbo::hashedPassword, hashedPassword)
        )
        return updateResult.modifiedCount > 0
    }

    suspend fun deleteUser(userId: String): Boolean {
        val deleteResult = collection.deleteOneById(userId)
        return deleteResult.deletedCount > 0
    }
}