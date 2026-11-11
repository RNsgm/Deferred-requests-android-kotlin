pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "deferred-requests"

include(":deferred-core")
include(":deferred-gson")
include(":deferred-room")
