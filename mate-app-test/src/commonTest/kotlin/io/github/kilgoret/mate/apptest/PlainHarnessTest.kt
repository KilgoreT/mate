package io.github.kilgoret.mate.apptest

import io.github.kilgoret.mate.Effect
import io.github.kilgoret.mate.Mate
import kotlin.test.Test
import kotlin.test.assertEquals

class PlainHarnessTest {
    private val registry =
        screenRegistry {
            on<CounterScreen> { screen, context ->
                ScreenNode(
                    screen = screen,
                    runners =
                        listOf(
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
        }

    @Test
    fun plainNodeSendAndExpect() {
        runAppScenario(registry, miniNavGraph) {
            launch(CounterScreen)
            send(PanelMsg.SetNote("x"))
            expectState<PanelState> { assertEquals("x", it.note) }
        }
    }
}
