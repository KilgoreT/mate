package io.github.kilgoret.mate

/**
 * Базовый интерфейс навигатора для NavigationEffectHandler.
 * Содержит только Back — операцию, доступную всем экранам.
 *
 * Roadmap v1 (mate-navigation): per-screen навигаторы упраздняются в
 * пользу navGraph (навигация как данные) и единого nav-handler'а.
 */
public interface Navigator {
    public fun back()
}
