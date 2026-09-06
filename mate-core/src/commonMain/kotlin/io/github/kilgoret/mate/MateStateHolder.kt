package io.github.kilgoret.mate

import kotlinx.coroutines.flow.StateFlow

/** Публичный контракт раннера для UI: поток состояния + вход сообщений. */
interface MateStateHolder<State, Message> {

    val state: StateFlow<State>
    fun accept(message: Message)
}
