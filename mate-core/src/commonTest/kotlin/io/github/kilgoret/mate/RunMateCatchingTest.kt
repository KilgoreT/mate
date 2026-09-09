package io.github.kilgoret.mate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RunMateCatchingTest {
    @Test
    fun successIsWrapped() {
        assertEquals(Result.success(42), runMateCatching { 42 })
    }

    @Test
    fun failureIsWrapped() {
        val result = runMateCatching { error("boom") }
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun cancellationIsRethrownNotWrapped() {
        assertFailsWith<CancellationException> {
            runMateCatching { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun realCoroutineCancellationPropagates() =
        runTest {
            // Отмена живой корутины проходит сквозь хелпер, а не
            // оседает в Result.failure.
            var wrappedAsFailure = false
            coroutineScope {
                val job =
                    launch {
                        val result = runMateCatching { delay(10_000) }
                        wrappedAsFailure = result.isFailure
                    }
                testScheduler.runCurrent()
                job.cancel()
            }
            assertEquals(false, wrappedAsFailure)
        }
}
