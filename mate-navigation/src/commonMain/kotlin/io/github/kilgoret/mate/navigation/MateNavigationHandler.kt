package io.github.kilgoret.mate.navigation

import io.github.kilgoret.mate.MateEffectHandler
import io.github.kilgoret.mate.NavigationEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * ЕДИНЫЙ исполнитель навигационного семейства — один shared-инстанс
 * на приложение, который кладётся в сборку каждого раннера (навигация
 * — глобальный ресурс: стек один, значит и очередь переходов одна).
 * Типизирован `Nothing` по Message — сообщений в цикл не шлёт, потому
 * совместим с раннером любого экрана (ковариантность
 * [MateEffectHandler]).
 *
 * Цикл жизни эффекта:
 * 1. [runEffect] резолвит эффект по таблице [graph] СРАЗУ — эффект
 *    без маршрута громко валится ещё на диспатче (ошибка
 *    конфигурации, уходит в fail-policy раннера);
 * 2. готовые команды встают в внутреннюю FIFO-очередь БЕЗ лимита —
 *    все nav-эффекты идут через неё даже при открытом гейте: очередь
 *    и есть сериализация («navigate(A); navigate(B)» соберут back
 *    stack в порядке намерений);
 * 3. дренаж-цикл (запускается [attach]) перед КАЖДЫМ элементом ждёт
 *    открытый гейт [readiness] — исполненный переход может сам
 *    захлопнуть гейт, поэтому гейт перечитывается между элементами;
 * 4. команды элемента исполняет [executor] — единственное место,
 *    знающее, что такое экран.
 *
 * Гейт — абстрактная готовность исполнителя (в Android кормится
 * lifecycle: STARTED = true): навигация в закрытый гейт не теряется
 * (намерение свято), а ждёт открытия и исполняется накопленной пачкой.
 *
 * @param graph таблица «эффект → команды» — единственный источник
 *   знания о переходах.
 * @param executor исполнитель команд (прод: NavController-адаптер;
 *   тест: стек раннеров).
 * @param readiness гейт готовности; false — переходы копятся в
 *   очереди, true — исполняются.
 * @param staleness необязательный срок годности отложенного перехода:
 *   элемент, прождавший в очереди дольше, при дренаже отбрасывается
 *   (после долгого фона пачка устаревших переходов может быть
 *   нежеланна). null — переходы не устаревают.
 * @param onDropped наблюдаемость staleness-политики: вызывается на
 *   каждый отброшенный эффект.
 */
public class MateNavigationHandler(
    private val graph: NavGraph,
    private val executor: NavigationExecutor,
    private val readiness: StateFlow<Boolean>,
    private val staleness: Duration? = null,
    private val onDropped: ((NavigationEffect) -> Unit)? = null,
) : MateEffectHandler<Nothing, NavigationEffect> {
    override val effectFamily: KClass<NavigationEffect> = NavigationEffect::class

    private class Queued(
        val effect: NavigationEffect,
        val commands: List<NavCommand>,
        val enqueuedAt: TimeMark,
    )

    /** FIFO-очередь переходов; без лимита — намерения не теряются. */
    private val queue = Channel<Queued>(Channel.UNLIMITED)

    /**
     * Элемент, вычитанный дренажем, но ещё не исполненный (ждёт
     * гейт). Отмена дренажа ([detach]) не имеет права его потерять —
     * следующий [attach] продолжает с него.
     */
    private var pending: Queued? = null

    private var drainJob: Job? = null

    override suspend fun runEffect(
        effect: NavigationEffect,
        consumer: (Nothing) -> Unit,
    ) {
        val commands =
            checkNotNull(graph.resolve(effect)) {
                "No navigation route for '${effect::class.simpleName}': add it to navGraph"
            }
        queue.trySend(
            Queued(
                effect = effect,
                commands = commands,
                enqueuedAt = TimeSource.Monotonic.markNow(),
            ),
        )
    }

    /**
     * Запустить дренаж очереди на [scope] — держатель collect'а гейта
     * и есть этот handler. Повторный [attach] заменяет предыдущий
     * дренаж (пересоздание хоста); сама очередь при этом живёт —
     * накопленные намерения не теряются.
     */
    public fun attach(scope: CoroutineScope) {
        drainJob?.cancel()
        drainJob =
            scope.launch {
                while (true) {
                    val item = pending ?: queue.receive().also { pending = it }
                    readiness.first { it }
                    val stale = staleness?.let { item.enqueuedAt.elapsedNow() > it } == true
                    if (stale) {
                        onDropped?.invoke(item.effect)
                    } else {
                        item.commands.forEach(executor::execute)
                    }
                    pending = null
                }
            }
    }

    /** Остановить дренаж; очередь и её содержимое переживают detach. */
    public fun detach() {
        drainJob?.cancel()
        drainJob = null
    }
}
