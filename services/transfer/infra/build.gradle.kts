plugins {
    id("transentia.spring-library")
    id("transentia.spring-jpa")
    id("transentia.kafka-convention")
    id("transentia.code-coverage")
    `java-test-fixtures`
}

dependencies {
    implementation(project(":transfer-application"))
    implementation(project(":transfer-domain"))
    implementation(project(":common-domain"))
    implementation(project(":kafka-producer"))
    implementation(project(":kafka-model"))

    implementation("io.confluent:kafka-avro-serializer:7.9.2")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")

    // Resilience4j — Circuit Breaker / Retry / Bulkhead / TimeLimiter
    implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
    implementation("io.github.resilience4j:resilience4j-micrometer:2.2.0")
    implementation("org.springframework.boot:spring-boot-starter-aop")

    // testFixtures — 다른 모듈에서 testFixturesImplementation 으로 공유 가능한 인프라.
    testFixturesImplementation(platform("org.testcontainers:testcontainers-bom:1.20.4"))
    testFixturesImplementation("org.testcontainers:testcontainers")
    testFixturesImplementation("org.testcontainers:junit-jupiter")
    testFixturesImplementation("org.testcontainers:postgresql")
    testFixturesImplementation("com.redis:testcontainers-redis:2.2.2")
    testFixturesImplementation("org.springframework.boot:spring-boot-starter-data-redis")

    // 자체 모듈 테스트는 testFixtures 자동 포함
    testImplementation(testFixtures(project(":transfer-infra")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
}
