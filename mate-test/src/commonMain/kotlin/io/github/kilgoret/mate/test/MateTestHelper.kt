package io.github.kilgoret.mate.test

import io.github.kilgoret.mate.MateReducer
import io.github.kilgoret.mate.ReducerResult
import io.github.kilgoret.mate.effects
import io.github.kilgoret.mate.state
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// ВНИМАНИЕ при сверке с JUnit-версией: в kotlin.test message — ПОСЛЕДНИЙ
// аргумент (assertEquals(expected, actual, message)), у JUnit — первый.

// Test helpers for ReducerResult
public fun <STATE, EFFECTS> ReducerResult<STATE, EFFECTS>.assertState(
    expectedState: STATE,
    message: String = "State should be '$expectedState' but was '${state()}'",
) {
    assertEquals(expectedState, state(), message)
}

public fun <STATE, EFFECTS> ReducerResult<STATE, EFFECTS>.assertEffects(
    expectedEffects: Set<EFFECTS>,
    message: String = "Effects should be $expectedEffects but was ${effects()}",
) {
    assertEquals(expectedEffects, effects(), message)
}

public fun <STATE, EFFECTS> ReducerResult<STATE, EFFECTS>.assertNoEffects(
    message: String = "Should have no effects but had ${effects().size}: ${effects()}",
) {
    assertTrue(effects().isEmpty(), message)
}

public inline fun <reified EFFECT_TYPE> ReducerResult<*, *>.assertSingleEffect(
    message: String = "Should have exactly one ${EFFECT_TYPE::class.simpleName} but had ${effects().size}: ${effects()}",
) {
    assertEquals(1, effects().size, message)
    assertTrue(effects().first() is EFFECT_TYPE, message)
}

public fun <STATE, EFFECTS> ReducerResult<STATE, EFFECTS>.assertEffectsCount(
    expectedCount: Int,
    message: String = "Should have $expectedCount effects but had ${effects().size}: ${effects()}",
) {
    assertEquals(expectedCount, effects().size, message)
}

public inline fun <reified EFFECT_TYPE> ReducerResult<*, *>.assertHasEffect(
    message: String = "Should have ${EFFECT_TYPE::class.simpleName} but had: ${effects()}",
) {
    assertTrue(effects().any { it is EFFECT_TYPE }, message)
}

// Test helpers for MateReducer
public fun <STATE, MESSAGE, EFFECT> MateReducer<STATE, MESSAGE, EFFECT>.testReduce(
    initialState: STATE,
    message: MESSAGE,
): ReducerResult<STATE, EFFECT> {
    return reduce(initialState, message)
}

public fun <STATE, MESSAGE, EFFECT> MateReducer<STATE, MESSAGE, EFFECT>.testScenario(
    initialState: STATE,
    vararg messages: MESSAGE,
): List<ReducerResult<STATE, EFFECT>> {
    var currentState = initialState
    return messages.map { message ->
        val result = reduce(currentState, message)
        currentState = result.state()
        result
    }
}
