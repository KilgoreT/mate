package io.github.kilgoret.mate

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
    private val flowHandlers: List<MateFlowHandler<Message>> = emptyList(),
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

    init {
        // UNDISPATCHED: init-эффекты диспатчатся до первого Message на
        // любом диспатчере; дальше цикл честно suspend'ится на mailbox.
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            initEffects.forEach { dispatchEffect(it) }
            for (message in mailbox) {
                processMessage(message)
            }
        }
        flowHandlers.forEach { it.subscribe(coroutineScope, ::accept) }
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
            effects.forEach { dispatchEffect(it) }
        } catch (error: Throwable) {
            failPolicy.onError(MateError.MessageFailed(message, error))
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
        flowHandlers.forEach { it.unsubscribe() }
    }
}
