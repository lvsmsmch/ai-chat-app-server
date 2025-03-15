package com.lvsmsmch.aichat.network.utils

import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlinx.serialization.json.Json

import io.ktor.http.*
import io.ktor.server.response.*
import kotlinx.serialization.SerializationException
import java.io.IOException

suspend inline fun <reified T : Any> ApplicationCall.parseRequest(): T? {
    val contentType = request.contentType().withoutParameters()

    return try {
        when {
            contentType.match("multipart/form-data") -> parseMultipartData<T>()
            contentType.match("application/json") -> parseJson<T>()
            else -> {
                respond(HttpStatusCode.UnsupportedMediaType, "Unsupported content type: $contentType")
                null
            }
        }
    } catch (e: SerializationException) { // JSON parsing error
        respond(HttpStatusCode.BadRequest, "Invalid JSON format")
        null
    } catch (e: IOException) { // File reading error
        respond(HttpStatusCode.InternalServerError, "File processing error")
        null
    } catch (e: Exception) {
        respond(HttpStatusCode.InternalServerError, "Unexpected server error")
        e.printStackTrace()
        null
    }
}


suspend inline fun <reified T : Any> ApplicationCall.parseJson(): T? {
    val jsonBody = receive<String>()
    return try {
        Json.decodeFromString<T>(jsonBody)
    } catch (e: Exception) {
        null
    }
}

suspend inline fun <reified T : Any> ApplicationCall.parseMultipartData(): T? {
    val multipart = receiveMultipart()
    val fieldMap = mutableMapOf<String, String>()
    var file: File? = null

    multipart.forEachPart { part ->
        when (part) {
            is PartData.FormItem -> fieldMap[part.name ?: ""] = part.value
            is PartData.FileItem -> {
                val fileBytes = part.streamProvider().readBytes()
                val fileName = part.originalFileName ?: "default.jpg"
                file = File("uploads/$fileName").apply { writeBytes(fileBytes) }
            }

            else -> {}
        }
        part.dispose()
    }

    return constructDataClass(T::class, fieldMap, file)
}

fun <T : Any> constructDataClass(kClass: KClass<T>, fieldMap: Map<String, String>, file: File?): T? {
    val constructor = kClass.primaryConstructor ?: return null
    val parameters = constructor.parameters.associateWith { param ->
        when {
            param.type.classifier == String::class -> fieldMap[param.name]
            param.type.classifier == File::class -> file
            else -> null
        }
    }

    return constructor.callBy(parameters)
}
