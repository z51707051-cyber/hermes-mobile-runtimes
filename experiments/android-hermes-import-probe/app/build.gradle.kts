plugins {
    id("com.android.application")
    id("com.chaquo.python")
}
android {
    namespace = "ai.hermes.mobile.runtime.bridge"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "ai.hermes.runtime.importprobe"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-probe"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "ai.hermes.runtime.importprobe.ImportInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        getByName("main") {
            res.srcDir("../../../apps/mobile-bridge-android/app/src/main/res")
        }
    }
}
chaquopy {
    sourceSets {
        getByName("main") {
            srcDir("src/generated/python")
        }
    }
    defaultConfig {
        version = "3.13"
        pip {
            options("--find-links", "android-wheelhouse")
            install("-r", "requirements-hermes-core.txt")
        }
    }
}
dependencies {
    implementation("com.squareup.moshi:moshi:1.15.2")
}
