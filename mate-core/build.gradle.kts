plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("maven-publish")
}

kotlin {
    // Библиотечный режим: каждая публичная декларация — с явной
    // видимостью и явным типом возврата.
    explicitApi()

    jvmToolchain(17)

    // v0: androidTarget + jvm. iOS-таргеты отложены до публикации в
    // Maven Central (Э7): JitPack собирает на Linux, где Kotlin/Native
    // iOS-кросс-компиляция недоступна (rollout Э0).
    androidTarget {
        publishLibraryVariants("release")
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
    }
}

android {
    namespace = "io.github.kilgoret.mate.core"
    compileSdk = 35
    defaultConfig {
        minSdk = 23
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
