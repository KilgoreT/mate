package io.github.kilgoret.mate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecoverableEffectTest {
    private data class S(
        val pings: Int = 0,
        val fails: List<String> = emptyList(),
    )

    private sealed interface Msg {
        data object Ping : Msg

        data class Launch(val fx: Fx) : Msg

        data class Failed(val tag: String) : Msg
    }

    private sealed interface Fx : Effect {
        /** Падает при исполнении; объявляет ответ на провал. */
        data class Fragile(val tag: String) : Fx, RecoverableEffect<Msg> {
            override fun onFail(error: Throwable) = Msg.Failed("$tag:${error.message}")
        }

        /** Падает при исполнении; его onFail падает тоже. */
        data object BrokenRecovery : Fx, RecoverableEffect<Msg> {
            override fun onFail(error: Throwable): Msg = error("recovery exploded")
        }

        /** Падает при исполнении; recovery не объявлен. */
        data object Plain : Fx

        /** Висит в delay — мишень для отмены scope. */
        data object Sleepy : Fx, RecoverableEffect<Msg> {
            override fun onFail(error: Throwable) = Msg.Failed("sleepy:${error.message}")
        }

        /** withTimeout внутри исполнения истекает. */
        data object TimingOut : Fx, RecoverableEffect<Msg> {
            override fun onFail(error: Throwable) = Msg.Failed("timeout")
        }
    }

    private class Reducer : MateReducer<S, Msg, Effect> {
        override fun reduce(
            state: S,
            message: Msg,
        ): ReducerResult<S, Effect> =
            when (message) {
                is Msg.Ping -> state.copy(pings = state.pings + 1) to emptySet()
                is Msg.Launch -> state to setOf(message.fx)
                is Msg.Failed -> state.copy(fails = state.fails + message.tag) to emptySet()
            }
    }

    private class FxHandler : MateEffectHandler<Msg, Fx> {
        override val effectFamily: KClass<Fx> = Fx::class

        override suspend fun runEffect(
            effect: Fx,
            consumer: (Msg) -> Unit,
        ) {
            when (effect) {
                is Fx.Fragile -> error("boom")
                is Fx.BrokenRecovery -> error("primary")
                is Fx.Plain -> error("plain boom")
                is Fx.Sleepy -> delay(1_000_000)
                is Fx.TimingOut -> withTimeout(10) { delay(1_000) }
            }
        }
    }

    private fun buildMate(
        scope: CoroutineScope,
        observers: List<MateObserver<S, Msg, Effect>> = emptyList(),
        failPolicy: MateFailPolicy = MateFailPolicy.Strict,
    ): Mate<S, Msg, Effect> =
        Mate(
            initState = S(),
            reducer = Reducer(),
            initEffects = emptySet(),
            effectHandlers = listOf(FxHandler()),
            observers = observers,
            failPolicy = failPolicy,
            coroutineScope = scope,
        )

    @Test
    fun recoverableFailureDeliversOnFailMessage() =
        runMateTest { mateScope ->
            val errors = mutableListOf<MateError>()
            val observer = RecordingObserver<S, Msg, Effect>()
            val mate = buildMate(mateScope, listOf(observer), { errors += it })

            mate.accept(Msg.Launch(Fx.Fragile("load")))
            testScheduler.advanceUntilIdle()

            // onFail-Msg отредьюсен, политика молчит.
            assertEquals(listOf("load:boom"), mate.state.value.fails)
            assertEquals(emptyList(), errors)
            // Трасса: recovered + каузальная связь, БЕЗ fx-fail.
            assertTrue(observer.events.any { it.startsWith("fx-recovered:${Fx.Fragile("load")}") })
            assertTrue(observer.events.contains("caused:${Fx.Fragile("load")}->${Msg.Failed("load:boom")}"))
            assertTrue(observer.events.none { it.startsWith("fx-fail:") })
        }

    @Test
    fun plainFailureStillGoesToPolicy() =
        runMateTest { mateScope ->
            val errors = mutableListOf<MateError>()
            val observer = RecordingObserver<S, Msg, Effect>()
            val mate = buildMate(mateScope, listOf(observer), { errors += it })

            mate.accept(Msg.Launch(Fx.Plain))
            testScheduler.advanceUntilIdle()

            val failed = errors.single() as MateError.EffectFailed
            assertEquals(Fx.Plain, failed.effect)
            assertEquals(emptyList(), mate.state.value.fails)
            assertTrue(observer.events.none { it.startsWith("fx-recovered:") })
        }

    @Test
    fun throwingOnFailFallsBackToPolicyWithSuppressed() =
        runMateTest { mateScope ->
            val errors = mutableListOf<MateError>()
            val observer = RecordingObserver<S, Msg, Effect>()
            val mate = buildMate(mateScope, listOf(observer), { errors += it })

            mate.accept(Msg.Launch(Fx.BrokenRecovery))
            testScheduler.advanceUntilIdle()

            // Исходная ошибка эффекта в политике, ошибка onFail — suppressed.
            val failed = errors.single() as MateError.EffectFailed
            assertEquals("primary", failed.cause.message)
            assertEquals(
                listOf("recovery exploded"),
                failed.cause.suppressedExceptions.map { it.message },
            )
            assertTrue(observer.events.none { it.startsWith("fx-recovered:") })

            // Цикл жив: следующий Msg обрабатывается.
            mate.accept(Msg.Ping)
            testScheduler.advanceUntilIdle()
            assertEquals(1, mate.state.value.pings)
        }

    @Test
    fun scopeCancellationSkipsRecovery() =
        runMateTest { mateScope ->
            val errors = mutableListOf<MateError>()
            val observer = RecordingObserver<S, Msg, Effect>()
            val mate = buildMate(mateScope, listOf(observer), { errors += it })

            mate.accept(Msg.Launch(Fx.Sleepy))
            testScheduler.runCurrent()
            mateScope.cancel()
            testScheduler.advanceUntilIdle()

            // Отмена scope — не провал: ни recovery, ни политики.
            assertEquals(emptyList(), mate.state.value.fails)
            assertEquals(emptyList(), errors)
            assertTrue(observer.events.none { it.startsWith("fx-recovered:") })
            assertTrue(observer.events.none { it.startsWith("fx-fail:") })
        }

    @Test
    fun timeoutInsideEffectIsFailureNotCancellation() =
        runMateTest { mateScope ->
            val errors = mutableListOf<MateError>()
            val mate = buildMate(mateScope, failPolicy = { errors += it })

            mate.accept(Msg.Launch(Fx.TimingOut))
            testScheduler.advanceUntilIdle()

            // TimeoutCancellationException при живой корутине — честный
            // провал эффекта: recovery доставлен, эффект не исчез.
            assertEquals(listOf("timeout"), mate.state.value.fails)
            assertEquals(emptyList(), errors)
        }
}
