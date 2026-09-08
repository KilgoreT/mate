package io.github.kilgoret.mate.apptest

import io.github.kilgoret.mate.Effect
import io.github.kilgoret.mate.Mate
import io.github.kilgoret.mate.MateEffectHandler
import io.github.kilgoret.mate.MateReducer
import io.github.kilgoret.mate.MateSubscriptionHandler
import io.github.kilgoret.mate.NavigationEffect
import io.github.kilgoret.mate.ReducerResult
import io.github.kilgoret.mate.Sub
import io.github.kilgoret.mate.navigation.NavGraph
import io.github.kilgoret.mate.navigation.Screen
import io.github.kilgoret.mate.navigation.navGraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlin.reflect.KClass

/*
 * Мини-приложение для TDD харнеса: экран счётчика (эффект-персист,
 * живая подписка, тикер, навигация в детали) и экран деталей (Back).
 * Узел Counter собирается с двумя раннерами (counter + panel) — для
 * проверки send-роутинга по семействам сообщений.
 */

// ===== Экраны =====

data object CounterScreen : Screen

data class DetailsScreen(val id: Long) : Screen

// ===== Counter =====

data class CounterState(
    val value: Int = 0,
    val live: List<String> = emptyList(),
    val ticks: Int = 0,
)

sealed interface CounterMsg {
    data object Inc : CounterMsg

    data class Persisted(val value: Int) : CounterMsg

    data class LiveUpdated(val items: List<String>) : CounterMsg

    data object Tick : CounterMsg

    data class OpenDetails(val id: Long) : CounterMsg
}

sealed interface CounterEffect : Effect {
    data class Persist(val value: Int) : CounterEffect
}

sealed interface CounterNav : NavigationEffect {
    data class OpenDetails(val id: Long) : CounterNav
}

sealed interface CounterSub : Sub {
    data object Live : CounterSub

    data object Ticker : CounterSub
}

class CounterReducer : MateReducer<CounterState, CounterMsg, Effect> {
    override fun reduce(
        state: CounterState,
        message: CounterMsg,
    ): ReducerResult<CounterState, Effect> =
        when (message) {
            CounterMsg.Inc -> {
                val next = state.value + 1
                state.copy(value = next) to setOf(CounterEffect.Persist(next))
            }

            is CounterMsg.Persisted -> state to emptySet()

            is CounterMsg.LiveUpdated -> state.copy(live = message.items) to emptySet()

            CounterMsg.Tick -> state.copy(ticks = state.ticks + 1) to emptySet()

            is CounterMsg.OpenDetails ->
                state to setOf(CounterNav.OpenDetails(message.id))
        }
}

/** Персист: use case-шов — сценарий подменяет поведение лямбдой. */
class CounterEffectHandler(
    private val persist: suspend (Int) -> Unit,
) : MateEffectHandler<CounterMsg, CounterEffect> {
    override val effectFamily: KClass<CounterEffect> = CounterEffect::class

    override suspend fun runEffect(
        effect: CounterEffect,
        consumer: (CounterMsg) -> Unit,
    ) {
        when (effect) {
            is CounterEffect.Persist -> {
                persist(effect.value)
                consumer(CounterMsg.Persisted(effect.value))
            }
        }
    }
}

class CounterSubHandler(
    private val live: Flow<List<String>>,
) : MateSubscriptionHandler<CounterMsg, CounterSub> {
    override val subFamily: KClass<CounterSub> = CounterSub::class

    override fun flow(sub: CounterSub): Flow<CounterMsg> =
        when (sub) {
            CounterSub.Live -> live.map { CounterMsg.LiveUpdated(it) }

            CounterSub.Ticker ->
                flow {
                    while (true) {
                        delay(1_000)
                        emit(CounterMsg.Tick)
                    }
                }
        }
}

// ===== Panel: второй раннер узла Counter =====

data class PanelState(val note: String = "")

sealed interface PanelMsg {
    data class SetNote(val value: String) : PanelMsg
}

class PanelReducer : MateReducer<PanelState, PanelMsg, Effect> {
    override fun reduce(
        state: PanelState,
        message: PanelMsg,
    ): ReducerResult<PanelState, Effect> =
        when (message) {
            is PanelMsg.SetNote -> state.copy(note = message.value) to emptySet()
        }
}

// ===== Details =====

data class DetailsState(val id: Long)

sealed interface DetailsMsg {
    data object Close : DetailsMsg
}

class DetailsReducer : MateReducer<DetailsState, DetailsMsg, Effect> {
    override fun reduce(
        state: DetailsState,
        message: DetailsMsg,
    ): ReducerResult<DetailsState, Effect> =
        when (message) {
            DetailsMsg.Close -> state to setOf(NavigationEffect.Back)
        }
}

// ===== Сборка =====

val miniNavGraph: NavGraph =
    navGraph {
        on<NavigationEffect.Back> { pop() }
        on<CounterNav.OpenDetails> { push(DetailsScreen(it.id)) }
    }

fun miniRegistry(
    live: Flow<List<String>>,
    persist: suspend (Int) -> Unit = {},
): ScreenRegistry =
    screenRegistry {
        on<CounterScreen> { screen, context ->
            ScreenNode(
                screen = screen,
                runners =
                    listOf(
                        RunnerSlot(
                            name = "counter",
                            messageFamily = CounterMsg::class,
                            holder =
                                Mate<CounterState, CounterMsg, Effect>(
                                    initState = CounterState(),
                                    reducer = CounterReducer(),
                                    initEffects = emptySet(),
                                    effectHandlers =
                                        listOf(
                                            CounterEffectHandler(persist),
                                            context.navigationHandler,
                                        ),
                                    subscriptions = {
                                        setOf(CounterSub.Live, CounterSub.Ticker)
                                    },
                                    subscriptionHandlers = listOf(CounterSubHandler(live)),
                                    observers = listOf(context.observer),
                                    coroutineScope = context.scope,
                                ),
                        ),
                        RunnerSlot(
                            name = "panel",
                            messageFamily = PanelMsg::class,
                            holder =
                                Mate<PanelState, PanelMsg, Effect>(
                                    initState = PanelState(),
                                    reducer = PanelReducer(),
                                    initEffects = emptySet(),
                                    effectHandlers = emptyList(),
                                    observers = listOf(context.observer),
                                    coroutineScope = context.scope,
                                ),
                        ),
                    ),
            )
        }
        on<DetailsScreen> { screen, context ->
            ScreenNode(
                screen = screen,
                runners =
                    listOf(
                        RunnerSlot(
                            name = "details",
                            messageFamily = DetailsMsg::class,
                            holder =
                                Mate<DetailsState, DetailsMsg, Effect>(
                                    initState = DetailsState(id = screen.id),
                                    reducer = DetailsReducer(),
                                    initEffects = emptySet(),
                                    effectHandlers = listOf(context.navigationHandler),
                                    observers = listOf(context.observer),
                                    coroutineScope = context.scope,
                                ),
                        ),
                    ),
            )
        }
    }
