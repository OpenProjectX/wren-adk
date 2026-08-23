package org.openprojectx.wren.adk.autoconfigure

import org.openprojectx.wren.adk.WrenLlmProvider
import org.openprojectx.wren.adk.WrenTransport
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Configuration for the Wren-backed ADK agent, bound from `wren.adk.*`. */
@ConfigurationProperties(prefix = "wren.adk")
data class WrenAdkProperties(
    /** Which LLM backend to use. */
    val provider: WrenLlmProvider = WrenLlmProvider.GEMINI,
    /**
     * Model name for [provider], e.g. `gemini-2.0-flash` or `claude-sonnet-4-5`.
     * Blank picks a sensible default for the chosen provider.
     */
    val model: String = "",
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
    val anthropic: Anthropic = Anthropic(),
    val gemini: Gemini = Gemini(),
) {
    /** Resolved model name, falling back to a per-provider default. */
    fun resolvedModel(): String = model.ifBlank {
        when (provider) {
            WrenLlmProvider.GEMINI -> "gemini-2.0-flash"
            WrenLlmProvider.ANTHROPIC -> "claude-sonnet-4-5"
        }
    }

    /**
     * Gemini settings. Leave [apiKey] unset to read `GOOGLE_API_KEY` from the
     * environment.
     */
    data class Gemini(val apiKey: String = "")

    /**
     * Anthropic settings. Leave [apiKey] unset to let the SDK read
     * `ANTHROPIC_API_KEY` from the environment — which is what `.env` supplies.
     */
    data class Anthropic(
        /** Sent as `x-api-key`. What api.anthropic.com expects. */
        val apiKey: String = "",
        /**
         * Sent as `Authorization: Bearer …`. What most Anthropic-compatible
         * gateways expect. Takes precedence over [apiKey] when both are set.
         */
        val authToken: String = "",
        /** Point at a gateway that speaks the Anthropic API. */
        val baseUrl: String = "",
        val maxTokens: Int = 8192,
        val timeout: Duration = Duration.ofMinutes(2),
        val maxRetries: Int = 2,
        /**
         * Send `thinking: {type: "disabled"}`. ADK cannot parse `thinking`
         * response blocks, and several compatible gateways emit them.
         */
        val disableThinking: Boolean = true,
    )

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
