plugins {
    id("transentia.spring-library")
    id("transentia.spring-jpa")
    id("transentia.kafka-convention")
    id("transentia.code-coverage")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:2023.0.3")
    }
}

dependencies {
    implementation(project(":fds-application"))
    implementation(project(":fds-domain"))
    implementation(project(":common-domain"))
    implementation(project(":kafka-model"))

    // Spring Cloud Stream - 직접 추가
    implementation("org.springframework.cloud:spring-cloud-stream")
    implementation("org.springframework.cloud:spring-cloud-stream-binder-kafka-streams")
    
    // Kafka Streams Avro Serde - 필수!
    implementation("io.confluent:kafka-streams-avro-serde:7.9.2")
    implementation("io.confluent:kafka-avro-serializer:7.9.2")
    implementation("org.apache.avro:avro:1.11.4")
    implementation("io.hypersistence:hypersistence-utils-hibernate-63:3.7.0")

    // ML 어댑터의 메트릭 노출용
    implementation("io.micrometer:micrometer-core")

    // Slack 알림 어댑터 — RestClient (spring-web) + Resilience4j CB
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testRuntimeOnly("org.postgresql:postgresql")
}
