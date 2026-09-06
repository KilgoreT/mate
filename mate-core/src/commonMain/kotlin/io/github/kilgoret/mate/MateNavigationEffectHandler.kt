package io.github.kilgoret.mate

/**
 * Базовый handler навигационных эффектов: [NavigationEffect.Back]
 * обрабатывает сам, остальное делегирует в [onScreenEffect].
 *
 * Roadmap v1 (mate-navigation): единый shared-инстанс на приложение,
 * гейт готовности + FIFO-очередь отложенной навигации, интерпретация
 * через navGraph.
 */
public abstract class MateNavigationEffectHandler<Msg>(
    protected val navigator: Navigator,
) : MateTypedEffectHandler<Msg, NavigationEffect>() {
    final override fun filter(effect: Effect): NavigationEffect? = effect as? NavigationEffect

    final override suspend fun onEffect(
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
