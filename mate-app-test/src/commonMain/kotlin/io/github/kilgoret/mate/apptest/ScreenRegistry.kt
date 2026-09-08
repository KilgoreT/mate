package io.github.kilgoret.mate.apptest

import io.github.kilgoret.mate.Effect
import io.github.kilgoret.mate.MateEffectHandler
import io.github.kilgoret.mate.MateObserver
import io.github.kilgoret.mate.NavigationEffect
import io.github.kilgoret.mate.navigation.Screen
import kotlinx.coroutines.CoroutineScope
import kotlin.reflect.KClass

/**
 * Контекст сборки узла — всё, что фабрика обязана вставить в каждый
 * создаваемый раннер:
 *
 * @param scope дочерний scope узла — pop экрана отменяет его, и циклы
 *   раннеров узла умирают вместе с ним.
 * @param observer единая лента харнеса — кладётся в observers каждого
 *   Mate (контравариантность [MateObserver] позволяет один наблюдатель
 *   на все раннеры).
 * @param navigationHandler харнесный исполнитель nav-семейства —
 *   кладётся в effectHandlers каждого Mate вместо продового
 *   (та же таблица переходов, другая «мышца»: стек узлов).
 */
public class HarnessContext internal constructor(
    public val scope: CoroutineScope,
    public val observer: MateObserver<Any?, Any?, Effect>,
    public val navigationHandler: MateEffectHandler<Nothing, NavigationEffect>,
)

/** Фабрика узла: из данных экрана и контекста собирает раннеры. */
public fun interface NodeFactory<S : Screen> {
    public fun create(
        screen: S,
        context: HarnessContext,
    ): ScreenNode
}

/**
 * Реестр «класс экрана → фабрика узла» — единственное, что приложение
 * описывает для харнеса (сборка из тех же Assembly, что использует
 * прод, но со стабами use case'ов). Матч — по конкретному классу
 * экрана; экран без фабрики — громкий fail при пуше.
 */
public class ScreenRegistry internal constructor(
    private val factories: Map<KClass<out Screen>, NodeFactory<Screen>>,
) {
    internal fun create(
        screen: Screen,
        context: HarnessContext,
    ): ScreenNode {
        val factory =
            factories[screen::class]
                ?: error(
                    "No node factory for screen '${screen::class.simpleName}': " +
                        "add it to screenRegistry { on<...> { ... } }",
                )
        return factory.create(screen, context)
    }
}

/** Билдер реестра: по строке [on] на каждый класс экрана. */
public class ScreenRegistryBuilder internal constructor() {
    @PublishedApi
    internal val factories: MutableMap<KClass<out Screen>, NodeFactory<Screen>> = mutableMapOf()

    /** Объявить фабрику узла для экранов класса [S]. */
    public inline fun <reified S : Screen> on(factory: NodeFactory<S>) {
        val previous =
            factories.put(S::class) { screen, context ->
                @Suppress("UNCHECKED_CAST")
                factory.create(screen as S, context)
            }
        require(previous == null) {
            "Node factory for '${S::class.simpleName}' is declared twice: merge them"
        }
    }
}

/** Собрать реестр экранов харнеса. */
public fun screenRegistry(build: ScreenRegistryBuilder.() -> Unit): ScreenRegistry =
    ScreenRegistry(ScreenRegistryBuilder().apply(build).factories.toMap())
