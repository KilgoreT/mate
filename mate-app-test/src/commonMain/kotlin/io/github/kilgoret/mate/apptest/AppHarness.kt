package io.github.kilgoret.mate.apptest

import io.github.kilgoret.mate.navigation.MateNavigationHandler
import io.github.kilgoret.mate.navigation.NavCommand
import io.github.kilgoret.mate.navigation.NavGraph
import io.github.kilgoret.mate.navigation.NavigationExecutor
import io.github.kilgoret.mate.navigation.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Ядро харнеса: стек узлов + второй интерпретатор навигации.
 *
 * Навигация исполняется ТЕМ ЖЕ [MateNavigationHandler] и ТОЙ ЖЕ
 * таблицей [NavGraph], что в проде, — отличается только «мышца»
 * ([NavigationExecutor]): Push создаёт узел через [ScreenRegistry] и
 * кладёт на стек, Pop снимает верхний узел и отменяет его scope
 * (циклы раннеров узла умирают). Разойтись с продом таблица не может
 * по построению.
 *
 * Каждый узел живёт на дочернем scope от [scope] сценария; на каждый
 * раннер вешается единая лента [recording].
 */
public class AppHarness internal constructor(
    private val registry: ScreenRegistry,
    graph: NavGraph,
    private val scope: CoroutineScope,
) {
    /** Единая лента событий всех раннеров сценария. */
    public val recording: RecordingObserver = RecordingObserver()

    private val stack = mutableListOf<NodeEntry>()

    private class NodeEntry(val node: ScreenNode, val nodeScope: CoroutineScope)

    /**
     * Харнесный nav-handler: кладётся в effectHandlers каждого
     * раннера через [HarnessContext]. Гейт всегда открыт — в тестах
     * lifecycle-пауз нет (само поведение гейта покрыто тестами
     * mate-navigation).
     */
    public val navigationHandler: MateNavigationHandler =
        MateNavigationHandler(
            graph = graph,
            executor = NavigationExecutor { command -> execute(command) },
            readiness = MutableStateFlow(true),
        )

    internal fun attach() {
        navigationHandler.attach(scope)
    }

    private fun execute(command: NavCommand) {
        when (command) {
            is NavCommand.Push -> push(command.screen)
            NavCommand.Pop -> pop()
        }
    }

    /** Положить узел экрана на стек (стартовая точка — launch сценария). */
    internal fun push(screen: Screen) {
        // Job узла — РЕБЁНОК scope сценария: конец сценария гасит все
        // узлы (и их вечные тикеры) даже без явных pop'ов.
        val nodeScope = CoroutineScope(scope.coroutineContext + Job(parent = scope.coroutineContext[Job]))
        val context =
            HarnessContext(
                scope = nodeScope,
                observer = recording,
                navigationHandler = navigationHandler,
            )
        val node = registry.create(screen, context)
        stack += NodeEntry(node, nodeScope)
        recording.onScreenPushed(screen)
    }

    /** Снять верхний узел: раннеры узла умирают вместе с его scope. */
    internal fun pop() {
        check(stack.isNotEmpty()) { "pop on empty screen stack" }
        // removeAt, не removeLast: на JDK21 removeLast линкуется в
        // java.util.List.removeLast и падает на Android/JVM17.
        val entry = stack.removeAt(stack.lastIndex)
        entry.nodeScope.cancel()
        recording.onScreenPopped(entry.node.screen)
    }

    /** Экраны стека снизу вверх. */
    public val screens: List<Screen> get() = stack.map { it.node.screen }

    /** Верхний узел; fail на пустом стеке. */
    public val currentNode: ScreenNode
        get() =
            stack.lastOrNull()?.node
                ?: error("Screen stack is empty: launch(...) a screen first")

    /**
     * Доставить сообщение раннеру ВЕРХНЕГО узла по семейству
     * сообщений (какому [RunnerSlot.messageFamily] принадлежит msg).
     * Нет кандидата или их двое — громкий fail с именами раннеров.
     */
    internal fun send(message: Any) {
        val node = currentNode
        val matches = node.runners.filter { it.messageFamily.isInstance(message) }
        val slot =
            when (matches.size) {
                1 -> matches.single()
                0 ->
                    error(
                        "No runner in node '${node.screen::class.simpleName}' accepts " +
                            "${message::class.simpleName}; families: " +
                            node.runners.map { "${it.name}:${it.messageFamily.simpleName}" },
                    )
                else ->
                    error(
                        "Message ${message::class.simpleName} matches several runners: " +
                            matches.map { it.name },
                    )
            }
        slot.accept(message)
    }

    private fun RunnerSlot.accept(message: Any) {
        @Suppress("UNCHECKED_CAST")
        (holder as io.github.kilgoret.mate.MateStateHolder<Any?, Any>).accept(message)
    }
}
