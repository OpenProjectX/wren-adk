package org.openprojectx.wren.adk

import com.google.adk.tools.mcp.McpToolset
import com.google.adk.tools.mcp.StdioServerParameters
import com.google.adk.tools.mcp.StreamableHttpServerParameters
import java.io.File

/**
 * Builds the [McpToolset] that exposes Wren's semantic layer to an ADK agent.
 *
 * The toolset surfaces Wren's MCP tools — `run_sql`, `dry_plan`, `query_cube`,
 * `list_models`, `describe_model`, `list_cubes`, `recall_queries` and the rest —
 * as ADK tools. The agent writes SQL against **model names**; Wren expands each
 * model into a CTE and resolves the real schema, so the model never needs to
 * know the physical table layout.
 */
object WrenToolsets {

    /**
     * @param settings where and how to reach Wren
     * @param readOnly withhold the `store_query` write tool (default true)
     * @param transpileOnly disable every tool that touches the warehouse (default false)
     */
    @JvmStatic
    @JvmOverloads
    fun create(
        settings: WrenMcpSettings,
        readOnly: Boolean = true,
        transpileOnly: Boolean = false,
    ): McpToolset = when (settings.transport) {
        WrenTransport.STDIO -> stdio(settings, readOnly, transpileOnly)
        WrenTransport.STREAMABLE_HTTP -> http(settings)
    }

    private fun stdio(
        settings: WrenMcpSettings,
        readOnly: Boolean,
        transpileOnly: Boolean,
    ): McpToolset {
        val env = buildMap {
            // The CLI resolves ${'$'}{VAR} placeholders in its connection profile from
            // the environment and from a .env it discovers relative to its
            // *working directory* — which is this JVM's, not the project's.
            // ADK's stdio parameters expose no working directory, so the
            // project's own .env is read here and passed through. Without this
            // the child dies with "Profile references ${'$'}{POSTGRES_HOST} but it is
            // not set in the environment or any discovered .env file."
            settings.projectHome?.let { putAll(readDotEnv(File(it, ".env"))) }
            putAll(settings.env) // explicit settings always win
            settings.projectHome?.let { put("WREN_PROJECT_HOME", it) }
        }
        val params = StdioServerParameters.builder()
            .command(settings.command)
            .args(settings.effectiveArgs(readOnly, transpileOnly))
            .env(env)
            .build()
        return McpToolset(params.toServerParameters())
    }

    /** Best-effort .env read; an unreadable or absent file is not an error. */
    private fun readDotEnv(file: File): Map<String, String> = try {
        if (!file.isFile) {
            emptyMap()
        } else {
            file.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && "=" in it }
                .associate { it.substringBefore("=").trim() to it.substringAfter("=").trim() }
        }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun http(settings: WrenMcpSettings): McpToolset {
        val params = StreamableHttpServerParameters.builder()
            .url(settings.url)
            .headers(settings.headers)
            .timeout(settings.timeout)
            .build()
        return McpToolset(params)
    }
}
