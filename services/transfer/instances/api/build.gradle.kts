plugins {
    id("transentia.spring-boot-app")
}

dependencies {
    implementation(project(":transfer-application"))
    implementation(project(":transfer-infra"))
    implementation(project(":common-application"))
    implementation(project(":common-domain"))

    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.flywaydb:flyway-core")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    
    // Apache HttpClient 5 for Connection Pool testing (Spring Boot 3.x uses HttpClient 5)
    testImplementation("org.apache.httpcomponents.client5:httpclient5:5.2.1")
}