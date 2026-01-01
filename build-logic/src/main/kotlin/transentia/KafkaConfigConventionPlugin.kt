package transentia

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

class KafkaConfigConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        // 공통 플러그인
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        pluginManager.apply("io.spring.dependency-management")
        pluginManager.apply("org.jetbrains.kotlin.kapt")
        pluginManager.apply("org.jetbrains.kotlin.plugin.allopen")
        pluginManager.apply("org.jetbrains.kotlin.plugin.spring")

        // JVM 21 설정
        extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }

        // 필요한 최소 의존성만 추가
        dependencies {
            add("implementation", "org.springframework.boot:spring-boot-starter-validation")
            add("kapt", "org.springframework.boot:spring-boot-configuration-processor")
        }
    }
}
