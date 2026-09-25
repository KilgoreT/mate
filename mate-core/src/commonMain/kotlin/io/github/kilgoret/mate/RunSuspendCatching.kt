package io.github.kilgoret.mate

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` для suspend-кода: ловит любой сбой в
 * [Result.failure], но пробрасывает [CancellationException] — отмена
 * корутины (закрытие экрана) не ошибка и не должна превращаться в
 * значение-провал.
 *
 * В suspend-коде всегда вместо голого `runCatching`: тот глотает
 * отмену и ломает кооперативную отмену корутин.
 *
 * Для целых эффектов широкий catch не нужен: провал эффекта
 * объявляется в его типе ([RecoverableEffect]). Эта функция — для
 * точечной обработки внутри многошагового runEffect и для
 * suspend-кода вне цикла mate:
 *
 * ```
 * val cached = runSuspendCatching { cache.read(key) }
 *     .getOrElse { fallback }
 * ```
 */
public inline fun <T> runSuspendCatching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (error: CancellationException) {
    throw error
} catch (error: Throwable) {
    Result.failure(error)
}
