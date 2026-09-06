package io.github.kilgoret.mate

/**
 * Результат одного шага update: новое состояние + МНОЖЕСТВО различимых
 * намерений (см. [Effect], принцип П1).
 */
typealias ReducerResult<State, Effect> = Pair<State, Set<Effect>>

/**
 * Чистая тотальная функция update (TEA):
 * `Msg × State → (State, Effects)`. Не выполняет эффекты — описывает
 * их данными; не бросает исключений; обрабатывает любой Message в
 * любом состоянии (невозможный — явный no-op).
 */
interface MateReducer<State, Message, Effect> {
    fun reduce(state: State, message: Message): ReducerResult<State, Effect>
}

fun <STATE, EFFECTS> ReducerResult<STATE, EFFECTS>.state() = first
fun <STATE, EFFECTS> ReducerResult<STATE, EFFECTS>.effects() = second
