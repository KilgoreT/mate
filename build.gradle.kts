plugins {
    id("org.jetbrains.kotlin.multiplatform") apply false
    id("com.android.library") apply false
    // Библиотечные гейты: публичный API фиксируется .api-снапшотами
    // (apiCheck в CI), стиль — ktlint, покрытие — kover с порогом.
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.16.3"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
    id("org.jetbrains.kotlinx.kover") version "0.9.1"
}

allprojects {
    group = "io.github.kilgoret.mate"
    version = findProperty("mateVersion")?.toString() ?: "0.0.1-SNAPSHOT"
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "org.jetbrains.kotlinx.kover")
}

dependencies {
    kover(project(":mate-core"))
    kover(project(":mate-navigation"))
    kover(project(":mate-test"))
}

kover {
    reports {
        verify {
            rule("line coverage of mate runtime") {
                // Факт на Э1 — 100%; планка с люфтом под error-ветки
                // Э2, пересматривается вверх по мере роста.
                minBound(85)
            }
        }
    }
}
