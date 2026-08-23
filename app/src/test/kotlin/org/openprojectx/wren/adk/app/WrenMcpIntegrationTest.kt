package org.openprojectx.wren.adk.app

import org.junit.jupiter.api.Test
import org.openprojectx.wren.adk.WrenMcpSettings
import org.openprojectx.wren.adk.WrenToolsets
import org.openprojectx.wren.adk.WrenTransport
import kotlin.test.assertTrue

/**
 * Exercises the real MCP surface of a containerised Wren against the e-shop
 * fixture — the layer this project's whole value rests on.
 *
 * Uses [WrenToolsets] exactly as the auto-configuration does, so a breaking
 * change in the ADK MCP API fails here rather than at runtime.
 */
class WrenMcpIntegrationTest {

    private fun toolset() = WrenToolsets.create(
        WrenMcpSettings(
            transport = WrenTransport.STREAMABLE_HTTP,
            url = EshopContainers.mcpUrl,
        ),
    )

    /** McpToolset is not Closeable, so close it explicitly. */
    private fun toolNames(): Set<String> {
        val ts = toolset()
        try {
            return ts.getTools(null).map { it.name() }.toList().blockingGet().toSet()
        } finally {
            ts.close()
        }
    }

    @Test
    fun `wren exposes its semantic-layer tools over MCP`() {
        val names = toolNames()

        // The tools the agent instruction actually depends on.
        listOf("run_sql", "dry_plan", "list_models", "describe_model", "list_cubes")
            .forEach { assertTrue(it in names, "expected MCP tool '$it', got $names") }

        assertTrue(names.size >= 15, "expected the full tool surface, got ${names.size}: $names")
    }

    @Test
    fun `the write tool is withheld by default`() {
        val names = toolNames()
        assertTrue(
            "store_query" !in names,
            "store_query must stay hidden unless --allow-write is passed; got $names",
        )
    }
}
