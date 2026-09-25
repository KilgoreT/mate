package io.github.kilgoret.mate.navigation

import io.github.kilgoret.mate.NavigationEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NavTableTest {
    private data class CardScreen(val wordId: Long) : Screen

    private sealed interface WordsNav : NavigationEffect {
        data class OpenCard(val wordId: Long) : WordsNav

        data object OpenSettings : WordsNav
    }

    @Test
    fun resolvesExactEffectClassToCommands() {
        val table = navTable {
            on<WordsNav.OpenCard> { push(CardScreen(it.wordId)) }
            on<NavigationEffect.Back> { pop() }
        }

        assertEquals(
            listOf<NavCommand>(NavCommand.Push(CardScreen(42))),
            table.resolve(WordsNav.OpenCard(42)),
        )
        assertEquals(listOf<NavCommand>(NavCommand.Pop), table.resolve(NavigationEffect.Back))
    }

    @Test
    fun resolvesSubgroupEffectThroughSupertypeRoute() {
        // Строка на всю подгруппу: любой WordsNav-эффект ведёт назад.
        val table = navTable { on<WordsNav> { pop() } }

        assertEquals(listOf<NavCommand>(NavCommand.Pop), table.resolve(WordsNav.OpenSettings))
    }

    @Test
    fun exactRouteWinsOverSupertypeRoute() {
        val table = navTable {
            on<WordsNav> { pop() }
            on<WordsNav.OpenCard> { push(CardScreen(it.wordId)) }
        }

        assertEquals(
            listOf<NavCommand>(NavCommand.Push(CardScreen(1))),
            table.resolve(WordsNav.OpenCard(1)),
        )
    }

    @Test
    fun effectWithoutRouteResolvesToNull() {
        val table = navTable { on<NavigationEffect.Back> { pop() } }

        assertNull(table.resolve(WordsNav.OpenSettings))
    }

    @Test
    fun duplicateRouteFailsOnBuild() {
        assertFailsWith<IllegalArgumentException> {
            navTable {
                on<NavigationEffect.Back> { pop() }
                on<NavigationEffect.Back> { pop() }
            }
        }
    }

    @Test
    fun compositeRouteKeepsCommandOrder() {
        // Составной переход: pop текущего + push нового — по порядку.
        val table = navTable {
            on<WordsNav.OpenCard> {
                pop()
                push(CardScreen(it.wordId))
            }
        }

        assertEquals(
            listOf(NavCommand.Pop, NavCommand.Push(CardScreen(7))),
            table.resolve(WordsNav.OpenCard(7)),
        )
    }
}
