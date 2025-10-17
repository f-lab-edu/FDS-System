plugins {
    id("transentia.kotlin-library")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    api("org.slf4j:slf4j-api:2.0.13")
    compileOnly("jakarta.validation:jakarta.validation-api:3.0.2")
    api("com.fasterxml.jackson.core:jackson-databind:2.20.0")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
