package io.github.kilgoret.mate

import kotlin.time.Duration

/** Тестовый наблюдатель: пишет ленту событий цикла строками. */
internal class RecordingObserver<State, Message, E : Effect> : MateObserver<State, Message, E> {
    val events = mutableListOf<String>()

    override fun onMessage(
        message: Message,
        stateBefore: State,
    ) {
        events += "msg:$message"
    }

    override fun onReduced(
        message: Message,
        before: State,
        after: State,
        effects: Set<E>,
    ) {
        events += "reduced:$message->effects=${effects.size}"
    }

    override fun onEffectStarted(effect: E) {
        events += "fx-start:$effect"
    }

    override fun onEffectFinished(
        effect: E,
        duration: Duration,
    ) {
        events += "fx-done:$effect"
    }

    override fun onEffectFailed(
        effect: E,
        error: Throwable,
    ) {
        events += "fx-fail:$effect:${error.message}"
    }

    override fun onCausedMessage(
        parent: E,
        message: Message,
    ) {
        events += "caused:$parent->$message"
    }
}
