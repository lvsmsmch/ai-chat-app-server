package com.lvsmsmch.aichat.auth

import com.lvsmsmch.aichat.utils.BadRequestException
import org.mindrot.jbcrypt.BCrypt
import java.security.SecureRandom

/**
 * Пароли и коды из писем.
 *
 * Хешируем bcrypt: он медленный по построению и с солью внутри, поэтому утечка
 * таблицы не превращается в список паролей. Стоимость 12 — примерно четверть
 * секунды на 1 vCPU нашего дроплета; ниже смысла нет, выше начнёт мешать
 * логину под нагрузкой.
 */
object Passwords {

    private const val BCRYPT_COST = 12

    /** Ограничение самого bcrypt: всё после 72 байт он молча игнорирует. */
    private const val MAX_PASSWORD_LENGTH = 72
    private const val MIN_PASSWORD_LENGTH = 8

    private val random = SecureRandom()

    fun hash(password: String): String = BCrypt.hashpw(password, BCrypt.gensalt(BCRYPT_COST))

    /**
     * Проверка пароля. Пустой хеш (аккаунт без пароля — гость или вход через
     * Google) всегда даёт false, а не исключение.
     */
    fun matches(password: String, hash: String?): Boolean {
        if (hash.isNullOrBlank()) return false
        return runCatching { BCrypt.checkpw(password, hash) }.getOrDefault(false)
    }

    /**
     * Требования к паролю намеренно скромные: длина работает лучше, чем
     * обязательные спецсимволы, которые люди обходят предсказуемыми «Password1!».
     */
    fun validate(password: String, email: String? = null) {
        if (password.length < MIN_PASSWORD_LENGTH) {
            throw BadRequestException("Password must be at least $MIN_PASSWORD_LENGTH characters")
        }
        if (password.toByteArray().size > MAX_PASSWORD_LENGTH) {
            throw BadRequestException("Password is too long (max $MAX_PASSWORD_LENGTH bytes)")
        }
        if (password.isBlank()) throw BadRequestException("Password must not be blank")
        if (email != null && password.equals(email, ignoreCase = true)) {
            throw BadRequestException("Password must not be the same as email")
        }
    }

    /** Шестизначный код для письма. Ведущие нули сохраняются. */
    fun generateEmailCode(): String = (random.nextInt(1_000_000)).toString().padStart(6, '0')
}

/**
 * Почта. Хранится и сравнивается в нижнем регистре: адрес «A@x.com» — тот же
 * аккаунт, что «a@x.com», и уникальный индекс в базе построен на lower(email).
 */
object Emails {

    // Без попыток формально реализовать RFC: практическая проверка формы адреса
    private val pattern = Regex("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$")

    fun normalize(email: String): String = email.trim().lowercase()

    fun validateAndNormalize(email: String): String {
        val normalized = normalize(email)
        if (normalized.length > 254) throw BadRequestException("Email is too long")
        if (!pattern.matches(normalized)) throw BadRequestException("Invalid email format")
        return normalized
    }

    /** Для писем и логов: «an***@example.com». */
    fun mask(email: String): String {
        val at = email.indexOf('@')
        if (at <= 0) return "***"
        val name = email.take(at)
        val visible = name.take(2)
        return "$visible***${email.substring(at)}"
    }
}
