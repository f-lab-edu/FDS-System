plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    // 소비 모듈에 전파돼야 하니 api 로 두는 게 맞음
    api("org.springframework.boot:spring-boot-starter-web")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.boot:spring-boot-autoconfigure")

    implementation(project(":shared-domain-error"))

    compileOnly("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
