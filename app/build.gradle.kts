plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.spring-kotlin")
    alias(libs.plugins.jib)
}

description = "Chat application: Google ADK agent over a Wren semantic layer"

dependencies {
    implementation(project(":wren-adk-spring-boot-starter"))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Reads a .env file at startup and exposes it to the Spring Environment,
    // so ANTHROPIC_API_KEY et al. resolve without exporting them by hand.
    implementation(libs.springDotenv)

    testImplementation(kotlin("test"))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // One BOM keeps every Testcontainers module on the same version.
    testImplementation(platform(libs.testcontainersBom))
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.postgresql:postgresql")
}

// Integration tests that hit a live LLM read their configuration from the
// project .env, the same file the application uses. Sourcing it into the shell
// is not enough: the Gradle daemon is long-lived, so test JVMs forked from it
// inherit the daemon's (stale) environment rather than yours. Reading the file
// here via a value source keeps it deterministic and configuration-cache safe.
val dotEnv: Provider<String> =
    providers.fileContents(rootProject.layout.projectDirectory.file(".env")).asText

tasks.withType<Test>().configureEach {
    dotEnv.orNull?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
        ?.forEach { line ->
            val key = line.substringBefore("=").trim()
            val value = line.substringAfter("=").trim()
            if (key.isNotEmpty()) environment(key, value)
        }

    // Gradle does not track a Test task's environment as an input, so without
    // this the build cache happily replays a result produced when these
    // variables were absent — live tests would show as "skipped" forever.
    inputs.property("dotEnvFingerprint", dotEnv.map { it.hashCode().toString() })
        .optional(true)
}

// NOTE: the Jib plugin (3.5.4) is not Gradle configuration-cache compatible —
// it fails with "Cannot invoke Project.getProjectDir() because this.project is
// null". This build enables the configuration cache in gradle.properties, so
// Jib tasks must be run with --no-configuration-cache:
//
//   ./gradlew :app:jibDockerBuild --no-configuration-cache
//
// The base image already carries the wren CLI, a JDK, Python and Node, so the
// agent can spawn `wren serve mcp` over stdio with nothing else installed.
// See docker/wrenai in openprojectx-helm-charts for how it is built.
jib {
    from {
        image = providers.gradleProperty("wrenBaseImage")
            .getOrElse("ghcr.io/openprojectx/wrenai:0.1.0")
        platforms {
            platform {
                architecture = "amd64"
                os = "linux"
            }
        }
    }
    to {
        image = providers.gradleProperty("appImage")
            .getOrElse("ghcr.io/openprojectx/wren-adk-app")
        tags = setOf(version.toString(), "latest")
    }
    container {
        // uid 10001 is the non-root `wren` user created by the base image.
        user = "10001"
        ports = listOf("8080")
        workingDirectory = "/project"
        jvmFlags = listOf("-XX:MaxRAMPercentage=75", "-XX:+UseContainerSupport")
        environment = mapOf("WREN_PROJECT_HOME" to "/project")
        creationTime = "USE_CURRENT_TIMESTAMP"
    }
    // The base image is Debian-based with its own entrypoint expectations;
    // let Jib own the entrypoint so the Spring app is PID 1.
    containerizingMode = "packaged"
}
