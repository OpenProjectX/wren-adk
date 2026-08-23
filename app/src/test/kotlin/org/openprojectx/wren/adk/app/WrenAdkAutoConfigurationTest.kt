package org.openprojectx.wren.adk.app

import com.google.adk.agents.LlmAgent
import com.google.adk.runner.Runner
import com.google.adk.tools.mcp.McpToolset
import com.google.adk.web.AgentLoader
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the starter actually wires a working agent — the toolset resolves
 * against a live Wren, and the agent exposes it.
 *
 * Deliberately does not call the LLM: that would need GOOGLE_API_KEY and turn a
 * fast, deterministic test into a flaky, billable one. What is asserted here is
 * the wiring, which is what the starter is responsible for.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WrenAdkAutoConfigurationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun wrenProperties(registry: DynamicPropertyRegistry) {
            registry.add("wren.adk.mcp.transport") { "STREAMABLE_HTTP" }
            registry.add("wren.adk.mcp.url") { EshopContainers.mcpUrl }
        }
    }

    @Autowired lateinit var toolset: McpToolset
    @Autowired lateinit var agent: LlmAgent
    @Autowired lateinit var runner: Runner
    @Autowired lateinit var agentLoader: AgentLoader
    @LocalServerPort var port: Int = 0

    @Test
    fun `the toolset bean resolves wren's tools`() {
        val names = toolset.getTools(null).map { it.name() }.toList().blockingGet()
        assertTrue("run_sql" in names, "expected run_sql among $names")
    }

    @Test
    fun `the agent is configured and carries the wren toolset`() {
        assertEquals("wren_analyst", agent.name())
        assertTrue(agent.toolsets().isNotEmpty(), "agent should own the Wren toolset")
        val names = agent.tools().blockingGet().map { it.name() }
        assertTrue("render_a2ui" in names, "agent should expose render_a2ui among $names")
    }

    @Test
    fun `a runner is available for chat turns`() {
        assertEquals("wren-adk", runner.appName())
    }

    @Test
    fun `the ADK Dev UI can discover the wren agent`() {
        assertEquals(listOf("wren_analyst"), agentLoader.listAgents())
        assertEquals(agent, agentLoader.loadAgent("wren_analyst"))
    }

    @Test
    fun `the ADK Dev UI and agent registry are served`() {
        val client = HttpClient.newHttpClient()
        fun get(path: String): HttpResponse<String> = client.send(
            HttpRequest.newBuilder(URI("http://localhost:$port$path")).GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )

        val apps = get("/list-apps")
        assertEquals(HttpStatus.OK.value(), apps.statusCode())
        assertEquals("[\"wren_analyst\"]", apps.body())

        val devUi = get("/dev-ui")
        assertEquals(HttpStatus.OK.value(), devUi.statusCode())
        assertTrue(devUi.body().contains("<title>Agent Development Kit Dev UI</title>"))
    }

    @Test
    fun `the UI can report A2UI renderer errors to the server log`() {
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI("http://localhost:$port/ui/diagnostics/a2ui"))
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """{"message":"Validation failed for List","surfaceId":"result"}""",
                    ),
                )
                .build(),
            HttpResponse.BodyHandlers.discarding(),
        )

        assertEquals(HttpStatus.NO_CONTENT.value(), response.statusCode())
    }
}
