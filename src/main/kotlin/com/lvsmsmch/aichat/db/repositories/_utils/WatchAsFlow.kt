package com.lvsmsmch.aichat.db.repositories._utils

import com.mongodb.client.model.changestream.FullDocument
import com.mongodb.client.model.changestream.FullDocumentBeforeChange
import com.mongodb.client.model.changestream.OperationType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.litote.kmongo.coroutine.CoroutineCollection
import kotlinx.coroutines.reactive.asFlow

inline fun <reified T : Any> CoroutineCollection<T>.watchAsFlow(): Flow<ChangeEvent<T>> {
    // Get the underlying reactive publisher
    val reactivePublisher = this.collection.watch(emptyList(), T::class.java)
        .fullDocument(FullDocument.UPDATE_LOOKUP)
        .fullDocumentBeforeChange(FullDocumentBeforeChange.REQUIRED)


    // Convert the reactive publisher to a Flow using kotlinx-coroutines-reactive
    return reactivePublisher.asFlow().let { reactiveFlow ->
        flow {
            reactiveFlow.collect { change ->
                val operationType = change.operationType

                val event = when (operationType) {
                    OperationType.INSERT -> {
                        val document = change.fullDocument
                        if (document != null) {
                            ChangeEvent.Created(document)
                        } else null
                    }

                    OperationType.UPDATE, OperationType.REPLACE -> {
                        val newDocument = change.fullDocument
                        val oldDocument = change.fullDocumentBeforeChange
                        if (newDocument != null && oldDocument != null) {
                            ChangeEvent.Updated(oldDocument, newDocument)
                        } else null
                    }

                    OperationType.DELETE -> {
                        val oldDocument = change.fullDocumentBeforeChange
                        if (oldDocument != null) {
                            ChangeEvent.Deleted(oldDocument)
                        } else null
                    }

                    else -> null
                }

                event?.let { emit(it) }
            }
        }
    }
}

sealed class ChangeEvent<out T> {
    data class Created<T>(val new: T) : ChangeEvent<T>()
    data class Updated<T>(val old: T, val new: T) : ChangeEvent<T>()
    data class Deleted<T>(val old: T) : ChangeEvent<T>()

    val latestObject: T
        get() = when (this) {
            is Created -> new
            is Updated -> new
            is Deleted -> old
        }
}