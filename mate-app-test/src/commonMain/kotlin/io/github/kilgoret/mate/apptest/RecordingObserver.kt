package io.github.kilgoret.mate.apptest

import io.github.kilgoret.mate.Effect
import io.github.kilgoret.mate.MateObserver
import io.github.kilgoret.mate.Sub
import io.github.kilgoret.mate.navigation.Screen
import kotlin.time.Duration

/**
 * Событие ленты харнеса — то, что произошло в каком-то раннере
 * какого-то узла (плюс навигационные вехи стека). Лента едина для
 * всего сценария: по ней читается полная история «Msg → reduce →
 * эффекты → каузальность» всех экранов.
 */
public sealed interface HarnessEvent {
    public data class MessageReceived(val message: Any?, val stateBefore: Any?) : HarnessEvent

    public data class Reduced(
        val message: Any?,
        val before: Any?,
        val after: Any?,
        val effects: Set<Effect>,
    ) : HarnessEvent

    public data class EffectStarted(val effect: Effect) : HarnessEvent

    public data class EffectFinished(val effect: Effect, val duration: Duration) : HarnessEvent

    public data class EffectFailed(val effect: Effect, val error: Throwable) : HarnessEvent

    public data class CausedMessage(val parent: Effect, val message: Any?) : HarnessEvent

    public data class SubscriptionStarted(val sub: Sub) : HarnessEvent

    public data class SubscriptionStopped(val sub: Sub) : HarnessEvent

    public data class SubscriptionError(val sub: Sub, val error: Throwable) : HarnessEvent

    public data class ScreenPushed(val screen: Screen) : HarnessEvent

    public data class ScreenPopped(val screen: Screen) : HarnessEvent
}

/**
 * Единая лента событий сценария поверх [MateObserver]: один инстанс
 * вешается на КАЖДЫЙ раннер каждого узла (контравариантность
 * observer'а), навигационные вехи дописывает харнес.
 *
 * [cursor] — позиция «прочитано до сих пор» для expectEffect:
 * ожидания эффектов идут по ленте строго вперёд, один эффект не
 * матчится дважды.
 *
 * При падении ассерта [renderTrace] отдаёт всю историю — падение
 * сценария самодиагностично.
 */
public class RecordingObserver internal constructor() : MateObserver<Any?, Any?, Effect> {
    private val _events = mutableListOf<HarnessEvent>()

    /** Полная лента с начала сценария. */
    public val events: List<HarnessEvent> get() = _events

    internal var cursor: Int = 0

    override fun onMessage(
        message: Any?,
        stateBefore: Any?,
    ) {
        _events += HarnessEvent.MessageReceived(message, stateBefore)
    }

    override fun onReduced(
        message: Any?,
        before: Any?,
        after: Any?,
        effects: Set<Effect>,
    ) {
        _events += HarnessEvent.Reduced(message, before, after, effects)
    }

    override fun onEffectStarted(effect: Effect) {
        _events += HarnessEvent.EffectStarted(effect)
    }

    override fun onEffectFinished(
        effect: Effect,
        duration: Duration,
    ) {
        _events += HarnessEvent.EffectFinished(effect, duration)
    }

    override fun onEffectFailed(
        effect: Effect,
        error: Throwable,
    ) {
        _events += HarnessEvent.EffectFailed(effect, error)
    }

    override fun onCausedMessage(
        parent: Effect,
        message: Any?,
    ) {
        _events += HarnessEvent.CausedMessage(parent, message)
    }

    override fun onSubscriptionStarted(sub: Sub) {
        _events += HarnessEvent.SubscriptionStarted(sub)
    }

    override fun onSubscriptionStopped(sub: Sub) {
        _events += HarnessEvent.SubscriptionStopped(sub)
    }

    override fun onSubscriptionError(
        sub: Sub,
        error: Throwable,
    ) {
        _events += HarnessEvent.SubscriptionError(sub, error)
    }

    internal fun onScreenPushed(screen: Screen) {
        _events += HarnessEvent.ScreenPushed(screen)
    }

    internal fun onScreenPopped(screen: Screen) {
        _events += HarnessEvent.ScreenPopped(screen)
    }

    /** Полная лента, пронумерованная, с маркером курсора. */
    public fun renderTrace(): String =
        buildString {
            appendLine("=== harness trace (${_events.size} events, cursor=$cursor) ===")
            _events.forEachIndexed { index, event ->
                val marker = if (index == cursor) ">" else " "
                appendLine("$marker[$index] $event")
            }
        }
}
