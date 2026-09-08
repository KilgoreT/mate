package io.github.kilgoret.mate.apptest

import io.github.kilgoret.mate.MateStateHolder
import io.github.kilgoret.mate.navigation.Screen
import kotlin.reflect.KClass

/**
 * Именованный раннер внутри узла стека харнеса.
 *
 * @param name человекочитаемое имя для адресации и трассы («words»,
 *   «groups», «appbar»).
 * @param messageFamily корневой Message-тип раннера: [ScenarioScope.send]
 *   маршрутизирует сообщение раннеру узла, чьему семейству оно
 *   принадлежит — та же табличная идея, что у роутинга эффектов.
 * @param holder сам раннер (state + accept).
 */
public class RunnerSlot(
    public val name: String,
    public val messageFamily: KClass<*>,
    public val holder: MateStateHolder<*, *>,
)

/**
 * Узел стека харнеса — НАБОР раннеров, живущих на «экране»
 * одновременно (host + вкладки + виджеты). Плоский экран — частный
 * случай узла с одним раннером; никакой другой модели у харнеса нет,
 * поэтому любая топология приложения описывается одинаково.
 *
 * @param screen данные экрана, породившие узел (аргументы — внутри).
 * @param runners раннеры узла; имена уникальны, семейства сообщений
 *   не пересекаются (иначе send неоднозначен — fail при создании).
 */
public class ScreenNode(
    public val screen: Screen,
    public val runners: List<RunnerSlot>,
) {
    init {
        require(runners.isNotEmpty()) { "ScreenNode for '$screen' has no runners" }
        val names = runners.map { it.name }
        require(names.size == names.toSet().size) {
            "ScreenNode for '$screen' has duplicate runner names: $names"
        }
        val families = runners.map { it.messageFamily }
        require(families.size == families.toSet().size) {
            "ScreenNode for '$screen' has duplicate message families: " +
                families.map { it.simpleName }
        }
    }

    public fun runner(name: String): RunnerSlot =
        runners.find { it.name == name }
            ?: error(
                "No runner '$name' in node '${screen::class.simpleName}'; " +
                    "known: ${runners.map { it.name }}",
            )
}
