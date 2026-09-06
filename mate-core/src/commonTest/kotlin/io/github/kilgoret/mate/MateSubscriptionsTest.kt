package io.github.kilgoret.mate

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MateSubscriptionsTest {
    private data class S(
        val watching: Set<Long> = emptySet(),
        val limit: Int = 1,
        val received: List<String> = emptyList(),
    )

    private sealed interface Msg {
        data class Watch(val id: Long) : Msg

        data class Unwatch(val id: Long) : Msg

        data class SetLimit(val limit: Int) : Msg

        data class Emitted(val tag: String) : Msg
    }

    private sealed interface WindowSub : Sub {
        data class Window(val id: Long, val limit: Int) : WindowSub
    }

    private data object StraySub : Sub

    private class Reducer : MateReducer<S, Msg, Effect> {
        override fun reduce(
            state: S,
            message: Msg,
        ): ReducerResult<S, Effect> =
            when (message) {
                is Msg.Watch -> state.copy(watching = state.watching + message.id) to emptySet()
                is Msg.Unwatch -> state.copy(watching = state.watching - message.id) to emptySet()
                is Msg.SetLimit -> state.copy(limit = message.limit) to emptySet()
                is Msg.Emitted -> state.copy(received = state.received + message.tag) to emptySet()
            }
    }

    /** Карта подписок: окно на каждый наблюдаемый id с текущим лимитом. */
    private fun subscriptionsOf(state: S): Set<Sub> = state.watching.map { WindowSub.Window(it, state.limit) }.toSet()

    private class WindowHandler(
        private val source: MutableSharedFlow<String>,
    ) : MateSubscriptionHandler<Msg, WindowSub> {
        val started = mutableListOf<WindowSub>()

        override val subFamily: KClass<WindowSub> = WindowSub::class

        override fun flow(sub: WindowSub): Flow<Msg> {
            started += sub
            return source.map { Msg.Emitted("$it@$sub") }
        }
    }

    private fun buildMate(
        scope: kotlinx.coroutines.CoroutineScope,
        handler: MateSubscriptionHandler<Msg, *>,
        observers: List<MateObserver<S, Msg, Effect>> = emptyList(),
        failPolicy: MateFailPolicy = MateFailPolicy.Strict,
        initState: S = S(),
    ) = Mate<S, Msg, Effect>(
        initState = initState,
        reducer = Reducer(),
        initEffects = emptySet(),
        effectHandlers = emptyList(),
        subscriptions = ::subscriptionsOf,
        subscriptionHandlers = listOf(handler),
        observers = observers,
        failPolicy = failPolicy,
        coroutineScope = scope,
    )

    @Test
    fun subscriptionStartsWhenStateDeclaresIt() =
        runMateTest { mateScope ->
            val source = MutableSharedFlow<String>()
            val handler = WindowHandler(source)
            val mate = buildMate(mateScope, handler)

            mate.accept(Msg.Watch(5))
            testScheduler.advanceUntilIdle()
            source.emit("data")
            testScheduler.advanceUntilIdle()

            assertEquals<List<WindowSub>>(listOf(WindowSub.Window(5, 1)), handler.started)
            assertEquals(listOf("data@${WindowSub.Window(5, 1)}"), mate.state.value.received)
        }

    @Test
    fun subscriptionStopsWhenGoneFromState() =
        runMateTest { mateScope ->
            val source = MutableSharedFlow<String>()
            val handler = WindowHandler(source)
            val observer = RecordingObserver<S, Msg, Effect>()
            val mate = buildMate(mateScope, handler, observers = listOf(observer))

            mate.accept(Msg.Watch(5))
            testScheduler.advanceUntilIdle()
            mate.accept(Msg.Unwatch(5))
            testScheduler.advanceUntilIdle()
            source.emit("late")
            testScheduler.advanceUntilIdle()

            // Погашенная подписка эмиссий не доставляет.
            assertEquals(emptyList(), mate.state.value.received)
        }

    @Test
    fun equalSubDoesNotRestart() =
        runMateTest { mateScope ->
            val source = MutableSharedFlow<String>()
            val handler = WindowHandler(source)
            val mate = buildMate(mateScope, handler)

            mate.accept(Msg.Watch(5))
            testScheduler.advanceUntilIdle()
            // Msg, не меняющий desired-набор: Watch(5) уже есть.
            mate.accept(Msg.Watch(5))
            testScheduler.advanceUntilIdle()

            assertEquals(1, handler.started.size)
        }

    @Test
    fun changedParameterRestartsSubscription() =
        runMateTest { mateScope ->
            val source = MutableSharedFlow<String>()
            val handler = WindowHandler(source)
            val mate = buildMate(mateScope, handler)

            mate.accept(Msg.Watch(5))
            testScheduler.advanceUntilIdle()
            mate.accept(Msg.SetLimit(10))
            testScheduler.advanceUntilIdle()

            assertEquals<List<WindowSub>>(
                listOf(WindowSub.Window(5, 1), WindowSub.Window(5, 10)),
                handler.started,
            )
        }

    @Test
    fun initialDiffRunsFromInitState() =
        runMateTest { mateScope ->
            val source = MutableSharedFlow<String>()
            val handler = WindowHandler(source)
            buildMate(mateScope, handler, initState = S(watching = setOf(7)))
            testScheduler.advanceUntilIdle()

            assertEquals<List<WindowSub>>(listOf(WindowSub.Window(7, 1)), handler.started)
        }

    @Test
    fun observerSeesStartAndStop() =
        runMateTest { mateScope ->
            val source = MutableSharedFlow<String>()
            val handler = WindowHandler(source)
            val observer = RecordingObserver<S, Msg, Effect>()
            val mate = buildMate(mateScope, handler, observers = listOf(observer))

            mate.accept(Msg.Watch(5))
            mate.accept(Msg.Unwatch(5))
            testScheduler.advanceUntilIdle()

            assertTrue(observer.events.contains("sub-start:${WindowSub.Window(5, 1)}"))
            assertTrue(observer.events.contains("sub-stop:${WindowSub.Window(5, 1)}"))
        }

    @Test
    fun failingFlowGoesToPolicyAndObserver() =
        runMateTest { mateScope ->
            val errors = mutableListOf<MateError>()
            val observer = RecordingObserver<S, Msg, Effect>()
            val explosive =
                object : MateSubscriptionHandler<Msg, WindowSub> {
                    override val subFamily: KClass<WindowSub> = WindowSub::class

                    override fun flow(sub: WindowSub): Flow<Msg> = flow { error("stream burst") }
                }
            val mate =
                buildMate(
                    mateScope,
                    explosive,
                    observers = listOf(observer),
                    failPolicy = { errors += it },
                )

            mate.accept(Msg.Watch(1))
            testScheduler.advanceUntilIdle()

            assertTrue(errors.single() is MateError.SubscriptionFailed)
            assertTrue(observer.events.any { it.startsWith("sub-error:") })
        }

    @Test
    fun orphanSubscriptionFailsLoudly() =
        runMateTest { mateScope ->
            val errors = mutableListOf<MateError>()
            val handler = WindowHandler(MutableSharedFlow())
            Mate<S, Msg, Effect>(
                initState = S(),
                reducer = Reducer(),
                initEffects = emptySet(),
                effectHandlers = emptyList(),
                subscriptions = { setOf(StraySub) },
                subscriptionHandlers = listOf(handler),
                failPolicy = { errors += it },
                coroutineScope = mateScope,
            )
            testScheduler.advanceUntilIdle()

            val orphan = errors.single() as MateError.OrphanSubscription
            assertEquals(StraySub, orphan.sub)
        }
}
