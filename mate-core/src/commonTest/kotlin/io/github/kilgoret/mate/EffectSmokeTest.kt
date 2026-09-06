package io.github.kilgoret.mate

import kotlin.test.Test
import kotlin.test.assertEquals

/** Э0-смоук: commonTest компилируется и гоняется на всех таргетах. */
class EffectSmokeTest {

    private data class Ping(val id: Long) : Effect

    @Test
    fun distinctIntentsFormASet() {
        // П1: неразличимый дубль намерения схлопывается множеством.
        val effects: Set<Effect> = setOf(Ping(1), Ping(1), Ping(2))
        assertEquals(2, effects.size)
    }
}
