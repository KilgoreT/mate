package io.github.kilgoret.mate

import kotlin.reflect.KClass

/**
 * Ошибки runtime-цикла. В Elm исключений не существует (ошибки —
 * значения); Kotlin-раннер обязан выбрать поведение сам — поведение
 * задаёт [MateFailPolicy].
 */
public sealed interface MateError {
    /** Исключение при обработке сообщения (reduce/observer-стадия). */
    public data class MessageFailed(
        val message: Any?,
        val cause: Throwable,
    ) : MateError

    /** Исключение при исполнении эффекта handler'ом. */
    public data class EffectFailed(
        val effect: Effect,
        val cause: Throwable,
    ) : MateError

    /** Эффект-«сирота»: его семейство не заявлено ни одним handler'ом. */
    public data class OrphanEffect(
        val effect: Effect,
    ) : MateError

    /** Эффект матчится на НЕСКОЛЬКО семейств — запрещено (раздели). */
    public data class AmbiguousEffect(
        val effect: Effect,
        val families: List<KClass<out Effect>>,
    ) : MateError

    /** Message отвергнут mailbox'ом (раннер уже остановлен). */
    public data class MessageRejected(
        val message: Any?,
    ) : MateError
}

/**
 * Политика реакции на [MateError] — параметр раннера (в common нет
 * BuildConfig, параметр — единственный KMP-валидный способ различить
 * debug/release).
 *
 * Контракт цикла: mailbox не умирает от исключений ПРИ НЕкидающей
 * политике. [Strict] сознательно кидает — «debug-краш».
 */
public fun interface MateFailPolicy {
    public fun onError(error: MateError)

    public companion object {
        /** Debug-режим: любой [MateError] — немедленный краш. */
        public val Strict: MateFailPolicy =
            MateFailPolicy { error ->
                throw MateException(error)
            }
    }
}

/** Исключение [MateFailPolicy.Strict]. */
public class MateException(
    public val error: MateError,
) : IllegalStateException("Mate runtime error: $error")
