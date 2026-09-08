package io.github.kilgoret.mate

import kotlinx.coroutines.CoroutineScope
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MateRunnerTest {
    private data class S(val value: Int, val echoed: List<String> = emptyList())

    private sealed interface Msg {
        data class Add(val amount: Int) : Msg

        data class Echoed(val tag: String) : Msg

        data object Boom : Msg
    }

    private sealed interface Fx : Effect {
        data class Echo(val tag: String) : Fx

        data class FailingFx(val reason: String) : Fx
    }

    // Как в реальных экранах: reducer типизирован КОРНЕВЫМ Effect.
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

                is Msg.Boom -> error("reducer exploded")
            }
    }

    /** Исполнитель семейства Fx: Echo возвращает Msg, FailingFx кидает. */
    private class FxHandler : MateEffectHandler<Msg, Fx> {
        override val effectFamily: KClass<Fx> = Fx::class

        override suspend fun runEffect(
            effect: Fx,
            consumer: (Msg) -> Unit,
        ) {
            when (effect) {
                is Fx.Echo -> consumer(Msg.Echoed(effect.tag))
                is Fx.FailingFx -> error(effect.reason)
            }
        }
    }

    private fun buildMate(
        scope: CoroutineScope,
        observers: List<MateObserver<S, Msg, Effect>> = emptyList(),
        failPolicy: MateFailPolicy = MateFailPolicy.Strict,
        initEffects: Set<Effect> = emptySet(),
    ): Mate<S, Msg, Effect> =
        Mate(
            initState = S(0),
            reducer = Reducer(),
            initEffects = initEffects,
            effectHandlers = listOf(FxHandler()),
            observers = observers,
            failPolicy = failPolicy,
            coroutineScope = scope,
        )

    @Test
    fun acceptReducesAndCascadesThroughMailbox() =
        runMateTest { mateScope ->
            val mate = buildMate(mateScope)

            mate.accept(Msg.Add(5))
            testScheduler.advanceUntilIdle()

            assertEquals(5, mate.state.value.value)
            assertEquals(listOf("add-5"), mate.state.value.echoed)
        }

    @Test
    fun mailboxIsFifoNotDepthFirst() =
        runMateTest { mateScope ->
            // Два Message подряд: каскад от первого (Echoed) обязан
            // обработаться ПОСЛЕ второго Add — очередь, не стек.
            val observer = RecordingObserver<S, Msg, Effect>()
            val mate = buildMate(mateScope, observers = listOf(observer))

            mate.accept(Msg.Add(1))
            mate.accept(Msg.Add(2))
            testScheduler.advanceUntilIdle()

            val order = observer.events.filter { it.startsWith("msg:") }
            assertEquals(
                listOf(
                    "msg:${Msg.Add(1)}",
                    "msg:${Msg.Add(2)}",
                    "msg:${Msg.Echoed("add-1")}",
                    "msg:${Msg.Echoed("add-2")}",
                ),
                order,
            )
        }

    @Test
    fun initEffectsRunBeforeAnyMessage() =
        runMateTest { mateScope ->
            val observer = RecordingObserver<S, Msg, Effect>()
            val mate =
                buildMate(
                    mateScope,
                    observers = listOf(observer),
                    initEffects = setOf(Fx.Echo("boot")),
                )

            mate.accept(Msg.Add(1))
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("boot", "add-1"), mate.state.value.echoed)
            val firstFxStart = observer.events.indexOfFirst { it.startsWith("fx-start:") }
            val firstMsg = observer.events.indexOfFirst { it.startsWith("msg:") }
            assertTrue(firstFxStart < firstMsg, "init effect must dispatch before first message")
        }

    @Test
    fun reducerExceptionGoesToPolicyAndLoopSurvives() =
        runMateTest { mateScope ->
            val errors = mutableListOf<MateError>()
            val mate = buildMate(mateScope, failPolicy = { errors += it })

            mate.accept(Msg.Boom)
            mate.accept(Msg.Add(3))
            testScheduler.advanceUntilIdle()

            assertEquals(1, errors.size)
            assertTrue(errors.single() is MateError.MessageFailed)
            // Цикл жив: следующий Message обработан.
            assertEquals(3, mate.state.value.value)
        }

    @Test
    fun effectExceptionGoesToPolicyAndObserver() =
        runMateTest { mateScope ->
            val errors = mutableListOf<MateError>()
            val observer = RecordingObserver<S, Msg, Effect>()
            val mate =
                buildMate(
                    mateScope,
                    observers = listOf(observer),
                    failPolicy = { errors += it },
                    initEffects = setOf(Fx.FailingFx("net down")),
                )
            testScheduler.advanceUntilIdle()

            assertTrue(errors.single() is MateError.EffectFailed)
            assertTrue(observer.events.any { it.startsWith("fx-fail:") && it.contains("net down") })
        }

    @Test
    fun throwingObserverDoesNotKillLoop() =
        runMateTest { mateScope ->
            val angry =
                object : MateObserver<S, Msg, Effect> {
                    override fun onMessage(
                        message: Msg,
                        stateBefore: S,
                    ): Unit = error("observer tantrum")
                }
            val mate = buildMate(mateScope, observers = listOf(angry))

            mate.accept(Msg.Add(7))
            testScheduler.advanceUntilIdle()

            assertEquals(7, mate.state.value.value)
        }

    @Test
    fun causalityIsObservable() =
        runMateTest { mateScope ->
            val observer = RecordingObserver<S, Msg, Effect>()
            val mate = buildMate(mateScope, observers = listOf(observer))

            mate.accept(Msg.Add(9))
            testScheduler.advanceUntilIdle()

            assertTrue(
                observer.events.contains(
                    "caused:${Fx.Echo("add-9")}->${Msg.Echoed("add-9")}",
                ),
            )
            assertTrue(observer.events.any { it == "fx-done:${Fx.Echo("add-9")}" })
        }
}
