package io.github.kilgoret.mate

import kotlin.test.Test
import kotlin.test.assertEquals

class ReducerChainTest {

    private data class S(val log: List<String>)
    private data class E(val name: String) : Effect

    @Test
    fun beginStartsWithNoEffects() {
        val result = S(emptyList()).begin<S, E>()
        assertEquals(S(emptyList()), result.state())
        assertEquals(emptySet(), result.effects())
    }

    @Test
    fun thenRollsStateAndAccumulatesEffects() {
        val result = S(emptyList()).begin<S, E>()
            .then { it.copy(log = it.log + "a") to setOf(E("fromA")) }
            .then { it.copy(log = it.log + "b") to emptySet() }
            .then { it.copy(log = it.log + "c") to setOf(E("fromC")) }

        assertEquals(listOf("a", "b", "c"), result.state().log)
        assertEquals(setOf(E("fromA"), E("fromC")), result.effects())
    }

    @Test
    fun withEffectKeepsStateUntouched() {
        val result = S(listOf("x")).begin<S, E>() withEffect E("branch")
        assertEquals(S(listOf("x")), result.state())
        assertEquals(setOf(E("branch")), result.effects())
    }

    @Test
    fun distinctIntentsCollapseInOneChain() {
        // П1: неразличимый дубль намерения в одном reduce не имеет
        // семантики — множество схлопывает его.
        val result = S(emptyList()).begin<S, E>()
            .then { it to setOf(E("same")) }
            .then { it to setOf(E("same")) }
        assertEquals(1, result.effects().size)
    }
}
