plugins {
    id("org.jetbrains.kotlin.multiplatform") apply false
    id("com.android.library") apply false
    // Библиотечные гейты: публичный API фиксируется .api-снапшотами
    // (apiCheck в CI), стиль — ktlint.
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.16.3"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1" apply false
}

allprojects {
    group = "io.github.kilgoret.mate"
    version = findProperty("mateVersion")?.toString() ?: "0.0.1-SNAPSHOT"
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
}
