package io.github.kilgoret.mate

/**
 * Базовый класс для EffectHandler с типизированной фильтрацией
 * эффектов. Runtime вызывает runEffect() на каждом handler'е с каждым
 * эффектом — этот класс отфильтровывает чужие через [filter] и зовёт
 * [onEffect] только для своих.
 *
 * Roadmap v0: упраздняется в пользу декларации семейства
 * (`effectFamily`) и роутинга-таблички в раннере.
 */
abstract class MateTypedEffectHandler<Msg, E : Effect> : MateEffectHandler<Msg, Effect> {

    final override suspend fun runEffect(effect: Effect, consumer: (Msg) -> Unit) {
        val typed = filter(effect) ?: return
        onEffect(typed, consumer)
    }

    protected abstract fun filter(effect: Effect): E?
    protected abstract suspend fun onEffect(effect: E, consumer: (Msg) -> Unit)
}
