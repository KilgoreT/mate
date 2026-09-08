package io.github.kilgoret.mate.navigation

/**
 * Маркер экрана приложения — пункт назначения навигации, только
 * данные (data class/object с аргументами перехода внутри).
 *
 * Библиотека не знает, что такое «экран»: она лишь довозит
 * [NavCommand.Push] с этим объектом до исполнителя
 * ([NavigationExecutor]). Что скрывается за объектом — route
 * NavController'а, стек раннеров тестового харнеса — решает
 * исполнитель.
 */
public interface Screen
