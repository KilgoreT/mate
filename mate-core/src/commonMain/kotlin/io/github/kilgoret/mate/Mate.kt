package io.github.kilgoret.mate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.reflect.KClass
import kotlin.time.TimeSource

/**
 * Раннер TEA-цикла с гарантиями RUNTIME (свойства библиотеки, не
 * дисциплина вызывающего):
 *
 * - **Mailbox (FIFO):** Message'ы встают в очередь и разбираются
 *   строго по одному единственным внутренним циклом — [accept]
 *   потокобезопасен по построению; каскадные Message («эффект породил
 *   Message») встают в ХВОСТ очереди: сначала все эффекты текущего
 *   Message, потом следующий (никакого depth-first).
 * - **Роутинг эффектов — табличкой:** каждый эффект доставляется РОВНО
 *   одному handler'у по декларации семейства ([MateEffectHandler]);
 *   дубль семейств — fail при создании, сирота и двойной матч — fail
 *   при диспатче ([MateFailPolicy]).
 * - **Цикл не умирает:** исключения reduce/handler'ов уходят в
 *   [failPolicy]; кидающая политика ([MateFailPolicy.Strict]) — это
 *   осознанный debug-краш.
 * - **Наблюдаемость:** [observers] видят полный цикл (Msg, state
 *   до/после, эффекты, длительность, каузальность); исключение в
 *   наблюдателе цикл не убивает.
 *
 * Порядок итерации зафиксирован: reduce → публикация state → запуск
 * эффектов. Порядок ЗАВЕРШЕНИЯ эффектов одного reduce не гарантирован
 * (исполняются конкурентно, как Cmd в Elm).
 *
 * Init-эффекты исполняются ПЕРВОЙ итерацией цикла — до любого Message.
 *
 * Контракт вызывающего: не читать `state.value` синхронно сразу после
 * `accept` — состояние публикуется циклом (все чтения — подпиской).
 * Код раннера не привязан к диспатчеру: FIFO не зависит от потока.
 */
public class Mate<State, Message, E : Effect>(
    initState: State,
    reducer: MateReducer<State, Message, E>,
    initEffects: Set<E>,
    effectHandlers: List<MateEffectHandler<Message, *>>,
    private val subscriptions: ((State) -> Set<Sub>)? = null,
    subscriptionHandlers: List<MateSubscriptionHandler<Message, *>> = emptyList(),
    private val observers: List<MateObserver<State, Message, E>> = emptyList(),
    private val failPolicy: MateFailPolicy = MateFailPolicy.Strict,
    private val coroutineScope: CoroutineScope,
) : MateStateHolder<State, Message>,
    MateReducer<State, Message, E> by reducer {
    private val _state: MutableStateFlow<State> = MutableStateFlow(initState)

    override val state: StateFlow<State>
        get() = _state.asStateFlow()

    private val mailbox = Channel<Message>(Channel.UNLIMITED)

    /** Табличка «семейство → исполнитель»; дубль семейства — fail сразу. */
    private val registry: Map<KClass<*>, MateEffectHandler<Message, *>> =
        buildMap {
            effectHandlers.forEach { handler ->
                val previous = put(handler.effectFamily, handler)
                require(previous == null) {
                    "Two handlers declare the same effect family " +
                        "'${handler.effectFamily.simpleName}': split them"
                }
            }
        }

    /** Кэш резолва «конкретный класс эффекта → исполнитель». */
    private val resolveCache = mutableMapOf<KClass<*>, MateEffectHandler<Message, *>>()

    /** Табличка «семейство подписок → исполнитель»; дубль — fail сразу. */
    private val subRegistry: Map<KClass<*>, MateSubscriptionHandler<Message, *>> =
        buildMap {
            subscriptionHandlers.forEach { handler ->
                val previous = put(handler.subFamily, handler)
                require(previous == null) {
                    "Two subscription handlers declare the same family " +
                        "'${handler.subFamily.simpleName}': split them"
                }
            }
        }

    private val subResolveCache = mutableMapOf<KClass<*>, MateSubscriptionHandler<Message, *>>()

    /** Активные подписки: данные Sub → job коллектора. */
    private val activeSubs = mutableMapOf<Sub, Job>()

    init {
        // UNDISPATCHED: initial-дифф подписок (от initState) и
        // init-эффекты отрабатывают до первого Message на любом
        // диспатчере; дальше цикл честно suspend'ится на mailbox.
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            diffSubscriptions(initState)
            initEffects.forEach { dispatchEffect(it) }
            for (message in mailbox) {
                processMessage(message)
            }
        }
    }

    override fun accept(message: Message) {
        val result = mailbox.trySend(message)
        if (result.isFailure) {
            failPolicy.onError(MateError.MessageRejected(message))
        }
    }

    private fun processMessage(message: Message) {
        try {
            val before = _state.value
            notifyObservers { onMessage(message, before) }
            val (newState, effects) = reduce(before, message)
            _state.value = newState
            notifyObservers { onReduced(message, before, newState, effects) }
            // Порядок итерации зафиксирован контрактом:
            // reduce → state → ДИФФ ПОДПИСОК → эффекты.
            diffSubscriptions(newState)
            effects.forEach { dispatchEffect(it) }
        } catch (error: Throwable) {
            failPolicy.onError(MateError.MessageFailed(message, error))
        }
    }

    /**
     * Elm-семантика Sub: желаемый набор выводится из State; новые
     * подписки стартуют, исчезнувшие гасятся. Идентичность — equality
     * данных Sub: равная подписка НЕ рестартует.
     */
    private fun diffSubscriptions(state: State) {
        val declared = subscriptions ?: return
        val desired = declared(state)

        val toStop = activeSubs.keys.filter { it !in desired }
        toStop.forEach { sub ->
            activeSubs.remove(sub)?.cancel()
            notifyObservers { onSubscriptionStopped(sub) }
        }

        desired.forEach { sub ->
            if (sub in activeSubs) return@forEach
            val handler = resolveSubHandler(sub) ?: return@forEach
            notifyObservers { onSubscriptionStarted(sub) }
            activeSubs[sub] =
                coroutineScope.launch {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        (handler as MateSubscriptionHandler<Message, Sub>)
                            .flow(sub)
                            .collect { message -> accept(message) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        notifyObservers { onSubscriptionError(sub, error) }
                        failPolicy.onError(MateError.SubscriptionFailed(sub, error))
                    }
                }
        }
    }

    private fun resolveSubHandler(sub: Sub): MateSubscriptionHandler<Message, *>? {
        val subClass = sub::class
        subResolveCache[subClass]?.let { return it }

        val matches = subRegistry.entries.filter { (family, _) -> family.isInstance(sub) }
        return when (matches.size) {
            1 -> matches.single().value.also { subResolveCache[subClass] = it }
            0 -> {
                failPolicy.onError(MateError.OrphanSubscription(sub))
                null
            }
            else -> {
                failPolicy.onError(
                    MateError.AmbiguousSubscription(
                        sub = sub,
                        families =
                            matches.map {
                                @Suppress("UNCHECKED_CAST")
                                it.key as KClass<out Sub>
                            },
                    ),
                )
                null
            }
        }
    }

    private fun dispatchEffect(effect: E) {
        val handler = resolveHandler(effect) ?: return
        coroutineScope.launch {
            notifyObservers { onEffectStarted(effect) }
            val startMark = TimeSource.Monotonic.markNow()
            try {
                @Suppress("UNCHECKED_CAST")
                (handler as MateEffectHandler<Message, E>).runEffect(effect) { message ->
                    notifyObservers { onCausedMessage(effect, message) }
                    accept(message)
                }
                notifyObservers { onEffectFinished(effect, startMark.elapsedNow()) }
            } catch (error: CancellationException) {
                // Отмена scope (закрытие экрана) — не ошибка эффекта:
                // без ложного EffectFailed в observer и политику.
                throw error
            } catch (error: Throwable) {
                notifyObservers { onEffectFailed(effect, error) }
                failPolicy.onError(MateError.EffectFailed(effect, error))
            }
        }
    }

    private fun resolveHandler(effect: E): MateEffectHandler<Message, *>? {
        val effectClass = effect::class
        resolveCache[effectClass]?.let { return it }

        val matches = registry.entries.filter { (family, _) -> family.isInstance(effect) }
        return when (matches.size) {
            1 -> matches.single().value.also { resolveCache[effectClass] = it }
            0 -> {
                failPolicy.onError(MateError.OrphanEffect(effect))
                null
            }
            else -> {
                failPolicy.onError(
                    MateError.AmbiguousEffect(
                        effect = effect,
                        families =
                            matches.map {
                                @Suppress("UNCHECKED_CAST")
                                it.key as KClass<out Effect>
                            },
                    ),
                )
                null
            }
        }
    }

    /** Наблюдатели read-only: их исключения цикл не убивают. */
    private inline fun notifyObservers(block: MateObserver<State, Message, E>.() -> Unit) {
        observers.forEach { observer ->
            try {
                observer.block()
            } catch (_: Throwable) {
                // Кривой наблюдатель не имеет права влиять на цикл.
            }
        }
    }

    public fun dispose() {
        activeSubs.values.forEach { it.cancel() }
        activeSubs.clear()
    }
}
