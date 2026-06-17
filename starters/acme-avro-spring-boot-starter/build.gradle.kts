plugins {
    id("acme.java-conventions")
}

dependencies {
    api(platform(project(":platform:acme-bom")))
    api(libs.spring.kafka)
    api(libs.avro)
    api(libs.kafka.avro.serializer)
}
