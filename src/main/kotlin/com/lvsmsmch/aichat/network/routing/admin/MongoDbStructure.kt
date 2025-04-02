package com.lvsmsmch.aichat.network.routing.admin

import com.lvsmsmch.aichat.utils.loadConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.bson.Document
import org.litote.kmongo.coroutine.CoroutineCollection
import org.litote.kmongo.coroutine.CoroutineDatabase

/**
 * Data structures for serializing MongoDB information
 */
@Serializable
data class MongoDBStructureResponse(
    val databaseName: String,
    val collections: List<CollectionInfo>
)

@Serializable
data class CollectionInfo(
    val name: String,
    val documentCount: Long,
    val sampleDocument: Map<String, String>? = null,
    val indexes: List<IndexInfo>
)

@Serializable
data class IndexInfo(
    val name: String,
    val keys: Map<String, String>,
    val unique: Boolean
)

/**
 * Configure routing to expose MongoDB structure information
 */
fun Routing.configureMongoDBStructureRouting(database: CoroutineDatabase) {
    val secretKey = loadConfig().getProperty("SECRET_URL_SEGMENT_DATABASE_STRUCTURE")
    get("/admin/mongodb/$secretKey/structure") {
        try {
            val collectionNames = database.listCollectionNames()

            val collections = collectionNames.map { collectionName ->
                val collection = database.getCollection<Document>(collectionName)
                val count = collection.countDocuments()
                val sampleDoc = getSampleDocument(collection)
                val indexes = getCollectionIndexes(collection)

                CollectionInfo(
                    name = collectionName,
                    documentCount = count,
                    sampleDocument = sampleDoc,
                    indexes = indexes
                )
            }

            val response = MongoDBStructureResponse(
                databaseName = database.name,
                collections = collections
            )

            call.respond(response)
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                "Error retrieving MongoDB structure: ${e.message}"
            )
        }
    }
}

/**
 * Get a sample document from the collection
 */
private suspend fun getSampleDocument(collection: CoroutineCollection<Document>): Map<String, String>? {
    return try {
        val firstDoc = collection.find().limit(1).first()
        firstDoc?.let { mapDocumentToStringMap(it) }
    } catch (e: Exception) {
        null
    }
}

/**
 * Get all indexes from a collection
 */
private suspend fun getCollectionIndexes(collection: CoroutineCollection<Document>): List<IndexInfo> {
    return try {
        // Cast to Document explicitly
        val indexDocuments = collection.listIndexes<Document>().toList()

        // Process each document with explicit type
        indexDocuments.map { doc: Document ->
            // Get key document with explicit casting
            val keysDoc = doc["key"] as? Document
            val keys = if (keysDoc != null) {
                keysDoc.entries.associate { entry ->
                    entry.key to entry.value.toString()
                }
            } else {
                mapOf("error" to "Could not parse index keys")
            }

            IndexInfo(
                name = doc["name"]?.toString() ?: "unknown",
                keys = keys,
                unique = doc["unique"] as? Boolean ?: false
            )
        }
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * Convert a BSON Document to a Map with string values for serialization
 */
private fun mapDocumentToStringMap(doc: Document): Map<String, String> {
    val result = mutableMapOf<String, String>()

    for ((key, value) in doc) {
        when (value) {
            is Document -> result[key] = "{...}" // Nested document
            is List<*> -> result[key] = "[...]" // Array
            else -> result[key] = value?.toString() ?: "null"
        }
    }

    return result
}