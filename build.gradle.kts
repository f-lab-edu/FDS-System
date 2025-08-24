import io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("org.springframework.boot") version "3.3.2" apply false
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "1.9.25" apply false
    kotlin("plugin.spring") version "1.9.25" apply false
    kotlin("plugin.jpa") version "1.9.25" apply false
    kotlin("plugin.allopen") version "1.9.25" apply false
}

group = "io.github.hyungkishin"
version = "0.0.1-SNAPSHOT"

val kotlinVersion = "1.9.25"

// 실행 가능한 Spring Boot 앱 모듈
val bootApps = setOf("transfer-api", "transfer-publisher", "transfer-consumer")
// Spring 관련 플러그인 필요한 모듈
val springModules =
    setOf("transfer-api", "transfer-publisher", "transfer-consumer", "transfer-infra", "delivery-http-error")

// JPA 필요한 모듈
val jpaModules = setOf("transfer-infra")

// 순수 Kotlin 모듈
val pureKotlinModules = setOf("shared-common", "transfer-domain", "transfer-application")

val kspModules = emptySet<String>() // ksp 쓰는 모듈 있으면 이름 추가

subprojects {

    // 공통: Kotlin/JVM
    apply(plugin = "org.jetbrains.kotlin.jvm")

    // Spring 관련 플러그인은 springModules + bootApps에 적용
    if (name in springModules || name in bootApps) {
        apply(plugin = "org.jetbrains.kotlin.plugin.spring")
        apply(plugin = "org.jetbrains.kotlin.plugin.allopen")
        // @Transactional 등 proxy 대상 자동 open
        extensions.configure<org.jetbrains.kotlin.allopen.gradle.AllOpenExtension> {
            annotation("org.springframework.stereotype.Component")
            annotation("org.springframework.transaction.annotation.Transactional")
            annotation("org.springframework.context.annotation.Configuration")
            annotation("org.springframework.boot.autoconfigure.SpringBootApplication")
        }
    }

    // JPA 사용하는 모듈만
    if (name in jpaModules) {
        apply(plugin = "org.jetbrains.kotlin.plugin.jpa")
    }

    // KSP 사용하는 모듈만
    if (name in kspModules) {
        apply(plugin = "com.google.devtools.ksp")
    }

    // Boot 애플리케이션 모듈만 spring-boot 플러그인
    if (name in bootApps) {
        apply(plugin = "org.springframework.boot")
        apply(plugin = "io.spring.dependency-management")
    } else {
        // 나머지는 BOM만 사용
        apply(plugin = "io.spring.dependency-management")
    }

    // 자바/코틀린 툴체인
    extensions.configure<JavaPluginExtension> {
        toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    }
    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    // 테스트 공통
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
    }

    // BOM 관리 (부트 플러그인 미적용 모듈에서도 버전 일관성 유지)
    plugins.withId("io.spring.dependency-management") {
        the<DependencyManagementExtension>().apply {
            imports {
                mavenBom("org.springframework.boot:spring-boot-dependencies:3.3.2")
                mavenBom("io.kotest:kotest-bom:5.8.0")
            }
        }
    }

    // 공통 라이브러리(필요 없는 모듈은 개별 build.gradle에서 제외 가능)
    dependencies {
        add("implementation", "org.jetbrains.kotlin:kotlin-stdlib-jdk8:$kotlinVersion")
        add("implementation", "org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")

        add("testImplementation", "org.springframework.boot:spring-boot-starter-test")
        add("testRuntimeOnly", "org.junit.platform:junit-platform-launcher")
        add("testImplementation", "io.kotest:kotest-runner-junit5")
        add("testImplementation", "io.kotest:kotest-assertions-core")
    }

    // ── 커버리지(선택) : 특정 모듈에만 규칙 적용 ─────────────────────────
    if (name in setOf("transfer-domain", "transfer-infra")) {
        apply(plugin = "jacoco")

        fun Project.jacocoClassDirs() =
            fileTree(layout.buildDirectory.dir("classes/kotlin/main")) {
                exclude("**/dto/**", "**/config/**")
            }

        tasks.named<JacocoReport>("jacocoTestReport") {
            dependsOn(tasks.named<Test>("test"))
            finalizedBy("jacocoTestCoverageVerification")
            classDirectories.setFrom(files(jacocoClassDirs()))
            sourceDirectories.setFrom(project.layout.projectDirectory.dir("src/main/kotlin"))
            executionData.setFrom(fileTree(layout.buildDirectory).include("/jacoco/test.exec"))
            reports { xml.required.set(true); html.required.set(true) }
        }

        tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
            classDirectories.setFrom(files(jacocoClassDirs()))
            sourceDirectories.setFrom(project.layout.projectDirectory.dir("src/main/kotlin"))
            executionData.setFrom(fileTree(layout.buildDirectory).include("/jacoco/test.exec"))
            violationRules {
                rule {
                    enabled = true
                    element = "BUNDLE"
                    limit { counter = "LINE"; value = "COVERED_RATIO"; minimum = "0.80".toBigDecimal() }
                    limit { counter = "BRANCH"; value = "COVERED_RATIO"; minimum = "0.70".toBigDecimal() }
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

        if (System.getenv("CI") == "true" || project.hasProperty("enableCoverage")) {
            tasks.named("check") { dependsOn("testCoverage") }
        }
    }
}
