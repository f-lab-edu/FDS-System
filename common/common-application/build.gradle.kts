plugins {
    id("transentia.spring-library")
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-autoconfigure")

    implementation(project(":common-domain"))

    implementation("org.springframework.boot:spring-boot-starter-actuator") // 그라파나 대시보드 만드는거 에 재료
    implementation("io.micrometer:micrometer-tracing-bridge-brave") // traceId, spanId
    implementation("io.zipkin.reporter2:zipkin-reporter-brave")

    compileOnly("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
