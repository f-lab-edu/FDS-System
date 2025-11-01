plugins {
    id("transentia.spring-boot-app")
    id("transentia.kafka-convention")
}

dependencies {
    // Spring Batch
    implementation("org.springframework.boot:spring-boot-starter-batch")
    
    implementation(project(":transfer-application"))
    implementation(project(":transfer-infra"))
    implementation(project(":common-application"))
    implementation(project(":common-domain"))
    implementation(project(":kafka-config"))
    implementation(project(":kafka-producer"))
    implementation(project(":kafka-model"))

    implementation("io.confluent:kafka-avro-serializer:7.9.2")

    // 테스트 의존성 추가
    testImplementation(project(":transfer-domain"))  // TransferEvent 사용을 위해 TODO application 으로 eventType 분리 개선
    testImplementation("org.springframework.boot:spring-boot-starter-jdbc")
    testImplementation("org.springframework.batch:spring-batch-test")
    testImplementation("org.mockito.kotlin:mockito-kotlin:4.1.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

tasks.withType<Test> {
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}

tasks.register<Test>("performanceTest") {
    useJUnitPlatform()
    include("**/*PerformanceTest*")
    group = "verification"
    description = "성능 테스트 실행"
}