package io.github.kilgoret.mate.apptest

import io.github.kilgoret.mate.Effect
import io.github.kilgoret.mate.Mate
import io.github.kilgoret.mate.Sub
import kotlinx.coroutines.flow.Flow
import kotlin.test.Test
import kotlin.test.assertEquals

class SliceDiagTest {
    private fun counterRegistry(
        live: Flow<List<String>>?,
        ticker: Boolean,
        persist: suspend (Int) -> Unit = {},
    ) = screenRegistry {
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
                                    subscriptions =
                                        if (live == null && !ticker) {
                                            null
                                        } else {
                                            {
                                                buildSet<Sub> {
                                                    if (live != null) add(CounterSub.Live)
                                                    if (ticker) add(CounterSub.Ticker)
                                                }
                                            }
                                        },
                                    subscriptionHandlers =
                                        listOf(CounterSubHandler(live ?: stubFlow())),
                                    observers = listOf(context.observer),
                                    coroutineScope = context.scope,
                                ),
                        ),
                    ),
            )
        }
    }

    @Test
    fun counterWithoutSubscriptions() {
        runAppScenario(counterRegistry(live = null, ticker = false), miniNavGraph) {
            launch(CounterScreen)
            send(CounterMsg.Inc)
            expectState<CounterState> { assertEquals(1, it.value) }
        }
    }

    @Test
    fun counterWithLiveOnly() {
        val live = stubFlow<List<String>>()
        runAppScenario(counterRegistry(live = live, ticker = false), miniNavGraph) {
            launch(CounterScreen)
            live.emit(listOf("a"))
            awaitIdle()
            expectState<CounterState> { assertEquals(listOf("a"), it.live) }
        }
    }

    @Test
    fun bareTickerOnRunMateTest() =
        io.github.kilgoret.mate.test.runMateTest { mateScope ->
            val mate =
                Mate<CounterState, CounterMsg, Effect>(
                    initState = CounterState(),
                    reducer = CounterReducer(),
                    initEffects = emptySet(),
                    effectHandlers = emptyList(),
                    subscriptions = { setOf(CounterSub.Ticker) },
                    subscriptionHandlers = listOf(CounterSubHandler(stubFlow())),
                    coroutineScope = mateScope,
                )
            testScheduler.runCurrent()
            assertEquals(0, mate.state.value.ticks)
            testScheduler.advanceTimeBy(2_000)
            testScheduler.runCurrent()
            assertEquals(2, mate.state.value.ticks)
        }

    @Test
    fun tickerInHarnessWithFuse() {
        // Предохранитель: если тикер молотит без сна — стек покажет, кто крутит.
        var fuse = 0
        val fusedTicker =
            kotlinx.coroutines.flow.flow {
                while (true) {
                    check(++fuse <= 10) { "ticker spun $fuse times without sleeping" }
                    kotlinx.coroutines.delay(1_000)
                    emit(CounterMsg.Tick)
                }
            }
        val registry =
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
                                            effectHandlers = emptyList(),
                                            subscriptions = { setOf(CounterSub.Ticker) },
                                            subscriptionHandlers =
                                                listOf(
                                                    object :
                                                        io.github.kilgoret.mate.MateSubscriptionHandler<
                                                            CounterMsg,
                                                            CounterSub,
                                                            > {
                                                        override val subFamily = CounterSub::class

                                                        override fun flow(sub: CounterSub) = fusedTicker
                                                    },
                                                ),
                                            coroutineScope = context.scope,
                                        ),
                                ),
                            ),
                    )
                }
            }
        runAppScenario(registry, miniNavGraph) {
            launch(CounterScreen)
            advanceTimeBy(kotlin.time.Duration.parse("2s"))
            expectState<CounterState> { assertEquals(2, it.ticks) }
        }
    }

    @Test
    fun counterWithTickerOnly() {
        runAppScenario(counterRegistry(live = null, ticker = true), miniNavGraph) {
            launch(CounterScreen)
            advanceTimeBy(kotlin.time.Duration.parse("2s"))
            expectState<CounterState> { assertEquals(2, it.ticks) }
        }
    }
}
