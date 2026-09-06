plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("maven-publish")
}

kotlin {
    explicitApi()

    jvmToolchain(17)

    androidTarget {
        publishLibraryVariants("release")
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":mate-core"))
            // kotlin-test/coroutines-test в MAIN сорсете — норма для
            // тест-библиотеки: потребители подключают mate-test в
            // testImplementation.
            implementation(kotlin("test"))
            api("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "io.github.kilgoret.mate.test"
    compileSdk = 35
    defaultConfig {
        minSdk = 23
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
