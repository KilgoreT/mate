package io.github.kilgoret.mate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * Handler долгоживущих подписок: [subscribe] запускается раннером при
 * старте, [unsubscribe] — при dispose.
 *
 * Roadmap v1: упраздняется в пользу декларативных
 * `subscriptions(State)` с диффом в раннере.
 */
public interface MateFlowHandler<Message, Effect> : MateEffectHandler<Message, Effect> {
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
