import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.spring") version "1.9.24"
    kotlin("plugin.jpa") version "2.2.0"
    kotlin("kapt") version "1.9.24"
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
    id("jacoco")
    id("org.sonarqube") version "4.4.1.3373"
}

group = "io.github.hyungkishin"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-mustache")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Kotlin 컴파일 설정
tasks.withType<KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs += "-Xjsr305=strict"
    }
}

// Coverage 정책 (LINE ≥ 80% / BRANCH ≥ 70%)
object CoveragePolicy {
    val line = 0.80.toBigDecimal()
    val branch = 0.70.toBigDecimal()
}

jacoco {
    toolVersion = "0.8.11"
}

tasks.test {
    useJUnitPlatform()
    finalizedBy("jacocoTestReport") // 순서 보장
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test)
    finalizedBy("jacocoTestCoverageVerification") // 리포트 생성 후 검증 실행

    classDirectories.setFrom(files(jacocoClassDirs()))
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(fileTree(layout.buildDirectory).include("/jacoco/test.exec"))

    reports {
        xml.required.set(true)
        html.required.set(true)
    }
}

tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
    classDirectories.setFrom(files(jacocoClassDirs()))
    sourceDirectories.setFrom(files("src/main/kotlin"))
    executionData.setFrom(fileTree(layout.buildDirectory).include("/jacoco/test.exec"))

    violationRules {
        rule {
            enabled = true
            element = "BUNDLE"
            limit {
                counter = "LINE"
                value = "COVEREDRATIO"
                minimum = CoveragePolicy.line
            }
            limit {
                counter = "BRANCH"
                value = "COVEREDRATIO"
                minimum = CoveragePolicy.branch
            }
        }
    }
}

// test + report + verify를 순차 실행하는 task
tasks.register("testCoverage") {
    group = "verification"
    description = "Run tests, generate report, and verify coverage"

    dependsOn("test", "jacocoTestReport", "jacocoTestCoverageVerification")
    tasks["jacocoTestReport"].mustRunAfter("test")
    tasks["jacocoTestCoverageVerification"].mustRunAfter("jacocoTestReport")
}

// check 시 coverage 실행 옵션 ( CI에서만 실행되도록 )
if (System.getenv("CI") == "true" || project.hasProperty("enableCoverage")) {
    tasks.named("check") {
        dependsOn("testCoverage")
    }
}

sonarqube {
    properties {
        property("sonar.gradle.skipCompile", "true")
        property("sonar.projectKey", "f-lab-edu_FDS-System")
        property("sonar.organization", "f-lab-edu-1")
        property("sonar.host.url", "https://sonarcloud.io")
        property("sonar.sources", mutableListOf("src/main/kotlin"))
        property("sonar.tests", mutableListOf("src/test/kotlin"))
        property("sonar.sourceEncoding", "UTF-8")
        property("sonar.java.binaries", mutableListOf("build/classes"))
        property(
            "sonar.coverage.jacoco.xmlReportPaths",
            mutableListOf("build/reports/jacoco/test/jacocoTestReport.xml")
        )

        property("sonar.login", System.getenv("SONAR_TOKEN"))
        property(
            "sonar.test.exclusions", sonarExcludePatterns.toMutableList()
        )
        property("sonar.test.inclusions", mutableListOf("**/*Test.kt"))
    }
}