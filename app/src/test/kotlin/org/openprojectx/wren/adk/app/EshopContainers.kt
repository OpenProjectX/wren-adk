package org.openprojectx.wren.adk.app

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Shared containers for the integration tests.
 *
 * Both are singletons started once per JVM and reused across test classes —
 * Testcontainers' Ryuk reaps them at exit. Starting a Wren container per class
 * would dominate the suite runtime, since it has to build the MDL manifest
 * before it can serve.
 */
object EshopContainers {
    private val log = LoggerFactory.getLogger(EshopContainers::class.java)

    /** Image built from `docker/wrenai` in openprojectx-helm-charts. */
    val wrenImage: String =
        System.getenv("WREN_IMAGE") ?: "ghcr.io/openprojectx/wrenai:0.1.0"

    private val network: Network = Network.newNetwork()

    /** Postgres preloaded with the e-shop fixture in the `wrenai` schema. */
    val postgres: PostgreSQLContainer<*> =
        PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("eshop")
            .withUsername("eshop")
            .withPassword("eshop")
            .withNetwork(network)
            .withNetworkAliases("db")
            // Files in this directory are executed in name order on first start.
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("db/eshop.sql"),
                "/docker-entrypoint-initdb.d/01-eshop.sql",
            )
            .apply { start() }

    /**
     * Wren serving its MCP tools over streamable HTTP against [postgres].
     *
     * The container creates a connection profile from the checked-in
     * `connection.yml` placeholders (resolved from the env below), compiles the
     * MDL, and only then starts serving — so a successful wait strategy means
     * the semantic layer is genuinely ready, not merely that a port is open.
     */
    val wren: GenericContainer<*> by lazy {
        GenericContainer(wrenImage)
            .withNetwork(network)
            .withExposedPorts(8080)
            .withEnv(
                mapOf(
                    // `db` is the alias on the shared network; 5432 is the
                    // container-internal port, not the mapped one.
                    "POSTGRES_HOST" to "db",
                    "POSTGRES_PORT" to "5432",
                    "POSTGRES_DATABASE" to postgres.databaseName,
                    "POSTGRES_USER" to postgres.username,
                    "POSTGRES_PASSWORD" to postgres.password,
                    "HOME" to "/tmp",
                ),
            )
            .withCopyFileToContainer(
                MountableFile.forClasspathResource("wren-project/"),
                "/project/",
            )
            .withCreateContainerCmdModifier { cmd -> cmd.withUser("root") }
            .withCommand(
                "sh", "-c",
                """
                set -e
                cd /project
                wren profile add test --from-file connection.yml
                wren context set-profile test
                wren context build
                exec wren serve mcp --transport http --host 0.0.0.0 --port 8080 -q
                """.trimIndent(),
            )
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(4)))
            .withLogConsumer(Slf4jLogConsumer(log).withPrefix("wren"))
            .apply { start() }
    }

    /** MCP endpoint of the Wren container, as seen from the host. */
    val mcpUrl: String
        get() = "http://${wren.host}:${wren.getMappedPort(8080)}/mcp"
}
