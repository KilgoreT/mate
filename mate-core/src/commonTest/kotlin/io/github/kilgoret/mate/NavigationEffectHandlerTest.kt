package io.github.kilgoret.mate

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationEffectHandlerTest {
    private sealed interface Msg

    private data class OpenThing(val id: Long) : NavigationEffect

    private data class OtherFamily(val tag: String) : Effect

    private class RecordingNavigator : Navigator {
        val backCalls = mutableListOf<Unit>()

        override fun back() {
            backCalls += Unit
        }
    }

    private class Handler(navigator: Navigator) : MateNavigationEffectHandler<Msg>(navigator) {
        val screenEffects = mutableListOf<NavigationEffect>()

        override suspend fun onScreenEffect(effect: NavigationEffect) {
            screenEffects += effect
        }
    }

    @Test
    fun backGoesToNavigator() =
        runTest {
            val navigator = RecordingNavigator()
            val handler = Handler(navigator)

            handler.runEffect(NavigationEffect.Back) {}

            assertEquals(1, navigator.backCalls.size)
            assertEquals(emptyList(), handler.screenEffects)
        }

    @Test
    fun screenEffectDelegatesToSubclass() =
        runTest {
            val navigator = RecordingNavigator()
            val handler = Handler(navigator)

            handler.runEffect(OpenThing(42)) {}

            assertEquals(listOf<NavigationEffect>(OpenThing(42)), handler.screenEffects)
            assertEquals(0, navigator.backCalls.size)
        }

    @Test
    fun foreignFamilyIsSilentlyIgnored() =
        runTest {
            val navigator = RecordingNavigator()
            val handler = Handler(navigator)

            handler.runEffect(OtherFamily("net")) {}

            assertEquals(emptyList(), handler.screenEffects)
            assertEquals(0, navigator.backCalls.size)
        }
}
