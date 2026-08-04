package com.lvsmsmch.aichat.utils

/**
 * Событие изменения записи. Раньше приходило из change stream Mongo, теперь
 * публикуется репозиторием через com.lvsmsmch.aichat.db.EntityEvents — контракт
 * для подписчиков остался прежним.
 */
sealed class DatabaseEvent<out T> {
    data class Created<T>(val new: T) : DatabaseEvent<T>()
    data class Updated<T>(val old: T, val new: T) : DatabaseEvent<T>()
    data class Deleted<T>(val old: T) : DatabaseEvent<T>()

    val latestObject: T
        get() = when (this) {
            is Created -> new
            is Updated -> new
            is Deleted -> old
        }
}