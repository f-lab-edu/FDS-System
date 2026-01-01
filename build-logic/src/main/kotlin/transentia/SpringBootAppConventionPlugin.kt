package transentia

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

class SpringBootAppConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.pluginManager.apply("org.springframework.boot")
        target.pluginManager.apply("io.spring.dependency-management")
        target.pluginManager.apply("org.jetbrains.kotlin.jvm")
        target.pluginManager.apply("org.jetbrains.kotlin.plugin.spring")
        target.pluginManager.apply("org.jetbrains.kotlin.plugin.allopen")
        target.pluginManager.apply("org.jetbrains.kotlin.kapt")

        // Spring Cloud BOM 추가 (Spring Boot 3.3.2와 호환)
        target.extensions.getByType(io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension::class.java).apply {
            imports {
                mavenBom("org.springframework.cloud:spring-cloud-dependencies:2023.0.3")
            }
        }

        target.extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }

        target.tasks.withType(KotlinCompile::class.java).configureEach {
            kotlinOptions {
                jvmTarget = "21"
                freeCompilerArgs = freeCompilerArgs + "-Xjsr305=strict"
            }
        }

        // Spring Boot 앱 필수 의존성
        target.dependencies {
            add("implementation", "org.springframework.boot:spring-boot-starter-web")
            add("implementation", "org.springframework.boot:spring-boot-starter-validation")
            add("implementation", "org.springframework.boot:spring-boot-starter-json")
            add("implementation", "org.jetbrains.kotlin:kotlin-reflect")

            // Observability - Actuator + Prometheus + Tracing
            add("implementation", "org.springframework.boot:spring-boot-starter-actuator")
            add("implementation", "io.micrometer:micrometer-registry-prometheus")
            add("implementation", "io.micrometer:micrometer-tracing-bridge-otel")

            add("testImplementation", "org.springframework.boot:spring-boot-starter-test")
        }

        target.tasks.withType(Test::class.java).configureEach {
            useJUnitPlatform()
        }
    }
}