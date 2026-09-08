package io.github.kilgoret.mate.apptest

import io.github.kilgoret.mate.Effect
import io.github.kilgoret.mate.navigation.NavGraph
import io.github.kilgoret.mate.navigation.Screen
import io.github.kilgoret.mate.test.runMateTest
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration

/**
 * Точка входа сценарного теста: поднимает харнес на виртуальном
 * времени (`runMateTest`) и исполняет [block] в DSL [ScenarioScope].
 *
 * ```
 * runAppScenario(registry, appNavGraph) {
 *     launch(AppScreen.Main)
 *     send(Msg.AddWordClick)
 *     expectEffect(DatasourceEffect.CreateWord("dom"))
 *     expectState<WordsTabState> { ... }
 *     expectScreen(AppScreen.WordCard(42))
 *     back()
 * }
 * ```
 */
public fun runAppScenario(
    registry: ScreenRegistry,
    graph: NavGraph,
    block: suspend ScenarioScope.() -> Unit,
): TestResult =
    runMateTest { mateScope ->
        val harness = AppHarness(registry, graph, mateScope)
        harness.attach()
        ScenarioScope(harness, this).block()
    }

/**
 * DSL сценария. Каждый шаг, меняющий мир ([launch]/[send]/[back]/
 * [advanceTimeBy]), прогоняет виртуальное время до полного затишья —
 * ассерты всегда видят систему после ВСЕГО каскада (reduce → эффекты →
 * outcome-Msg → reduce → дифф подписок), детерминизм даёт FIFO
 * mailbox'а.
 *
 * Падение любого ассерта дополняется полной лентой событий сценария
 * ([RecordingObserver.renderTrace]) — что происходило во всех раннерах
 * до момента падения.
 */
public class ScenarioScope internal constructor(
    private val harness: AppHarness,
    private val testScope: TestScope,
) {
    /** Единая лента событий сценария (для нестандартных проверок). */
    public val recording: RecordingObserver get() = harness.recording

    /** Экраны стека снизу вверх. */
    public val screens: List<Screen> get() = harness.screens

    /**
     * Исполнить все ГОТОВЫЕ задачи, не продвигая виртуальное время:
     * каскад «reduce → эффекты → outcome-Msg → reduce» дорабатывает
     * до конца, а спящие тикеры/дебаунсы остаются спать — их будит
     * только явный [advanceTimeBy] (advanceUntilIdle с вечным тикером
     * крутил бы время бесконечно).
     */
    public fun awaitIdle() {
        testScope.testScheduler.runCurrent()
    }

    /** Положить экран на стек напрямую (стартовая точка сценария). */
    public fun launch(screen: Screen) {
        harness.push(screen)
        awaitIdle()
    }

    /**
     * Доставить сообщение раннеру верхнего узла (по семейству
     * сообщений) и дождаться конца каскада.
     */
    public fun send(message: Any) {
        harness.send(message)
        awaitIdle()
    }

    /** Системная «назад»: снять верхний узел стека. */
    public fun back() {
        harness.pop()
        awaitIdle()
    }

    /** Сдвинуть виртуальное время (тикеры, дебаунсы) и доработать каскады. */
    public fun advanceTimeBy(duration: Duration) {
        testScope.testScheduler.advanceTimeBy(duration)
        awaitIdle()
    }

    init {
        // Затишье на старте сценария: init-эффекты/подписки экрана,
        // созданного до первого шага DSL, дорабатывают немедленно.
        awaitIdle()
    }

    /**
     * Ассерты над state раннера верхнего узла, чей state имеет тип [S]
     * (типа достаточно — в узле состояния экранов различимы; при
     * настоящей неоднозначности адресуйся [runner]).
     */
    public inline fun <reified S : Any> expectState(block: (S) -> Unit) {
        val states = currentStates()
        val matches = states.values.filterIsInstance<S>()
        when (matches.size) {
            1 -> withTrace { block(matches.single()) }
            0 ->
                failWithTrace(
                    "No runner state of type ${S::class.simpleName} on top node; " +
                        "states: ${states.mapValues { it.value?.let { v -> v::class.simpleName } }}",
                )
            else ->
                failWithTrace(
                    "State type ${S::class.simpleName} is ambiguous on top node — use runner(name)",
                )
        }
    }

    /** State раннера верхнего узла по имени. */
    public fun runnerState(name: String): Any? = harness.currentNode.runner(name).holder.state.value

    /** Проверить стек экранов: верхний равен [screen]. */
    public fun expectScreen(screen: Screen) {
        withTrace {
            val top = harness.screens.lastOrNull()
            if (top != screen) {
                throw AssertionError("Expected top screen $screen, but was $top; stack=${harness.screens}")
            }
        }
    }

    /**
     * Ожидать эффект [effect] дальше по ленте (курсор строго вперёд —
     * один эффект не матчится дважды).
     */
    public fun expectEffect(effect: Effect) {
        withTrace {
            val events = recording.events
            var index = recording.cursor
            while (index < events.size) {
                val event = events[index]
                if (event is HarnessEvent.EffectStarted && event.effect == effect) {
                    recording.cursor = index + 1
                    return@withTrace
                }
                index++
            }
            throw AssertionError("Effect $effect not found after cursor ${recording.cursor}")
        }
    }

    /** Убедиться, что эффект [effect] дальше по ленте НЕ появлялся. */
    public fun expectNoEffect(effect: Effect) {
        withTrace {
            val found =
                recording.events
                    .drop(recording.cursor)
                    .any { it is HarnessEvent.EffectStarted && it.effect == effect }
            if (found) {
                throw AssertionError("Effect $effect was dispatched, but expected not to be")
            }
        }
    }

    @PublishedApi
    internal fun currentStates(): Map<String, Any?> = harness.currentNode.runners.associate { it.name to it.holder.state.value }

    /** Дополнить любое падение полной лентой сценария. */
    @PublishedApi
    internal inline fun withTrace(block: () -> Unit) {
        try {
            block()
        } catch (error: AssertionError) {
            throw AssertionError(
                "${error.message}\n\n${recording.renderTrace()}",
                error,
            )
        }
    }

    @PublishedApi
    internal fun failWithTrace(message: String): Nothing = throw AssertionError("$message\n\n${recording.renderTrace()}")
}
