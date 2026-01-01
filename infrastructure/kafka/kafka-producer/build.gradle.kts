plugins {
    id("transentia.kotlin-library")
    id("transentia.kafka-convention")
}

dependencies {
    implementation(project(":kafka-config"))
    implementation("org.apache.avro:avro:1.11.4")
    implementation("io.confluent:kafka-avro-serializer:7.9.2")
}
