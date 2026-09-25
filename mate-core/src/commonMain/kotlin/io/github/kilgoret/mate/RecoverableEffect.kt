package io.github.kilgoret.mate

/**
 * Эффект, объявляющий ответ на свой провал. Раннер при исключении из
 * исполнения такого эффекта (кроме отмены) вызывает [onFail] и
 * отправляет полученный Message в цикл вместо ухода ошибки в
 * [MateFailPolicy]; наблюдатели видят это как
 * [MateObserver.onEffectRecovered].
 *
 * Ответ на провал — знание владельца эффекта: маппинг объявлен в
 * типе эффекта, handler остаётся happy-path без catch.
 *
 * Только для одношаговых эффектов: если runEffect эмитит Message
 * до завершения работы, частичная эмиссия плюс recovery дают
 * противоречивое состояние — такой эффект разбивается на цепочку
 * одношаговых через reducer.
 */
public interface RecoverableEffect<out Message> : Effect {
    /**
     * Построить Message о провале. Чистый конструктор: только поля
     * эффекта и [error], никакой работы и обращений вовне —
     * вызывается на диспатчере раннера. Восстановительные действия
     * (fallback, retry) выражаются новым эффектом из reducer'а в
     * ответ на этот Message.
     *
     * Отмена ([kotlinx.coroutines.CancellationException]) сюда не
     * приходит — раннер пробрасывает её без recovery.
     */
    public fun onFail(error: Throwable): Message
}
