plugins {
    id("transentia.spring-library")
}

dependencies {
    implementation(project(":transfer-domain"))
    implementation(project(":common-application"))
    implementation(project(":common-domain"))
    implementation(project(":kafka-model"))
}
