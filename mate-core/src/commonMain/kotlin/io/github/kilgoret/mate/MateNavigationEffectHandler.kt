package io.github.kilgoret.mate

import kotlin.reflect.KClass

/**
 * Базовый handler навигационного семейства: [NavigationEffect.Back]
 * обрабатывает сам, остальное делегирует в [onScreenEffect].
 *
 * Декларирует БАЗОВОЕ семейство [NavigationEffect] — экранные
 * nav-эффекты остаются подгруппами-интерфейсами внутри него (подгруппы
 * легальны; запрещена лишь family-декларация двух уровней).
 *
 * Roadmap v1 (mate-navigation): единый shared-инстанс на приложение,
 * гейт готовности + FIFO-очередь отложенной навигации, интерпретация
 * через navGraph.
 */
public abstract class MateNavigationEffectHandler<Msg>(
    protected val navigator: Navigator,
) : MateEffectHandler<Msg, NavigationEffect> {
    final override val effectFamily: KClass<NavigationEffect> = NavigationEffect::class

    final override suspend fun runEffect(
        effect: NavigationEffect,
        consumer: (Msg) -> Unit,
    ) {
        when (effect) {
            is NavigationEffect.Back -> navigator.back()
            else -> onScreenEffect(effect)
        }
    }

    protected abstract suspend fun onScreenEffect(effect: NavigationEffect)
}
