package com.lvsmsmch.aichat.db

import com.lvsmsmch.aichat.utils.DatabaseEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Замена change streams Mongo: внутрипроцессная шина событий таблицы.
 *
 * Раньше подписчики (SSE-стрим ответа персонажа, лента чатов) читали изменения
 * из change stream Mongo. В Postgres такого механизма нет, но он и не нужен:
 * сервер одноинстансный, писатель и читатель живут в одной JVM — репозиторий
 * сам публикует событие после успешной записи.
 *
 * Публикуем ПОСЛЕ выхода из транзакции: иначе откат оставил бы подписчиков с
 * событием о записи, которой в базе нет.
 *
 * Буфер сбрасывает самые старые события и никогда не блокирует пишущего:
 * отставший подписчик не должен тормозить генерацию сообщения.
 */
class EntityEvents<T> {

    private val _events = MutableSharedFlow<DatabaseEvent<T>>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val events: SharedFlow<DatabaseEvent<T>> = _events

    fun created(new: T) {
        _events.tryEmit(DatabaseEvent.Created(new))
    }

    /**
     * [old] — состояние до записи. Подписчикам важен [new], но старое значение
     * оставлено в контракте: на нём построены проверки «что именно изменилось».
     */
    fun updated(old: T, new: T) {
        _events.tryEmit(DatabaseEvent.Updated(old, new))
    }

    fun deleted(old: T) {
        _events.tryEmit(DatabaseEvent.Deleted(old))
    }
}
