package io.github.kilgoret.mate

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.reflect.KClass
import kotlin.time.TimeSource

/**
 * Раннер TEA-цикла. Библиотека гарантирует в рантайме:
 *
 * - **Mailbox (FIFO):** Message'ы встают в очередь и разбираются
 *   строго по одному единственным внутренним циклом — [accept]
 *   потокобезопасен по построению; каскадные Message («эффект породил
 *   Message») встают в хвост очереди: сначала все эффекты текущего
 *   Message, потом следующий.
 * - **Роутинг эффектов по реестру семейств:** каждый эффект
 *   доставляется ровно одному handler'у по декларации семейства
 *   ([MateEffectHandler]); дубль семейств — fail при создании, сирота
 *   и двойной матч — fail при диспатче ([MateFailPolicy]).
 * - **Цикл не умирает:** исключения reduce/handler'ов уходят в
 *   [failPolicy]; [MateFailPolicy.Strict] превращает ошибку в краш —
 *   режим отладки.
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
 * Контракт чтения состояния и входа сообщений — на [MateStore].
 * Код раннера не привязан к диспатчеру: FIFO не зависит от потока.
 */
public class Mate<State, Message, E : Effect>(
    initState: State,
    reducer: MateReducer<State, Message, E>,
    initEffects: Set<E>,
    effectHandlers: List<MateEffectHandler<Message, *>>,
    private val subscriptions: ((State) -> Set<Subscription>)? = null,
    subscriptionHandlers: List<MateSubscriptionHandler<Message, *>> = emptyList(),
    private val observers: List<MateObserver<State, Message, E>> = emptyList(),
    private val failPolicy: MateFailPolicy = MateFailPolicy.Strict,
    private val coroutineScope: CoroutineScope,
) : MateStore<State, Message>,
    MateReducer<State, Message, E> by reducer {
    private val _state: MutableStateFlow<State> = MutableStateFlow(initState)

    override val state: StateFlow<State>
        get() = _state.asStateFlow()

    private val mailbox = Channel<Message>(Channel.UNLIMITED)

    /** Реестр «семейство → исполнитель»; дубль семейства — fail сразу. */
    private val effectRegistry: Map<KClass<*>, MateEffectHandler<Message, *>> =
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
    private val effectResolveCache = mutableMapOf<KClass<*>, MateEffectHandler<Message, *>>()

    /** Реестр «семейство подписок → исполнитель»; дубль — fail сразу. */
    private val subscriptionRegistry: Map<KClass<*>, MateSubscriptionHandler<Message, *>> =
        buildMap {
            subscriptionHandlers.forEach { handler ->
                val previous = put(handler.subscriptionFamily, handler)
                require(previous == null) {
                    "Two subscription handlers declare the same family " +
                        "'${handler.subscriptionFamily.simpleName}': split them"
                }
            }
        }

    private val subscriptionResolveCache = mutableMapOf<KClass<*>, MateSubscriptionHandler<Message, *>>()

    /** Активные подписки: данные Subscription → job коллектора. */
    private val activeSubscriptions = mutableMapOf<Subscription, Job>()

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
     * Elm-семантика Subscription: желаемый набор выводится из State; новые
     * подписки стартуют, исчезнувшие гасятся. Идентичность — equality
     * данных Subscription: равная подписка НЕ рестартует.
     */
    private fun diffSubscriptions(state: State) {
        val declared = subscriptions ?: return
        val desired = declared(state)

        val toStop = activeSubscriptions.keys.filter { it !in desired }
        toStop.forEach { sub ->
            activeSubscriptions.remove(sub)?.cancel()
            notifyObservers { onSubscriptionStopped(sub) }
        }

        desired.forEach { sub ->
            if (sub in activeSubscriptions) return@forEach
            val handler = resolveSubscriptionHandler(sub) ?: return@forEach
            notifyObservers { onSubscriptionStarted(sub) }
            activeSubscriptions[sub] =
                coroutineScope.launch {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        (handler as MateSubscriptionHandler<Message, Subscription>)
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

    private fun resolveSubscriptionHandler(sub: Subscription): MateSubscriptionHandler<Message, *>? {
        val subClass = sub::class
        subscriptionResolveCache[subClass]?.let { return it }

        val matches = subscriptionRegistry.entries.filter { (family, _) -> family.isInstance(sub) }
        return when (matches.size) {
            1 -> matches.single().value.also { subscriptionResolveCache[subClass] = it }
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
                                it.key as KClass<out Subscription>
                            },
                    ),
                )
                null
            }
        }
    }

    private fun dispatchEffect(effect: E) {
        val handler = resolveEffectHandler(effect) ?: return
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
                // пробрасывается, в observer и политику не попадает.
                // CancellationException при ЖИВОЙ корутине — не отмена
                // цикла, а честная ошибка эффекта: так падает
                // withTimeout внутри runEffect.
                if (currentCoroutineContext().isActive) {
                    handleEffectFailure(effect, error)
                } else {
                    throw error
                }
            } catch (error: Throwable) {
                handleEffectFailure(effect, error)
            }
        }
    }

    private fun handleEffectFailure(
        effect: E,
        error: Throwable,
    ) {
        // Recovery-Msg эффекта, объявившего ответ на провал. null в
        // двух случаях: эффект не Recoverable, либо onFail сам упал
        // (его исключение прикрепляется к исходному suppressed'ом).
        @Suppress("UNCHECKED_CAST")
        val recovery: Message? =
            (effect as? RecoverableEffect<Message>)?.let { recoverable ->
                try {
                    recoverable.onFail(error)
                } catch (recoveryError: Throwable) {
                    error.addSuppressed(recoveryError)
                    null
                }
            }
        if (recovery != null) {
            notifyObservers {
                onEffectRecovered(effect, error, recovery)
                onCausedMessage(effect, recovery)
            }
            accept(recovery)
        } else {
            notifyObservers { onEffectFailed(effect, error) }
            failPolicy.onError(MateError.EffectFailed(effect, error))
        }
    }

    private fun resolveEffectHandler(effect: E): MateEffectHandler<Message, *>? {
        val effectClass = effect::class
        effectResolveCache[effectClass]?.let { return it }

        val matches = effectRegistry.entries.filter { (family, _) -> family.isInstance(effect) }
        return when (matches.size) {
            1 -> matches.single().value.also { effectResolveCache[effectClass] = it }
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

    /**
     * Гасит все активные подписки; каждая уходит наблюдателям в
     * [MateObserver.onSubscriptionStopped] — трасса завершается без
     * «повисших» подписок.
     */
    public fun dispose() {
        activeSubscriptions.forEach { (sub, job) ->
            job.cancel()
            notifyObservers { onSubscriptionStopped(sub) }
        }
        activeSubscriptions.clear()
    }
}
