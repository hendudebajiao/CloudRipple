pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
// Use the installed JDK 17; the libxposed API and module both target Java 17.

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ColorOs17NotificationIsland"
include(":app")
