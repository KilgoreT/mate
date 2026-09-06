package io.github.kilgoret.mate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * runTest-обёртка для тестов раннера: вечный mailbox-цикл живёт в
 * ДОЧЕРНЕМ scope на том же testScheduler (advanceUntilIdle его гонит,
 * в отличие от backgroundScope, чьи задачи при активном теле теста не
 * исполняются) и гарантированно отменяется по концу теста.
 */
internal fun runMateTest(block: suspend TestScope.(mateScope: CoroutineScope) -> Unit) =
    runTest {
        val mateScope = CoroutineScope(coroutineContext + Job())
        try {
            block(mateScope)
        } finally {
            mateScope.cancel()
        }
    }
