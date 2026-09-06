package io.github.kilgoret.mate

import kotlin.time.Duration

/**
 * Наблюдатель ПОЛНОГО цикла раннера: Msg → state до/после → эффекты →
 * исполнение (длительность/ошибка) → каузальность (Msg, рождённый
 * эффектом). Материал для логов, трасс и time-travel.
 *
 * Read-only by design: наблюдатель не вмешивается в цикл; исключение
 * в наблюдателе не убивает раннер (глотается циклом).
 */
public interface MateObserver<State, Message, Effect> {
    /** Message взят из mailbox, до reduce. */
    public fun onMessage(
        message: Message,
        stateBefore: State,
    ): Unit = Unit

    /** Reduce выполнен: полное изменение одним вызовом. */
    public fun onReduced(
        message: Message,
        before: State,
        after: State,
        effects: Set<Effect>,
    ): Unit = Unit

    /** Эффект передан исполнителю. */
    public fun onEffectStarted(effect: Effect): Unit = Unit

    /** Исполнитель завершил эффект. */
    public fun onEffectFinished(
        effect: Effect,
        duration: Duration,
    ): Unit = Unit

    /** Исполнение эффекта упало ([MateError.EffectFailed] уйдёт в политику). */
    public fun onEffectFailed(
        effect: Effect,
        error: Throwable,
    ): Unit = Unit

    /** Каузальность: [message] рождён исполнением [parent]. */
    public fun onCausedMessage(
        parent: Effect,
        message: Message,
    ): Unit = Unit
}
