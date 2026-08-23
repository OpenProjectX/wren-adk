package org.openprojectx.wren.adk.app

import com.google.adk.agents.LlmAgent
import com.google.adk.runner.Runner
import com.google.adk.tools.mcp.McpToolset
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
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

    @Test
    fun `the toolset bean resolves wren's tools`() {
        val names = toolset.getTools(null).map { it.name() }.toList().blockingGet()
        assertTrue("run_sql" in names, "expected run_sql among $names")
    }

    @Test
    fun `the agent is configured and carries the wren toolset`() {
        assertEquals("wren_analyst", agent.name())
        assertTrue(agent.toolsets().isNotEmpty(), "agent should own the Wren toolset")
    }

    @Test
    fun `a runner is available for chat turns`() {
        assertEquals("wren-adk", runner.appName())
    }
}
