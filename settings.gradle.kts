pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "transentia"

val services = listOf(
    "transfer",
     "fds"
)

// 계층 모듈 (domain, application, infra)
services.forEach { service ->
    listOf("domain", "application", "infra").forEach { layer ->
        val name = "$service-$layer"
        include(name)
        project(":$name").projectDir = file("services/$service/$layer")
    }
}

// 실행 모듈 (boot/*)
include("transfer-api")
project(":transfer-api").projectDir = file("services/transfer/instances/api")

include("fds-consumer")
project(":fds-consumer").projectDir = file("services/fds/instances/consumer")

include("delivery-http-error")
project(":delivery-http-error").projectDir = file("common/delivery-http-error")

include("shared-common")
project(":shared-common").projectDir = file("common/shared-common")