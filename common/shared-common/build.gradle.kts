plugins {
    kotlin("jvm")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    api("org.slf4j:slf4j-api:2.0.13")
    compileOnly("jakarta.validation:jakarta.validation-api:3.1.1")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
