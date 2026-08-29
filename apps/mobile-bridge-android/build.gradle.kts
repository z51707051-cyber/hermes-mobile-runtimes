plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.cyclonedx.bom") version "3.4.1"
}

group = "ai.hermes.mobile"
version = "0.1.0-dev"

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

