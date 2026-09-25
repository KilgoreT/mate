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

class RunSuspendCatchingTest {
    @Test
    fun successIsWrapped() {
        assertEquals(Result.success(42), runSuspendCatching { 42 })
    }

    @Test
    fun failureIsWrapped() {
        val result = runSuspendCatching { error("boom") }
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun cancellationIsRethrownNotWrapped() {
        assertFailsWith<CancellationException> {
            runSuspendCatching { throw CancellationException("cancelled") }
        }
    }

    @Test
    fun realCoroutineCancellationPropagates() = runTest {
        // Отмена живой корутины проходит сквозь хелпер, а не
        // оседает в Result.failure.
        var wrappedAsFailure = false
        coroutineScope {
            val job = launch {
                val result = runSuspendCatching { delay(10_000) }
                wrappedAsFailure = result.isFailure
            }
            testScheduler.runCurrent()
            job.cancel()
        }
        assertEquals(false, wrappedAsFailure)
    }
}
