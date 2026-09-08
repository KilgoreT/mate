package io.github.kilgoret.mate.navigation

/**
 * Команда навигации — данные, в которые [NavGraph] переводит
 * навигационный эффект. Исполняет команды [NavigationExecutor];
 * таблица и раннер сами ничего с ними не делают.
 */
public sealed interface NavCommand {
    /**
     * Положить экран на стек.
     *
     * @param screen пункт назначения с аргументами перехода внутри.
     */
    public data class Push(val screen: Screen) : NavCommand

    /** Снять текущий экран со стека (back). */
    public data object Pop : NavCommand
}

/**
 * Исполнитель навигационных команд — «мышца» навигации, единственное
 * место, знающее, что такое экран. У приложения это адаптер над
 * NavController (push → navigate, pop → popBackStack), у тестового
 * харнеса — стек раннеров.
 */
public fun interface NavigationExecutor {
    public fun execute(command: NavCommand)
}
