package io.github.kilgoret.mate

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MateRoutingTest {
    private data class S(val handled: List<String> = emptyList())

    private sealed interface Msg {
        data class Handled(val by: String) : Msg

        data class Send(val effect: Effect) : Msg
    }

    private sealed interface ApiFx : Effect {
        data object Load : ApiFx
    }

    private sealed interface UiFx : Effect {
        data object Toast : UiFx
    }

    /** Сирота: семейство, которое никто не заявит. */
    private data object StrayFx : Effect

    /** Двойная принадлежность — запрещённый кейс. */
    private data object TwoFacedFx : ApiFx, UiFx

    private class Reducer : MateReducer<S, Msg, Effect> {
        override fun reduce(
            state: S,
            message: Msg,
        ): ReducerResult<S, Effect> =
            when (message) {
                is Msg.Handled -> state.copy(handled = state.handled + message.by) to emptySet()
                is Msg.Send -> state to setOf(message.effect)
            }
    }

    private class ApiHandler : MateEffectHandler<Msg, ApiFx> {
        override val effectFamily: KClass<ApiFx> = ApiFx::class

        override suspend fun runEffect(
            effect: ApiFx,
            consumer: (Msg) -> Unit,
        ) = consumer(Msg.Handled("api"))
    }

    private class UiHandler : MateEffectHandler<Msg, UiFx> {
        override val effectFamily: KClass<UiFx> = UiFx::class

        override suspend fun runEffect(
            effect: UiFx,
            consumer: (Msg) -> Unit,
        ) = consumer(Msg.Handled("ui"))
    }

    private fun mate(
        scope: kotlinx.coroutines.CoroutineScope,
        handlers: List<MateEffectHandler<Msg, *>>,
        failPolicy: MateFailPolicy = MateFailPolicy.Strict,
    ) = Mate<S, Msg, Effect>(
        initState = S(),
        reducer = Reducer(),
        initEffects = emptySet(),
        effectHandlers = handlers,
        failPolicy = failPolicy,
        coroutineScope = scope,
    )

    @Test
    fun effectGoesToExactlyItsFamilyHandler() =
        runMateTest { mateScope ->
            val mate = mate(mateScope, listOf(ApiHandler(), UiHandler()))

            mate.accept(Msg.Send(ApiFx.Load))
            mate.accept(Msg.Send(UiFx.Toast))
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("api", "ui"), mate.state.value.handled)
        }

    @Test
    fun duplicateFamilyFailsOnCreation() =
        runMateTest { mateScope ->
            assertFailsWith<IllegalArgumentException> {
                mate(mateScope, listOf(ApiHandler(), ApiHandler()))
            }
        }

    @Test
    fun orphanEffectFailsLoudlyOnDispatch() =
        runMateTest { mateScope ->
            val errors = mutableListOf<MateError>()
            val mate = mate(mateScope, listOf(ApiHandler()), failPolicy = { errors += it })

            mate.accept(Msg.Send(StrayFx))
            testScheduler.advanceUntilIdle()

            val orphan = errors.single() as MateError.OrphanEffect
            assertEquals(StrayFx, orphan.effect)
        }

    @Test
    fun ambiguousEffectFailsLoudlyOnDispatch() =
        runMateTest { mateScope ->
            val errors = mutableListOf<MateError>()
            val mate =
                mate(
                    mateScope,
                    listOf(ApiHandler(), UiHandler()),
                    failPolicy = { errors += it },
                )

            mate.accept(Msg.Send(TwoFacedFx))
            testScheduler.advanceUntilIdle()

            val ambiguous = errors.single() as MateError.AmbiguousEffect
            assertEquals(TwoFacedFx, ambiguous.effect)
            assertEquals(2, ambiguous.families.size)
            // Ничего не исполнено — намерение не «повезло кому-то одному».
            assertTrue(mate.state.value.handled.isEmpty())
        }

    @Test
    fun resolveIsCachedPerConcreteClass() =
        runMateTest { mateScope ->
            val mate = mate(mateScope, listOf(ApiHandler(), UiHandler()))

            repeat(3) { mate.accept(Msg.Send(ApiFx.Load)) }
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("api", "api", "api"), mate.state.value.handled)
        }
}
