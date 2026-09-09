plugins {
    id("com.android.application")
}

android {
    namespace = "ai.hermes.mobile.fixture"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "ai.hermes.mobile.fixture"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
    }
}

// Both Android applications deliberately resolve an identical dependency set,
// so the reviewed lock state remains one generated source of truth.
dependencyLocking {
    lockFile = rootProject.file("app/gradle.lockfile")
}

dependencies {
    implementation("com.squareup.moshi:moshi:1.15.2")
    testImplementation("junit:junit:4.13.2")
}
