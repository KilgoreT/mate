package io.github.kilgoret.mate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Долгоживущая подписка: [subscribe] запускается раннером при старте,
 * [unsubscribe] — при dispose. НЕ исполняет эффекты (это работа
 * [MateEffectHandler]) — передаётся раннеру отдельным параметром.
 *
 * Ошибки потока — обязанность самого handler'а (catch внутри flow).
 *
 * Roadmap v1: упраздняется в пользу декларативных
 * `subscriptions(State)` с диффом в раннере.
 */
public interface MateFlowHandler<Message> {
    public var job: Job?

    public fun subscribe(
        scope: CoroutineScope,
        send: (Message) -> Unit,
    )

    public fun unsubscribe() {
        job?.cancel()
        job = null
    }
}
