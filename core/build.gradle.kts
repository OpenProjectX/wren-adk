plugins {
    id("buildsrc.convention.kotlin-jvm")
}

description = "Wren semantic layer integration for Google ADK agents"

dependencies {
    // ADK brings the MCP Java SDK it is compatible with — do not pin it here.
    api(libs.googleAdk)

    testImplementation(kotlin("test"))
}
