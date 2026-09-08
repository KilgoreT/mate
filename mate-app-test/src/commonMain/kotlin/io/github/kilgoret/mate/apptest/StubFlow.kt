package io.github.kilgoret.mate.apptest

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Управляемый поток для стабов use case'ов: сценарий «играет базу» —
 * эмитит новые значения руками, подписки экранов переэмичиваются как
 * от живой БД.
 *
 * Replay = 1: подписка, стартовавшая позже эмиссии, получает
 * последнее значение (семантика живого запроса к базе).
 */
public class StubFlow<T> internal constructor(
    private val source: MutableSharedFlow<T>,
) : Flow<T> by source {
    /** Эмитить значение всем активным (и будущим — replay) подписчикам. */
    public suspend fun emit(value: T) {
        source.emit(value)
    }
}

/** Создать управляемый стаб-поток. */
public fun <T> stubFlow(): StubFlow<T> = StubFlow(MutableSharedFlow(replay = 1))
