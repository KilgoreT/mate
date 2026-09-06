package io.github.kilgoret.mate.test

import io.github.kilgoret.mate.Effect
import io.github.kilgoret.mate.MateReducer
import io.github.kilgoret.mate.ReducerResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MateTestHelperTest {
    private data class S(val value: Int)

    private data class Fx(val tag: String) : Effect

    private data class Inc(val amount: Int)

    private val reducer =
        object : MateReducer<S, Inc, Effect> {
            override fun reduce(
                state: S,
                message: Inc,
            ): ReducerResult<S, Effect> = S(state.value + message.amount) to setOf(Fx("inc-${message.amount}"))
        }

    @Test
    fun positiveAssertionsPass() {
        val result: ReducerResult<S, Effect> = S(1) to setOf(Fx("a"))

        result.assertState(S(1))
        result.assertEffects(setOf(Fx("a")))
        result.assertEffectsCount(1)
        result.assertSingleEffect<Fx>()
        result.assertHasEffect<Fx>()

        val empty: ReducerResult<S, Effect> = S(1) to emptySet()
        empty.assertNoEffects()
    }

    @Test
    fun failingAssertionsThrow() {
        val result: ReducerResult<S, Effect> = S(1) to setOf(Fx("a"))

        assertFailsWith<AssertionError> { result.assertState(S(2)) }
        assertFailsWith<AssertionError> { result.assertEffects(emptySet()) }
        assertFailsWith<AssertionError> { result.assertNoEffects() }
        assertFailsWith<AssertionError> { result.assertEffectsCount(2) }
    }

    @Test
    fun testReduceRunsSingleStep() {
        val result = reducer.testReduce(S(10), Inc(5))
        result.assertState(S(15))
        result.assertHasEffect<Fx>()
    }

    @Test
    fun testScenarioRollsStateBetweenMessages() {
        val results = reducer.testScenario(S(0), Inc(1), Inc(2), Inc(3))

        assertEquals(3, results.size)
        results[0].assertState(S(1))
        results[1].assertState(S(3))
        results[2].assertState(S(6))
    }

    @Test
    fun stateBuilderAppliesModificationsInOrder() {
        val built =
            S(0).toBuilder()
                .modify { it.copy(value = it.value + 1) }
                .modify { it.copy(value = it.value * 10) }
                .build()

        assertEquals(S(10), built)
    }
}
