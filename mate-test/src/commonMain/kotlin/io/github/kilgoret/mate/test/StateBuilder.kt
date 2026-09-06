package io.github.kilgoret.mate.test

/**
 * State builder pattern for creating complex test states.
 * Provides a fluent API for modifying state properties.
 */
public class StateBuilder<T>(private var state: T) {
    /**
     * Modifies the current state using the provided transformation function.
     *
     * @param transform A function that takes the current state and returns a modified state
     * @return This builder instance for method chaining
     */
    public fun modify(transform: (T) -> T): StateBuilder<T> {
        state = transform(state)
        return this
    }

    /**
     * Builds and returns the final state.
     *
     * @return The constructed state with all modifications applied
     */
    public fun build(): T = state
}

/**
 * Extension function that creates a StateBuilder from any object.
 * This enables the fluent builder pattern for test state creation.
 *
 * Usage:
 * ```kotlin
 * val state = MyState()
 *     .toBuilder()
 *     .modify { it.copy(property1 = "value1") }
 *     .modify { it.copy(property2 = "value2") }
 *     .build()
 * ```
 *
 * @return A new StateBuilder instance wrapping this object
 */
public fun <T> T.toBuilder(): StateBuilder<T> = StateBuilder(this)
