plugins {
    id("org.jetbrains.kotlin.multiplatform") apply false
    id("com.android.library") apply false
}

allprojects {
    group = "io.github.kilgoret.mate"
    version = findProperty("mateVersion")?.toString() ?: "0.0.1-SNAPSHOT"
}
