pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "transentia"

val services = listOf(
    "transfer",
    // "wallet",
    // "fds"
)

services.forEach { service ->
    listOf("domain", "application", "infra", "api").forEach { layer ->
        val name = "$service-$layer"
        include(name)
        project(":$name").projectDir = file("services/$service/$layer")
    }
}

include("common")
project(":common").projectDir = file("common")
