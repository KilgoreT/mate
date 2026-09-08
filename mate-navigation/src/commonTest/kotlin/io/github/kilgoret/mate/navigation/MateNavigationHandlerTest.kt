package io.github.kilgoret.mate.navigation

import io.github.kilgoret.mate.Effect
import io.github.kilgoret.mate.Mate
import io.github.kilgoret.mate.MateReducer
import io.github.kilgoret.mate.NavigationEffect
import io.github.kilgoret.mate.ReducerResult
import io.github.kilgoret.mate.test.runMateTest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration

class MateNavigationHandlerTest {
    private data class DetailsScreen(val id: Long) : Screen

    private sealed interface Nav : NavigationEffect {
        data class OpenDetails(val id: Long) : Nav
    }

    private class RecordingExecutor : NavigationExecutor {
        val executed = mutableListOf<NavCommand>()
        var onExecute: ((NavCommand) -> Unit)? = null

        override fun execute(command: NavCommand) {
            executed += command
            onExecute?.invoke(command)
        }
    }

    private val graph =
        navGraph {
            on<Nav.OpenDetails> { push(DetailsScreen(it.id)) }
            on<NavigationEffect.Back> { pop() }
        }

    @Test
    fun openGateExecutesImmediatelyInFifoOrder() =
        runMateTest { mateScope ->
            val executor = RecordingExecutor()
            val handler =
                MateNavigationHandler(
                    graph = graph,
                    executor = executor,
                    readiness = MutableStateFlow(true),
                )
            handler.attach(mateScope)

            handler.runEffect(Nav.OpenDetails(1)) {}
            handler.runEffect(NavigationEffect.Back) {}
            testScheduler.advanceUntilIdle()

            assertEquals(
                listOf(NavCommand.Push(DetailsScreen(1)), NavCommand.Pop),
                executor.executed,
            )
        }

    @Test
    fun closedGateQueuesAndOpeningDrainsTheBatch() =
        runMateTest { mateScope ->
            val executor = RecordingExecutor()
            val gate = MutableStateFlow(false)
            val handler =
                MateNavigationHandler(
                    graph = graph,
                    executor = executor,
                    readiness = gate,
                )
            handler.attach(mateScope)

            handler.runEffect(Nav.OpenDetails(1)) {}
            handler.runEffect(Nav.OpenDetails(2)) {}
            testScheduler.advanceUntilIdle()
            assertEquals(emptyList<NavCommand>(), executor.executed)

            gate.value = true
            testScheduler.advanceUntilIdle()
            assertEquals(
                listOf<NavCommand>(
                    NavCommand.Push(DetailsScreen(1)),
                    NavCommand.Push(DetailsScreen(2)),
                ),
                executor.executed,
            )
        }

    @Test
    fun gateIsRereadBetweenQueueItems() =
        runMateTest { mateScope ->
            val executor = RecordingExecutor()
            val gate = MutableStateFlow(false)
            // Первый же исполненный переход захлопывает гейт — второй
            // обязан остаться в очереди до повторного открытия.
            executor.onExecute = { gate.value = false }
            val handler =
                MateNavigationHandler(
                    graph = graph,
                    executor = executor,
                    readiness = gate,
                )
            handler.attach(mateScope)

            handler.runEffect(Nav.OpenDetails(1)) {}
            handler.runEffect(Nav.OpenDetails(2)) {}
            gate.value = true
            testScheduler.advanceUntilIdle()
            assertEquals(listOf<NavCommand>(NavCommand.Push(DetailsScreen(1))), executor.executed)

            gate.value = true
            testScheduler.advanceUntilIdle()
            assertEquals(
                listOf<NavCommand>(
                    NavCommand.Push(DetailsScreen(1)),
                    NavCommand.Push(DetailsScreen(2)),
                ),
                executor.executed,
            )
        }

    @Test
    fun staleEffectIsDroppedAndReported() =
        runMateTest { mateScope ->
            val executor = RecordingExecutor()
            val gate = MutableStateFlow(false)
            val dropped = mutableListOf<NavigationEffect>()
            val handler =
                MateNavigationHandler(
                    graph = graph,
                    executor = executor,
                    readiness = gate,
                    // Нулевой срок годности: любой отложенный переход
                    // протухает к моменту дренажа.
                    staleness = Duration.ZERO,
                    onDropped = { dropped += it },
                )
            handler.attach(mateScope)

            handler.runEffect(Nav.OpenDetails(1)) {}
            gate.value = true
            testScheduler.advanceUntilIdle()

            assertEquals(emptyList<NavCommand>(), executor.executed)
            assertEquals(listOf<NavigationEffect>(Nav.OpenDetails(1)), dropped)
        }

    @Test
    fun effectWithoutRouteFailsLoudlyOnDispatch() =
        runMateTest { mateScope ->
            val handler =
                MateNavigationHandler(
                    graph = navGraph { on<NavigationEffect.Back> { pop() } },
                    executor = RecordingExecutor(),
                    readiness = MutableStateFlow(true),
                )
            handler.attach(mateScope)

            assertFailsWith<IllegalStateException> {
                handler.runEffect(Nav.OpenDetails(1)) {}
            }
        }

    @Test
    fun queueSurvivesDetachAndResumesOnReattach() =
        runMateTest { mateScope ->
            val executor = RecordingExecutor()
            val gate = MutableStateFlow(false)
            val handler =
                MateNavigationHandler(
                    graph = graph,
                    executor = executor,
                    readiness = gate,
                )
            handler.attach(mateScope)
            handler.runEffect(Nav.OpenDetails(1)) {}
            testScheduler.advanceUntilIdle()

            // Пересоздание хоста: дренаж умер, намерение — нет.
            handler.detach()
            gate.value = true
            testScheduler.advanceUntilIdle()
            assertEquals(emptyList<NavCommand>(), executor.executed)

            handler.attach(mateScope)
            testScheduler.advanceUntilIdle()
            assertEquals(listOf<NavCommand>(NavCommand.Push(DetailsScreen(1))), executor.executed)
        }

    // ===== Интеграция с раннером: shared handler в Mate конкретного Msg =====

    private data class S(val value: Int)

    private sealed interface Msg {
        data object Go : Msg
    }

    private class NavOnlyReducer : MateReducer<S, Msg, Effect> {
        override fun reduce(
            state: S,
            message: Msg,
        ): ReducerResult<S, Effect> = state to setOf(NavigationEffect.Back)
    }

    @Test
    fun sharedHandlerPlugsIntoTypedMateRunner() =
        runMateTest { mateScope ->
            val executor = RecordingExecutor()
            val handler =
                MateNavigationHandler(
                    graph = graph,
                    executor = executor,
                    readiness = MutableStateFlow(true),
                )
            handler.attach(mateScope)
            val mate =
                Mate<S, Msg, Effect>(
                    initState = S(0),
                    reducer = NavOnlyReducer(),
                    initEffects = emptySet(),
                    effectHandlers = listOf(handler),
                    coroutineScope = mateScope,
                )

            mate.accept(Msg.Go)
            testScheduler.advanceUntilIdle()

            assertEquals(listOf<NavCommand>(NavCommand.Pop), executor.executed)
        }
}
