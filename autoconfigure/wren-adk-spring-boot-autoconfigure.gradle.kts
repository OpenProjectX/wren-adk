plugins {
    id("buildsrc.convention.kotlin-jvm")
    `kotlin-kapt`
}

description = "Spring Boot auto-configuration for Wren-backed Google ADK agents"

dependencies {

    api(project(":core"))

    val bootBom = platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")

    implementation(bootBom)
    kapt(bootBom)

    implementation("org.springframework.boot:spring-boot-autoconfigure")
    api("org.springframework.boot:spring-boot-starter")
    kapt("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation(bootBom)
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
