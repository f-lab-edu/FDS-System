plugins {
    id("transentia.spring-boot-app")
    id("io.spring.dependency-management")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:2023.0.3")
    }
}

dependencies {
    implementation(project(":fds-application"))
    implementation(project(":fds-infra"))
    implementation(project(":common-application"))
    implementation(project(":common-domain"))

    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.flywaydb:flyway-core")

    // Spring Cloud Stream - 직접 추가
    implementation("org.springframework.cloud:spring-cloud-stream")
    implementation("org.springframework.cloud:spring-cloud-stream-binder-kafka-streams")

    implementation("io.confluent:kafka-avro-serializer:7.9.2")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}