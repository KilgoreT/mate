package io.github.kilgoret.mate.navigation

import io.github.kilgoret.mate.NavigationEffect
import kotlin.reflect.KClass

/**
 * Таблица навигации приложения: «какой эффект куда ведёт», одной
 * строкой на переход. Единственный источник этого знания — её читают
 * и прод-исполнитель, и тестовый харнес, поэтому разойтись они не
 * могут.
 *
 * Создаётся билдером [navTable]; резолв эффекта в список [NavCommand]
 * выполняет [io.github.kilgoret.mate.navigation.MateNavigationHandler].
 * Правила громкости — как у роутинга эффектов: дубль маршрута — fail
 * при создании, эффект без маршрута — fail при диспатче.
 */
public class NavTable internal constructor(
    private val routes: Map<KClass<out NavigationEffect>, NavTableScope.(NavigationEffect) -> Unit>,
) {
    /** Кэш резолва «конкретный класс эффекта → маршрут». */
    private val resolveCache =
        mutableMapOf<KClass<*>, (NavTableScope.(NavigationEffect) -> Unit)?>()

    /**
     * Перевести эффект в команды по таблице; null — маршрута нет
     * (сирота таблицы, громкость решает вызывающий).
     *
     * Матч — по конкретному классу эффекта; если его строки нет,
     * ищется единственная строка-супертип (регистрация подгруппы
     * целиком). Несколько подходящих супертипов — ошибка конфигурации.
     */
    public fun resolve(effect: NavigationEffect): List<NavCommand>? {
        val route = resolveRoute(effect) ?: return null
        val scope = NavTableScope()
        scope.route(effect)
        return scope.commands
    }

    private fun resolveRoute(
        effect: NavigationEffect,
    ): (NavTableScope.(NavigationEffect) -> Unit)? {
        val effectClass = effect::class
        resolveCache[effectClass]?.let { return it }

        routes[effectClass]?.let {
            resolveCache[effectClass] = it
            return it
        }
        val matches = routes.entries.filter { (klass, _) -> klass.isInstance(effect) }
        check(matches.size <= 1) {
            "Navigation effect '${effectClass.simpleName}' matches several routes: " +
                matches.joinToString { it.key.simpleName ?: "?" }
        }
        return matches
            .singleOrNull()
            ?.value
            .also { resolveCache[effectClass] = it }
    }
}

/**
 * Ресивер строки таблицы: внутри лямбды `on<E> { … }` вызовы [push] и
 * [pop] записывают команды перехода (обычно одну; несколько — это
 * составной переход, исполняется по порядку).
 */
public class NavTableScope internal constructor() {
    internal val commands: MutableList<NavCommand> = mutableListOf()

    /** Записать переход на [screen] (данные экрана — с аргументами). */
    public fun push(screen: Screen) {
        commands += NavCommand.Push(screen)
    }

    /** Записать возврат назад. */
    public fun pop() {
        commands += NavCommand.Pop
    }
}

/** Билдер таблицы: по строке [on] на каждый навигационный эффект. */
public class NavTableBuilder internal constructor() {
    @PublishedApi
    internal val routes:
        MutableMap<KClass<out NavigationEffect>, NavTableScope.(NavigationEffect) -> Unit> =
        mutableMapOf()

    /**
     * Объявить маршрут эффекта [E]: лямбда получает сам эффект и
     * записывает команды через [NavTableScope.push]/[NavTableScope.pop].
     * Повторная строка на тот же эффект — ошибка конфигурации.
     */
    public inline fun <reified E : NavigationEffect> on(noinline route: NavTableScope.(E) -> Unit) {
        val previous = routes.put(E::class) { effect ->
            @Suppress("UNCHECKED_CAST")
            route(effect as E)
        }
        require(previous == null) {
            "Navigation route for '${E::class.simpleName}' is declared twice: merge them"
        }
    }
}

/**
 * Собрать таблицу навигации приложения:
 *
 * ```
 * val appNavTable = navTable {
 *     on<WordsNavigationEffect.OpenWordCard> { push(WordCardScreen(it.wordId)) }
 *     on<NavigationEffect.Back> { pop() }
 * }
 * ```
 */
public fun navTable(build: NavTableBuilder.() -> Unit): NavTable = NavTable(
    NavTableBuilder()
        .apply(build)
        .routes
        .toMap(),
)
