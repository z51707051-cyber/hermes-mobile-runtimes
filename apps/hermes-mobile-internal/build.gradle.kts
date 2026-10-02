plugins {
    id("com.android.application") version "9.3.2" apply false
    id("com.chaquo.python") version "17.0.0" apply false
}

group = "ai.hermes.mobile"
version = "0.1.0-alpha.3"

allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}
