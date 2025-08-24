plugins {
    kotlin("jvm")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

dependencies {
    // Hibernate UserType/Type SPI, SharedSessionContractImplementor 등 참조용
    compileOnly("org.hibernate.orm:hibernate-core:6.5.2.Final")

    // JPA/Validation 애노/타입 참조용 (TO-BE 대응 필요. infra 쪽에서 제공)
    compileOnly("jakarta.persistence:jakarta.persistence-api:3.1.0")
    compileOnly("jakarta.validation:jakarta.validation-api:3.0.2")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
