package io.github.kilgoret.mate

/**
 * Исполнитель эффектов. Runtime вызывает [runEffect] для каждого
 * эффекта; результат исполнения возвращается в цикл НОВЫМ Message
 * через [consumer] — колбэков в обход цикла не существует.
 */
interface MateEffectHandler<Message, out Effect> {
    suspend fun runEffect(
        effect: @UnsafeVariance Effect,
        consumer: (Message) -> Unit
    )
}
