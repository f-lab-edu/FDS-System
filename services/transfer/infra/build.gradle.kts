plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    kotlin("plugin.jpa")
}

dependencies {
    implementation(project(":transfer-application"))
    implementation(project(":transfer-domain"))
    implementation(project(":shared-common"))

    // Spring Data JPA & JDBC (RDB 어댑터 구현)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // Kafka (이벤트 퍼블리셔 어댑터 구현)
    implementation("org.springframework.kafka:spring-kafka")

    // JSON 직렬화 (Kafka 메시지에 필요)
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // DB 드라이버
    runtimeOnly("org.postgresql:postgresql")

    // 테스트
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}
