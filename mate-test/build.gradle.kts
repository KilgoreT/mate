plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("maven-publish")
}

kotlin {
    jvmToolchain(17)

    androidTarget {
        publishLibraryVariants("release")
    }
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":mate-core"))
            // kotlin-test в MAIN сорсете — норма для тест-библиотеки:
            // её потребители подключают mate-test в testImplementation.
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
