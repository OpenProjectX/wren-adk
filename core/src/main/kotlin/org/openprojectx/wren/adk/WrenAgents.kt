package org.openprojectx.wren.adk

import com.google.adk.agents.LlmAgent
import com.google.adk.tools.BaseToolset

/** Default system instruction for an analytics agent backed by Wren. */
const val DEFAULT_WREN_INSTRUCTION: String = """
You are a data analyst. You answer questions about the business by querying a
governed semantic layer through the provided Wren tools.

How to work:
1. Start with `list_models` / `describe_model` when you do not already know the
   schema. Use `list_cubes` and `describe_cube` for pre-defined metrics — prefer
   a cube over hand-written aggregation SQL when one fits the question.
2. Check `recall_queries` first. If a confirmed NL to SQL pair already answers
   the question, reuse its SQL rather than inventing your own.
3. Read `get_instructions` for business rules that the schema cannot express.
4. Write SQL against MODEL names, never database table names. Do not qualify
   with a schema: write `FROM orders`, not `FROM public.orders`. Column names
   must match the model definition.
5. Validate anything non-trivial with `dry_plan` before running it.
6. Execute with `run_sql`.

Answering:
- Lead with the number or finding, then the supporting detail.
- State the assumptions that materially change the answer — especially which
  rows you counted. Order status is the usual trap: excluded or included
  cancellations and returns can move a revenue figure substantially.
- If a result looks implausible, say so rather than presenting it confidently.
- If the data cannot answer the question, say that plainly instead of
  substituting a different question you can answer.
- Never invent numbers. Every figure you report must come from a tool result.
"""

/** Factory for the Wren-backed analytics [LlmAgent]. */
object WrenAgents {

    /**
     * @param model the LLM to drive the agent, e.g. `gemini-2.0-flash`
     * @param toolset the Wren MCP toolset from [WrenToolsets.create]
     * @param name agent name, surfaced in ADK events and traces
     * @param description short description for multi-agent routing
     * @param instruction system instruction; defaults to [DEFAULT_WREN_INSTRUCTION]
     */
    @JvmStatic
    @JvmOverloads
    fun analyticsAgent(
        model: String,
        toolset: BaseToolset,
        name: String = "wren_analyst",
        description: String = "Answers business questions over a governed Wren semantic layer.",
        instruction: String = DEFAULT_WREN_INSTRUCTION,
    ): LlmAgent =
        LlmAgent.builder()
            .name(name)
            .description(description)
            .model(model)
            .instruction(instruction.trimIndent())
            .tools(listOf(toolset))
            .build()
}
