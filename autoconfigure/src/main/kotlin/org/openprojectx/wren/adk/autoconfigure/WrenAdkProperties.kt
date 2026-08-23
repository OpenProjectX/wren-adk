package org.openprojectx.wren.adk.autoconfigure

import org.openprojectx.wren.adk.WrenTransport
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Configuration for the Wren-backed ADK agent, bound from `wren.adk.*`. */
@ConfigurationProperties(prefix = "wren.adk")
data class WrenAdkProperties(
    /** LLM driving the agent. Requires GOOGLE_API_KEY (or Vertex AI configuration). */
    val model: String = "gemini-2.0-flash",
    /** Agent name, surfaced in ADK events and traces. */
    val agentName: String = "wren_analyst",
    /** Application name used for ADK session scoping. */
    val appName: String = "wren-adk",
    /** Override the system instruction. Blank keeps the built-in analytics prompt. */
    val instruction: String = "",
    /**
     * Withhold Wren's `store_query` write tool. Keep true unless the agent is
     * meant to persist confirmed NL to SQL pairs into the project.
     */
    val readOnly: Boolean = true,
    /**
     * Disable every tool that touches the warehouse (`--no-connect`). The agent
     * can still explore the schema and plan SQL. Useful for a dry-run tier.
     */
    val transpileOnly: Boolean = false,
    val mcp: Mcp = Mcp(),
) {
    /** How to reach the Wren MCP server. */
    data class Mcp(
        val transport: WrenTransport = WrenTransport.STDIO,
        /** CLI to spawn in STDIO mode — present on PATH in the wrenai base image. */
        val command: String = "wren",
        val args: List<String> = listOf("serve", "mcp"),
        /** Wren project root: models, relationships, target/mdl.json. */
        val projectHome: String? = null,
        /** Extra environment for the spawned process — warehouse credentials go here. */
        val env: Map<String, String> = emptyMap(),
        /** Base URL in STREAMABLE_HTTP mode. */
        val url: String = "http://127.0.0.1:8080/mcp",
        val headers: Map<String, String> = emptyMap(),
        val timeout: Duration = Duration.ofSeconds(60),
    )
}
