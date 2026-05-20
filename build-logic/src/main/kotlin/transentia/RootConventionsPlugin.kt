package transentia

import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.gradle.api.Plugin
import org.gradle.api.Project

class RootConventionsPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.subprojects {
            // 모든 서브모듈에 dependency-management 적용
            pluginManager.apply("io.spring.dependency-management")

            // Spring Boot BOM import (버전만 여기서 한 번!)
            extensions.configure(DependencyManagementExtension::class.java) {
                imports {
                    mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.2")
                }
            }

            val bootApps = setOf(
                Modules.TRANSFER_API,
                Modules.TRANSFER_RELAY,
                Modules.TRANSFER_PUBLISHER,
                Modules.TRANSFER_CONSUMER,
                Modules.FDS_CONSUMER,
            )

            val springModules = setOf(
                Modules.TRANSFER_APPLICATION,
                Modules.TRANSFER_INFRA,
                Modules.FDS_INFRA,
                Modules.COMMON_APPLICATION,
                Modules.FDS_APPLICATION,
            )

            val kafkaModules = setOf(
                Modules.KAFKA_PRODUCER,
                Modules.KAFKA_CONSUMER
            )

            val kafkaConfigModules = setOf(
                Modules.KAFKA_CONFIG
            )

            val jpaModules = setOf(
                Modules.TRANSFER_INFRA,
                Modules.FDS_INFRA
            )

            val pureKotlinModules = setOf(
                Modules.COMMON_DOMAIN,
                Modules.TRANSFER_DOMAIN,
                Modules.FDS_DOMAIN
            )

            val coverageModules = setOf(
                Modules.TRANSFER_DOMAIN,
                Modules.TRANSFER_INFRA,
                Modules.FDS_INFRA,
            )

            when (name) {
                in bootApps -> pluginManager.apply("transentia.spring-boot-app")
                in springModules -> pluginManager.apply("transentia.spring-library")
                in pureKotlinModules -> pluginManager.apply("transentia.kotlin-library")
                in kafkaModules  -> pluginManager.apply("transentia.kafka-convention")
                in kafkaConfigModules -> pluginManager.apply("transentia.kafka-config-convention")
            }

            if (name in jpaModules) pluginManager.apply("transentia.spring-jpa")
            if (name in coverageModules) pluginManager.apply("transentia.code-coverage")

            val isKotlinModule = name in bootApps || name in springModules ||
                name in pureKotlinModules || name in kafkaModules || name in kafkaConfigModules
            if (isKotlinModule) pluginManager.apply("transentia.code-quality")
        }
    }
}
