plugins {
    id("com.android.application")
    id("com.chaquo.python")
}

android {
    namespace = "ai.hermes.mobile.runtime.bridge"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "ai.hermes.mobile.runtime"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha.1"
        ndk {
            // The first installable package targets the user's ARM64 iQOO Z10x.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("../../mobile-bridge-android/app/src/main/AndroidManifest.xml")
            java.srcDir("../../mobile-bridge-android/app/src/main/kotlin")
            res.srcDir("../../mobile-bridge-android/app/src/main/res")
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        lintConfig = file("../../mobile-bridge-android/app/lint.xml")
        warningsAsErrors = true
    }
}

chaquopy {
    sourceSets {
        getByName("main") {
            srcDir("src/generated/python")
            srcDir("../../mobile-bridge-android/app/src/main/python")
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
