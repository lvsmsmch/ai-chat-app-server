package com.lvsmsmch.aichat._trash.billing.database

import com.lvsmsmch.aichat.utils.UtcTimestamp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.bson.codecs.pojo.annotations.BsonId
import org.bson.types.ObjectId

@Serializable
data class PurchaseVerificationLog(
    @BsonId @SerialName("_id") val id: String = ObjectId().toHexString(),
    @SerialName("userId") val userId: String,
    @SerialName("purchaseToken") val purchaseToken: String,
    @SerialName("verificationResult") val verificationResult: String, // success, failed, invalid
    @SerialName("googleResponse") val googleResponse: String? = null,
    @SerialName("errorMessage") val errorMessage: String? = null,
    @SerialName("timestamp") val timestamp: UtcTimestamp = UtcTimestamp.now(),
)
