package org.openprojectx.wren.adk

import java.time.Duration

/**
 * How the agent reaches a Wren MCP server.
 *
 * [STDIO] spawns `wren serve mcp` as a child process. It needs the `wren` CLI on
 * `PATH` — which is exactly what the `ghcr.io/openprojectx/wrenai` base image
 * provides — and keeps the semantic layer off the network entirely.
 *
 * [STREAMABLE_HTTP] talks to a Wren instance over HTTP. Use it for a sidecar
 * container on loopback, or a shared service.
 *
 * Note that Wren holds MCP session state in-process: a request routed to a
 * different replica is rejected with `Session not found`. A shared HTTP
 * endpoint therefore needs session affinity on the `mcp-session-id` header.
 */
enum class WrenTransport { STDIO, STREAMABLE_HTTP }

/**
 * Connection settings for a Wren MCP server.
 *
 * @property transport how to reach the server
 * @property command the CLI to spawn in [WrenTransport.STDIO] mode
 * @property args arguments for [command]
 * @property projectHome the Wren project root (models, relationships, target/mdl.json);
 *   exported as `WREN_PROJECT_HOME` to the spawned process
 * @property env extra environment for the spawned process — put warehouse
 *   credentials here rather than in a `.env` file inside an image
 * @property url base URL in [WrenTransport.STREAMABLE_HTTP] mode, e.g. `http://127.0.0.1:8080/mcp`
 * @property headers extra HTTP headers, e.g. a gateway token
 * @property timeout request timeout
 */
data class WrenMcpSettings(
    val transport: WrenTransport = WrenTransport.STDIO,
    val command: String = "wren",
    val args: List<String> = listOf("serve", "mcp"),
    val projectHome: String? = null,
    val env: Map<String, String> = emptyMap(),
    val url: String = "http://127.0.0.1:8080/mcp",
    val headers: Map<String, String> = emptyMap(),
    val timeout: Duration = Duration.ofSeconds(60),
) {
    /**
     * Arguments actually passed to the CLI, including the gating flags.
     *
     * @param readOnly withhold `--allow-write`, so the `store_query` tool is not exposed
     * @param transpileOnly add `--no-connect`, disabling `run_sql`, `dry_run` and
     *   `query_cube` — the agent can explore the schema and plan SQL but cannot
     *   reach the warehouse
     */
    fun effectiveArgs(readOnly: Boolean, transpileOnly: Boolean): List<String> = buildList {
        addAll(args)
        if (transpileOnly) add("--no-connect")
        if (!readOnly) add("--allow-write")
        projectHome?.let { addAll(listOf("--project", it)) }
    }
}
