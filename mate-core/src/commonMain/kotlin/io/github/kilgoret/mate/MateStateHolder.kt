package io.github.kilgoret.mate

import kotlinx.coroutines.flow.StateFlow

/** Публичный контракт раннера для UI: поток состояния + вход сообщений. */
public interface MateStateHolder<State, Message> {
    public val state: StateFlow<State>

    public fun accept(message: Message)
}
