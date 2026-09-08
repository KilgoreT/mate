package io.github.kilgoret.mate.navigation

import io.github.kilgoret.mate.NavigationEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class NavGraphTest {
    private data class CardScreen(val wordId: Long) : Screen

    private sealed interface WordsNav : NavigationEffect {
        data class OpenCard(val wordId: Long) : WordsNav

        data object OpenSettings : WordsNav
    }

    @Test
    fun resolvesExactEffectClassToCommands() {
        val graph =
            navGraph {
                on<WordsNav.OpenCard> { push(CardScreen(it.wordId)) }
                on<NavigationEffect.Back> { pop() }
            }

        assertEquals(
            listOf<NavCommand>(NavCommand.Push(CardScreen(42))),
            graph.resolve(WordsNav.OpenCard(42)),
        )
        assertEquals(listOf<NavCommand>(NavCommand.Pop), graph.resolve(NavigationEffect.Back))
    }

    @Test
    fun resolvesSubgroupEffectThroughSupertypeRoute() {
        // Строка на всю подгруппу: любой WordsNav-эффект ведёт назад.
        val graph = navGraph { on<WordsNav> { pop() } }

        assertEquals(listOf<NavCommand>(NavCommand.Pop), graph.resolve(WordsNav.OpenSettings))
    }

    @Test
    fun exactRouteWinsOverSupertypeRoute() {
        val graph =
            navGraph {
                on<WordsNav> { pop() }
                on<WordsNav.OpenCard> { push(CardScreen(it.wordId)) }
            }

        assertEquals(
            listOf<NavCommand>(NavCommand.Push(CardScreen(1))),
            graph.resolve(WordsNav.OpenCard(1)),
        )
    }

    @Test
    fun effectWithoutRouteResolvesToNull() {
        val graph = navGraph { on<NavigationEffect.Back> { pop() } }

        assertNull(graph.resolve(WordsNav.OpenSettings))
    }

    @Test
    fun duplicateRouteFailsOnBuild() {
        assertFailsWith<IllegalArgumentException> {
            navGraph {
                on<NavigationEffect.Back> { pop() }
                on<NavigationEffect.Back> { pop() }
            }
        }
    }

    @Test
    fun compositeRouteKeepsCommandOrder() {
        // Составной переход: pop текущего + push нового — по порядку.
        val graph =
            navGraph {
                on<WordsNav.OpenCard> {
                    pop()
                    push(CardScreen(it.wordId))
                }
            }

        assertEquals(
            listOf(NavCommand.Pop, NavCommand.Push(CardScreen(7))),
            graph.resolve(WordsNav.OpenCard(7)),
        )
    }
}
