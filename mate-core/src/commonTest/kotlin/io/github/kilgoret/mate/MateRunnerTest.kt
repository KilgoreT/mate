package io.github.kilgoret.mate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MateRunnerTest {
    private data class S(val value: Int, val echoed: List<String> = emptyList())

    private sealed interface Msg {
        data class Add(val amount: Int) : Msg

        data class Echoed(val tag: String) : Msg
    }

    private sealed interface Fx : Effect {
        data class Echo(val tag: String) : Fx
    }

    // Как в реальных экранах: reducer типизирован КОРНЕВЫМ Effect
    // (эффекты-семейства — подтипы), Mate — тоже.
    private class Reducer : MateReducer<S, Msg, Effect> {
        override fun reduce(
            state: S,
            message: Msg,
        ): ReducerResult<S, Effect> =
            when (message) {
                is Msg.Add ->
                    state.copy(value = state.value + message.amount)
                        .begin<S, Effect>() withEffect Fx.Echo("add-${message.amount}")

                is Msg.Echoed -> state.copy(echoed = state.echoed + message.tag) to emptySet()
            }
    }

    /** Исполнитель Echo: возвращает Echoed в цикл — каскад Msg. */
    private class EchoHandler : MateTypedEffectHandler<Msg, Fx.Echo>() {
        override fun filter(effect: Effect): Fx.Echo? = effect as? Fx.Echo

        override suspend fun onEffect(
            effect: Fx.Echo,
            consumer: (Msg) -> Unit,
        ) {
            consumer(Msg.Echoed(effect.tag))
        }
    }

    @Test
    fun acceptReducesAndPublishesState() =
        runTest {
            val mate =
                Mate<S, Msg, Effect>(
                    initState = S(0),
                    reducer = Reducer(),
                    initEffects = emptySet(),
                    effectHandlerSet = setOf(EchoHandler()),
                    coroutineScope = this,
                )

            mate.accept(Msg.Add(5))

            assertEquals(5, mate.state.value.value)
            testScheduler.advanceUntilIdle()
            // Каскад: эффект Echo вернулся сообщением Echoed.
            assertEquals(listOf("add-5"), mate.state.value.echoed)
        }

    @Test
    fun initEffectsRunOnStart() =
        runTest {
            val mate =
                Mate<S, Msg, Effect>(
                    initState = S(0),
                    reducer = Reducer(),
                    initEffects = setOf(Fx.Echo("boot")),
                    effectHandlerSet = setOf(EchoHandler()),
                    coroutineScope = this,
                )

            testScheduler.advanceUntilIdle()
            assertEquals(listOf("boot"), mate.state.value.echoed)
        }

    @Test
    fun flowHandlerSubscribesOnStartAndUnsubscribesOnDispose() =
        runTest {
            val source = MutableSharedFlow<String>()
            val flowHandler =
                object : MateFlowHandler<Msg, Effect> {
                    override var job: Job? = null

                    override fun subscribe(
                        scope: CoroutineScope,
                        send: (Msg) -> Unit,
                    ) {
                        job =
                            scope.launch {
                                source.collect { send(Msg.Echoed(it)) }
                            }
                    }

                    override suspend fun runEffect(
                        effect: Effect,
                        consumer: (Msg) -> Unit,
                    ) = Unit
                }
            val mate =
                Mate<S, Msg, Effect>(
                    initState = S(0),
                    reducer = Reducer(),
                    initEffects = emptySet(),
                    effectHandlerSet = setOf(flowHandler),
                    coroutineScope = this,
                )

            testScheduler.advanceUntilIdle()
            source.emit("live")
            testScheduler.advanceUntilIdle()
            assertEquals(listOf("live"), mate.state.value.echoed)

            mate.dispose()
            assertNull(flowHandler.job)
        }
}
