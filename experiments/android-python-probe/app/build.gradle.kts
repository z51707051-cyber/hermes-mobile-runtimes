plugins {
    id("com.android.application")
    id("com.chaquo.python")
}
android {
    namespace = "ai.hermes.runtime.probe"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "ai.hermes.runtime.probe"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-probe"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        testInstrumentationRunner = "ai.hermes.runtime.probe.RuntimeInstrumentation"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
chaquopy {
    defaultConfig { version = "3.11" }
}
