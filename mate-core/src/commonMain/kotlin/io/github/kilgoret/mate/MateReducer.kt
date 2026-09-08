package io.github.kilgoret.mate

/**
 * Результат одного шага update: новое состояние + МНОЖЕСТВО различимых
 * намерений (см. [Effect] — принцип различимых намерений).
 */
public typealias ReducerResult<State, Effect> = Pair<State, Set<Effect>>

/**
 * Чистая тотальная функция update (TEA):
 * `Msg × State → (State, Effects)`. Не выполняет эффекты — описывает
 * их данными; не бросает исключений; обрабатывает любой Message в
 * любом состоянии (невозможный — явный no-op).
 */
public interface MateReducer<State, Message, Effect> {
    public fun reduce(
        state: State,
        message: Message,
    ): ReducerResult<State, Effect>
}

public fun <STATE, EFFECTS> ReducerResult<STATE, EFFECTS>.state(): STATE = first

public fun <STATE, EFFECTS> ReducerResult<STATE, EFFECTS>.effects(): Set<EFFECTS> = second
