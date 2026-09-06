package io.github.kilgoret.mate

/**
 * Базовое семейство навигационных эффектов. Экраны доопределяют свои
 * nav-эффекты подгруппами-интерфейсами ВНУТРИ этого семейства
 * (family-декларация — только базовая, см. правила роутинга).
 */
interface NavigationEffect : Effect {
    /** Navigate back (закрыть текущий экран). */
    data object Back : NavigationEffect
}
