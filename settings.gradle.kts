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

include("shared-kernel")
project(":shared-kernel").projectDir = file("common/shared-kernel")


include("delivery-http-starter")
project(":delivery-http-starter").projectDir = file("common/delivery-http-starter")

include("shared-domain-error")
project(":shared-domain-error").projectDir = file("common/shared-domain-error")