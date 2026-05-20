plugins {
    `kotlin-dsl`
    `java-gradle-plugin`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
    google()
}

dependencies {
    implementation(gradleApi())
    implementation(localGroovy())

    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.25")
    implementation("org.springframework.boot:spring-boot-gradle-plugin:3.3.2")
    implementation("io.spring.gradle:dependency-management-plugin:1.1.7")

    // 코드 품질 자동화 — detekt + ktlint
    implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:1.23.6")
    implementation("org.jlleitschuh.gradle:ktlint-gradle:12.1.1")
}

gradlePlugin {
    plugins {
        create("rootConventionsPlugin") {
            id = "transentia.root-conventions"
            implementationClass = "transentia.RootConventionsPlugin"
        }
        create("kotlinLibraryPlugin") {
            id = "transentia.kotlin-library"
            implementationClass = "transentia.KotlinLibraryConventionPlugin"
        }
        create("springLibraryPlugin") {
            id = "transentia.spring-library"
            implementationClass = "transentia.SpringLibraryConventionPlugin"
        }
        create("springBootAppPlugin") {
            id = "transentia.spring-boot-app"
            implementationClass = "transentia.SpringBootAppConventionPlugin"
        }
        create("springJpaPlugin") {
            id = "transentia.spring-jpa"
            implementationClass = "transentia.SpringJpaConventionPlugin"
        }
        create("codeCoveragePlugin") {
            id = "transentia.code-coverage"
            implementationClass = "transentia.CodeCoverageConventionPlugin"
        }

        // Kafka Config 용 플러그인
        create("kafkaConfigConventionPlugin") {
            id = "transentia.kafka-config-convention"
            implementationClass = "transentia.KafkaConfigConventionPlugin"
        }

        // Consumer/Producer용 Kafka 플러그인
        create("kafkaConventionPlugin") {
            id = "transentia.kafka-convention"
            implementationClass = "transentia.KafkaConventionPlugin"
        }

        // 코드 품질 (detekt + ktlint)
        create("codeQualityPlugin") {
            id = "transentia.code-quality"
            implementationClass = "transentia.CodeQualityConventionPlugin"
        }
    }
}

