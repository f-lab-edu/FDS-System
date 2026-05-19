plugins {
    id("transentia.spring-library")
    id("transentia.spring-jpa")
    id("transentia.kafka-convention")
    id("transentia.code-coverage")
}

dependencies {
    implementation(project(":transfer-application"))
    implementation(project(":transfer-domain"))
    implementation(project(":common-domain"))
    implementation(project(":kafka-producer"))
    implementation(project(":kafka-model"))

    implementation("io.confluent:kafka-avro-serializer:7.9.2")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Testcontainers 통합 테스트
    testImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("com.redis:testcontainers-redis:2.2.2")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
}
