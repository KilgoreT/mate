package io.github.kilgoret.mate

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` для кода эффектов и подписок: ловит любой сбой в
 * [Result.failure], но ПРОБРАСЫВАЕТ [CancellationException] — отмена
 * корутины (закрытие экрана) не ошибка и не должна превращаться в
 * fail-Msg.
 *
 * Использование в handler'е вместо ручного «catch отмены + catch
 * остального»:
 *
 * ```
 * val msg = runMateCatching { useCase.addWord(value) }
 *     .fold(
 *         onSuccess = { Msg.WordCreated },
 *         onFailure = { Msg.CreateFailed(value) },
 *     )
 * ```
 */
public inline fun <T> runMateCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
