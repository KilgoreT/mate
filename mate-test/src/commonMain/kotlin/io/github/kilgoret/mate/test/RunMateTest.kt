package io.github.kilgoret.mate.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * runTest-обёртка для тестов с раннером Mate.
 *
 * Вечный mailbox-цикл Mate должен жить в scope, чьи задачи гонит
 * `advanceUntilIdle` — задачи `backgroundScope` при активном теле
 * теста НЕ исполняются. [runMateTest] даёт готовый [mateScope]:
 * дочерний scope на testScheduler'е, гарантированно отменяемый по
 * концу теста.
 *
 * ```
 * @Test
 * fun myScreenTest() = runMateTest { mateScope ->
 *     val mate = Mate(..., coroutineScope = mateScope)
 *     mate.accept(Msg.Load)
 *     testScheduler.advanceUntilIdle()
 *     assertEquals(expected, mate.state.value)
 * }
 * ```
 */
public fun runMateTest(block: suspend TestScope.(mateScope: CoroutineScope) -> Unit): TestResult =
    runTest {
        val mateScope = CoroutineScope(coroutineContext + Job())
        try {
            block(mateScope)
        } finally {
            mateScope.cancel()
        }
    }
