import org.cyclonedx.gradle.CyclonedxDirectTask
import org.cyclonedx.model.Component
import org.cyclonedx.model.License
import org.cyclonedx.model.LicenseChoice

plugins {
    id("com.android.application")
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
        versionName = "0.1.0-dev"

        testInstrumentationRunner = "android.app.Instrumentation"
    }

    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        checkDependencies = true
        lintConfig = file("lint.xml")
        warningsAsErrors = true
    }

    testOptions {
        unitTests.all {
            it.useJUnit()
        }
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
    componentGroup = "ai.hermes.mobile"
    componentName = "hermes-mobile-runtime-android"
    componentVersion = "0.1.0-dev"
    projectType = Component.Type.APPLICATION
    includeConfigs = listOf("debugRuntimeClasspath")
    testConfigs = emptyList()
    licenseChoice =
        LicenseChoice().apply {
            addLicense(
                License().apply {
                    name = "MIT"
                    url = "https://opensource.org/license/mit"
                },
            )
        }
}
