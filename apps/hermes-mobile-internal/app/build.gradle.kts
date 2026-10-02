plugins {
    id("com.android.application")
    id("com.chaquo.python")
}

val signingStorePath = providers.environmentVariable("HERMES_ANDROID_KEYSTORE_PATH").orNull
val signingStorePassword = providers.environmentVariable("HERMES_ANDROID_STORE_PASSWORD").orNull
val signingKeyAlias = providers.environmentVariable("HERMES_ANDROID_KEY_ALIAS").orNull
val signingKeyPassword = providers.environmentVariable("HERMES_ANDROID_KEY_PASSWORD").orNull
val signingValues =
    listOf(signingStorePath, signingStorePassword, signingKeyAlias, signingKeyPassword)
require(signingValues.all { it == null } || signingValues.all { it != null }) {
    "internal Android signing variables must be either all set or all unset"
}

android {
    namespace = "ai.hermes.mobile.runtime.bridge"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "ai.hermes.mobile.runtime"
        minSdk = 30
        targetSdk = 36
        versionCode = 3
        versionName = "0.1.0-alpha.3"
        ndk {
            // The first installable package targets the user's ARM64 iQOO Z10x.
            abiFilters += "arm64-v8a"
        }
    }

    val internalSigning =
        if (signingStorePath != null) {
            signingConfigs.create("internal") {
                storeFile = file(signingStorePath)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
                storeType = "PKCS12"
            }
        } else {
            null
        }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            signingConfig = internalSigning
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            manifest.srcFile("../../mobile-bridge-android/app/src/main/AndroidManifest.xml")
            kotlin.srcDir("../../mobile-bridge-android/app/src/main/kotlin")
            res.srcDir("../../mobile-bridge-android/app/src/main/res")
        }
    }

    lint {
        abortOnError = true
        checkDependencies = true
        lintConfig = file("lint.xml")
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
