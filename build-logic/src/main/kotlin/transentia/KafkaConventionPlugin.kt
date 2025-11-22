package transentia

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

class KafkaConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        target.pluginManager.apply("org.jetbrains.kotlin.jvm")
        target.pluginManager.apply("org.jetbrains.kotlin.plugin.spring")
        target.pluginManager.apply("org.jetbrains.kotlin.plugin.allopen")
        target.pluginManager.apply("io.spring.dependency-management")

        // Spring Cloud BOM import (Spring Boot 3.3.2와 호환)
        target.extensions.getByType(io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension::class.java).apply {
            imports {
                mavenBom("org.springframework.cloud:spring-cloud-dependencies:2023.0.3")
            }
        }

        target.dependencies {
            // Spring Cloud Stream + Kafka Streams Binder (버전은 BOM에서 관리)
            add("implementation", "org.springframework.cloud:spring-cloud-stream")
            add("implementation", "org.springframework.cloud:spring-cloud-stream-binder-kafka-streams")
            add("implementation", "org.springframework.kafka:spring-kafka")
            add("implementation", "org.apache.kafka:kafka-streams")
            add("implementation", "com.fasterxml.jackson.module:jackson-module-kotlin")
            add("testImplementation", "org.springframework.kafka:spring-kafka-test")
        }
    }
}