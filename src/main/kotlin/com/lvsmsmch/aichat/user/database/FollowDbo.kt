package com.lvsmsmch.aichat.user.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId

@Serializable
data class FollowDbo(
    @BsonId val id: String,
    val followedAt: UtcTimestamp = UtcTimestamp.now(),
    val followerId: String,
    val followeeId: String
)