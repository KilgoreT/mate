package io.github.kilgoret.mate

import kotlin.time.Duration

/**
 * Наблюдатель ПОЛНОГО цикла раннера: Msg → state до/после → эффекты →
 * исполнение (длительность/ошибка) → каузальность (Msg, рождённый
 * эффектом). Материал для логов, трасс и time-travel.
 *
 * Read-only by design: наблюдатель не вмешивается в цикл; исключение
 * в наблюдателе не убивает раннер (глотается циклом).
 *
 * Все три типа контравариантны: наблюдатель только ПОЛУЧАЕТ значения,
 * поэтому один общий наблюдатель (`MateObserver<Any?, Any?, Effect>`)
 * вешается на раннер любого экрана — так тестовый харнес пишет единую
 * ленту событий всех раннеров приложения.
 */
public interface MateObserver<in State, in Message, in Effect> {
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

    /**
     * Исполнение эффекта упало, но эффект — [RecoverableEffect]:
     * вместо политики в цикл отправлен [message] из его onFail.
     * Следом приходит [onCausedMessage] с той же парой — каузальный
     * граф recovery не рвёт.
     */
    public fun onEffectRecovered(
        effect: Effect,
        error: Throwable,
        message: Message,
    ): Unit = Unit

    /**
     * Причинная связь: [parent] — эффект, при исполнении которого
     * handler вернул [message] в цикл через consumer.
     */
    public fun onCausedMessage(
        parent: Effect,
        message: Message,
    ): Unit = Unit

    /**
     * Подписка включена. После каждого reduce (и один раз при
     * создании раннера, от initState) раннер сравнивает желаемый
     * набор `subscriptions(state)` с активным: [sub] в новом
     * состоянии появилась — раннер запустил её поток и зовёт этот
     * хук.
     */
    public fun onSubscriptionStarted(sub: Subscription): Unit = Unit

    /**
     * Подписка погашена: на том же сравнении после reduce [sub] из
     * желаемого набора исчезла — раннер отменил её поток и зовёт
     * этот хук.
     */
    public fun onSubscriptionStopped(sub: Subscription): Unit = Unit

    /** Поток подписки упал необработанным исключением. */
    public fun onSubscriptionError(
        sub: Subscription,
        error: Throwable,
    ): Unit = Unit
}
