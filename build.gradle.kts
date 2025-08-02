import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("org.springframework.boot") version "3.3.2" apply false
    id("io.spring.dependency-management") version "1.1.7"
    id("com.google.devtools.ksp") version "2.2.0-2.0.2" apply false

    kotlin("jvm") version "2.2.0" apply false
    kotlin("plugin.spring") version "2.2.0" apply false
    kotlin("plugin.jpa") version "2.2.0" apply false
    kotlin("plugin.allopen") version "2.2.0" apply false
}

group = "io.github.hyungkishin"
version = "0.0.1-SNAPSHOT"

val kotlinVersion = "2.2.0"

allprojects {
    repositories {
        mavenCentral()
    }
}

object CoveragePolicy {
    val line = 0.80.toBigDecimal()
    val branch = 0.70.toBigDecimal()
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jetbrains.kotlin.plugin.spring")
    apply(plugin = "org.jetbrains.kotlin.plugin.jpa")
    apply(plugin = "org.jetbrains.kotlin.plugin.allopen")
    apply(plugin = "com.google.devtools.ksp")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "jacoco")

    extensions.configure<org.jetbrains.kotlin.allopen.gradle.AllOpenExtension> {
        annotation("org.springframework.stereotype.Component")
        annotation("org.springframework.transaction.annotation.Transactional")
        annotation("org.springframework.context.annotation.Configuration")
        annotation("org.springframework.boot.autoconfigure.SpringBootApplication")
    }

    configurations.all {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.jetbrains.kotlin") {
                useVersion("2.2.0")
            }
        }
    }

    if (name == "transfer-api") {
        apply(plugin = "org.springframework.boot")
    }

    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }

    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "failed", "skipped")
            showStandardStreams = true
        }
    }

    the<DependencyManagementExtension>().apply {
        imports {
            mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.2")
            mavenBom("io.kotest:kotest-bom:5.8.0")
        }
    }

    project.dependencies.apply {
        add("implementation", "org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
        add("implementation", "org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")

        add("testImplementation", "org.springframework.boot:spring-boot-starter-test")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        add("testImplementation", "io.kotest:kotest-runner-junit5")
        add("testImplementation", "io.kotest:kotest-assertions-core")
    }

    if (name in listOf("transfer-domain", "transfer-infra")) {
        fun Project.jacocoClassDirs(): FileTree =
            fileTree(layout.buildDirectory.dir("classes/kotlin/main")) {
                exclude("**/dto/**", "**/config/**")
            }

        tasks.named<JacocoReport>("jacocoTestReport") {

            dependsOn(tasks.named<Test>("test"))
            finalizedBy("jacocoTestCoverageVerification")

            classDirectories.setFrom(files(jacocoClassDirs()))
            sourceDirectories.setFrom(project.layout.projectDirectory.dir("src/main/kotlin"))
            executionData.setFrom(fileTree(layout.buildDirectory).include("/jacoco/test.exec"))

            reports {
                xml.required.set(true)
                html.required.set(true)
            }
        }

        tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
            classDirectories.setFrom(files(jacocoClassDirs()))
            sourceDirectories.setFrom(project.layout.projectDirectory.dir("src/main/kotlin"))
            executionData.setFrom(fileTree(layout.buildDirectory).include("/jacoco/test.exec"))

            violationRules {
                rule {
                    enabled = true
                    element = "BUNDLE"
                    limit {
                        counter = "LINE"
                        value = "COVERED_RATIO"
                        minimum = CoveragePolicy.line
                    }
                    limit {
                        counter = "BRANCH"
                        value = "COVERED_RATIO"
                        minimum = CoveragePolicy.branch
                    }
                }
            }
        }

        tasks.register("testCoverage") {
            group = "verification"
            description = "Run tests, generate report, and verify coverage"
            dependsOn("test", "jacocoTestReport", "jacocoTestCoverageVerification")
            tasks["jacocoTestReport"].mustRunAfter("test")
            tasks["jacocoTestCoverageVerification"].mustRunAfter("jacocoTestReport")
        }
    }

    if (System.getenv("CI") == "true" || project.hasProperty("enableCoverage")) {
        tasks.named("check") {
            dependsOn("testCoverage")
        }
    }
}