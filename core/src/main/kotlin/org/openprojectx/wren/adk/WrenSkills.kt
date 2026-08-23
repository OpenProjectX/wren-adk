package org.openprojectx.wren.adk

import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Loads Wren's official workflow guides and adapts them for an MCP-driven agent.
 *
 * ### Why translation is needed
 *
 * `wren skills get <name>` returns guidance written for an agent driving the
 * **CLI** — it says things like `wren --sql '...'` and `wren memory recall -q`.
 * An ADK agent has no shell; it has Wren's **MCP tools**. Handed the guide
 * verbatim, a model will try to run commands it cannot run.
 *
 * So the workflow is kept (it is the vendor's own, and better than a paraphrase)
 * and a translation table is prepended mapping each command to the equivalent
 * tool.
 *
 * Loading is best-effort: if the CLI is absent — which is normal when Wren runs
 * as a remote HTTP service rather than a local process — the agent falls back
 * to [DEFAULT_WREN_INSTRUCTION] alone.
 */
object WrenSkills {

    /** Maps the CLI commands the guides reference onto the MCP tools an agent has. */
    private val TRANSLATION = """
        ## Reading the guidance below

        The workflow below is Wren's official guidance, but it is written for an
        agent driving the `wren` command line. You have no shell. You have Wren's
        MCP tools instead. Translate as you read:

        | The guide says | You call |
        |---|---|
        | `wren --sql '...'` / `wren query` | `run_sql` |
        | `wren dry-plan --sql '...'` | `dry_plan` |
        | `wren dry-run --sql '...'` | `dry_run` |
        | `wren memory fetch -q '...'` | `get_context` |
        | `wren memory recall -q '...'` | `recall_queries` |
        | `wren memory store --nl ... --sql ...` | `store_query` |
        | `wren context show` / listing models | `list_models`, `describe_model`, `describe_schema` |
        | `wren cube list` / `describe` / `query` | `list_cubes`, `describe_cube`, `query_cube` |
        | business rules / instructions | `get_instructions` |
        | `wren context build`, `profile`, `serve`, installation | **skip — not yours to do** |

        Ignore every section about installing, configuring, connecting or building
        a project: that is already done, and you cannot run those commands. Follow
        only the parts about answering a question from data.

    """.trimIndent()

    /**
     * Runs `wren skills get <name>` for each name and returns the combined text,
     * translated for MCP use. Returns `null` when nothing could be loaded.
     *
     * @param names skill names, e.g. `usage`
     * @param command the CLI to invoke
     * @param timeout per-skill timeout
     */
    @JvmStatic
    @JvmOverloads
    fun load(
        names: List<String>,
        command: String = "wren",
        timeout: Duration = Duration.ofSeconds(20),
    ): String? {
        if (names.isEmpty()) return null
        val loaded = names.mapNotNull { fetch(it, command, timeout) }
        if (loaded.isEmpty()) return null
        return TRANSLATION + "\n\n" + loaded.joinToString("\n\n---\n\n")
    }

    private fun fetch(name: String, command: String, timeout: Duration): String? = try {
        val process = ProcessBuilder(command, "skills", "get", name)
            .redirectErrorStream(false)
            .start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            null
        } else if (process.exitValue() != 0 || text.isBlank()) {
            null
        } else {
            stripFrontMatter(text)
        }
    } catch (_: Exception) {
        // CLI missing or not executable — expected when Wren is remote.
        null
    }

    /** Removes the leading YAML front matter; it is metadata, not guidance. */
    internal fun stripFrontMatter(text: String): String {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("---")) return text.trim()
        val end = trimmed.indexOf("\n---", startIndex = 3)
        if (end < 0) return text.trim()
        return trimmed.substring(trimmed.indexOf('\n', end + 1) + 1).trim()
    }
}
