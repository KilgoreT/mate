package io.github.kilgoret.mate.apptest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class AppScenarioTest {
    @Test
    fun launchSendAndCascadeThroughEffect() {
        val persisted = mutableListOf<Int>()
        runAppScenario(miniRegistry(live = stubFlow(), persist = { persisted += it }), miniNavGraph) {
            launch(CounterScreen)

            send(CounterMsg.Inc)

            expectState<CounterState> { assertEquals(1, it.value) }
            expectEffect(CounterEffect.Persist(1))
            assertEquals(listOf(1), persisted)
        }
    }

    @Test
    fun navigationPushesAndPopsNodesThroughSharedTable() {
        runAppScenario(miniRegistry(live = stubFlow()), miniNavGraph) {
            launch(CounterScreen)
            assertEquals(listOf<Any>(CounterScreen), screens)

            // Nav-эффект из reducer'а → таблица → узел деталей на стеке.
            send(CounterMsg.OpenDetails(7))
            expectScreen(DetailsScreen(7))
            expectState<DetailsState> { assertEquals(7, it.id) }

            // Back-эффект экрана деталей — та же таблица, pop узла.
            send(DetailsMsg.Close)
            expectScreen(CounterScreen)
        }
    }

    @Test
    fun systemBackPopsTopNode() {
        runAppScenario(miniRegistry(live = stubFlow()), miniNavGraph) {
            launch(CounterScreen)
            send(CounterMsg.OpenDetails(1))
            expectScreen(DetailsScreen(1))

            back()

            expectScreen(CounterScreen)
        }
    }

    @Test
    fun stubFlowDrivesLiveSubscription() {
        val live = stubFlow<List<String>>()
        runAppScenario(miniRegistry(live = live), miniNavGraph) {
            launch(CounterScreen)
            expectState<CounterState> { assertEquals(emptyList(), it.live) }

            // Сценарий «играет базу»: эмиссия → подписка переэмитила.
            live.emit(listOf("dom"))
            awaitIdle()
            expectState<CounterState> { assertEquals(listOf("dom"), it.live) }

            live.emit(listOf("dom", "sad"))
            awaitIdle()
            expectState<CounterState> { assertEquals(listOf("dom", "sad"), it.live) }
        }
    }

    @Test
    fun virtualTimeDrivesTicker() {
        runAppScenario(miniRegistry(live = stubFlow()), miniNavGraph) {
            launch(CounterScreen)
            expectState<CounterState> { assertEquals(0, it.ticks) }

            advanceTimeBy(3.seconds)

            expectState<CounterState> { assertEquals(3, it.ticks) }
        }
    }

    @Test
    fun sendRoutesByMessageFamilyInsideNode() {
        runAppScenario(miniRegistry(live = stubFlow()), miniNavGraph) {
            launch(CounterScreen)

            // Два раннера в узле: сообщение находит своего по семейству.
            send(PanelMsg.SetNote("hello"))
            send(CounterMsg.Inc)

            expectState<PanelState> { assertEquals("hello", it.note) }
            expectState<CounterState> { assertEquals(1, it.value) }
        }
    }

    @Test
    fun sendWithoutMatchingRunnerFailsLoudly() {
        runAppScenario(miniRegistry(live = stubFlow()), miniNavGraph) {
            launch(CounterScreen)
            send(CounterMsg.OpenDetails(1))

            // На узле деталей семейства CounterMsg нет — громкий fail.
            val error =
                assertFailsWith<IllegalStateException> {
                    send(CounterMsg.Inc)
                }
            assertTrue(error.message!!.contains("No runner"))
        }
    }

    @Test
    fun popKillsNodeSubscriptions() {
        val live = stubFlow<List<String>>()
        runAppScenario(miniRegistry(live = live), miniNavGraph) {
            launch(CounterScreen)
            send(CounterMsg.OpenDetails(1))
            back() // детали умерли

            back() // counter умер, стек пуст
            val reducedBefore = recording.events.count { it is HarnessEvent.Reduced }

            // Эмиссия в мёртвую подписку не рождает новых reduce.
            live.emit(listOf("ghost"))
            awaitIdle()
            val reducedAfter = recording.events.count { it is HarnessEvent.Reduced }
            assertEquals(reducedBefore, reducedAfter)
        }
    }

    @Test
    fun failedAssertCarriesFullTrace() {
        val error =
            assertFailsWith<AssertionError> {
                runAppScenario(miniRegistry(live = stubFlow()), miniNavGraph) {
                    launch(CounterScreen)
                    send(CounterMsg.Inc)
                    expectState<CounterState> { assertEquals(99, it.value) }
                }
            }
        assertTrue(error.message!!.contains("harness trace"))
        assertTrue(error.message!!.contains("Persist"))
    }

    @Test
    fun expectEffectCursorMovesStrictlyForward() {
        runAppScenario(miniRegistry(live = stubFlow()), miniNavGraph) {
            launch(CounterScreen)
            send(CounterMsg.Inc)
            send(CounterMsg.Inc)

            expectEffect(CounterEffect.Persist(1))
            expectEffect(CounterEffect.Persist(2))
            // Повторное ожидание уже пройденного эффекта — fail.
            assertFailsWith<AssertionError> {
                expectEffect(CounterEffect.Persist(1))
            }
        }
    }

    @Test
    fun expectNoEffectSeesOnlyUnconsumedTail() {
        runAppScenario(miniRegistry(live = stubFlow()), miniNavGraph) {
            launch(CounterScreen)
            send(CounterMsg.Inc)

            expectEffect(CounterEffect.Persist(1))
            expectNoEffect(CounterEffect.Persist(2))
        }
    }
}
