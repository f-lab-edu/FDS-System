plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
}

dependencies {
    implementation(project(":transfer-domain"))
    implementation(project(":shared-kernel"))
    implementation(project(":shared-domain-error"))

    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-tx")

    testImplementation("io.kotest:kotest-runner-junit5")
    testImplementation("io.kotest:kotest-assertions-core")
}
