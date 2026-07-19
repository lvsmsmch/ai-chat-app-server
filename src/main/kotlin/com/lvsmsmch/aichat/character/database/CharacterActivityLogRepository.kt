package com.lvsmsmch.aichat.character.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import com.lvsmsmch.aichat.utils.createDatabaseEventsFlow
import com.mongodb.client.model.Aggregates.count
import com.mongodb.reactivestreams.client.ClientSession
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId
import org.litote.kmongo.*
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.coroutine.aggregate

@Serializable
data class CharacterActivityLogDbo(
    @BsonId val id: String = ObjectId().toHexString(),
    val timestamp: String = UtcTimestamp.now().toString(),
    val characterId: String,
    val activityType: Int,
    val userId: String,
)

enum class ActivityType(val code: Int) {
    CHAT_CREATED(0),
    MESSAGE_SENT(1),
    REVIEW_ADDED(2),
    COMMENT_ADDED(3),
}

class CharacterActivityLogRepository(
    private val collection: CoroutineCollection<CharacterActivityLogDbo>
) {



    val databaseEventsFlow = createDatabaseEventsFlow(collection)

    suspend fun logActivity(
        session: ClientSession,
        activityType: ActivityType,
        characterId: String,
        userId: String
    ) {
        val log = CharacterActivityLogDbo(
            characterId = characterId,
            activityType = activityType.code,
            userId = userId
        )
        collection.insertOne(session, log)
    }




    suspend fun getActivity(
        activityType: ActivityType,
        characterId: String,
        since: UtcTimestamp
    ): Int {
        return collection.countDocuments(
            and(
                CharacterActivityLogDbo::characterId eq characterId,
                CharacterActivityLogDbo::activityType eq activityType.code,
                CharacterActivityLogDbo::timestamp gte since.toString()
            )
        ).toInt()
    }

    suspend fun getUniqueUsersForActivity(
        activityType: ActivityType,
        characterId: String,
        since: UtcTimestamp
    ): Int {
        val result = collection.aggregate<CountResult>(
            match(
                and(
                    CharacterActivityLogDbo::characterId eq characterId,
                    CharacterActivityLogDbo::activityType eq activityType.code,
                    CharacterActivityLogDbo::timestamp gte since.toString()
                )
            ),
            group(
                CharacterActivityLogDbo::userId,
                CountResult::count sum 1
            ),
            count()
        ).first()

        return result?.count ?: 0
    }

    data class CountResult(
        val count: Int
    )





}