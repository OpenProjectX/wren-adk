package org.openprojectx.wren.adk.app

import com.google.adk.agents.RunConfig
import com.google.adk.artifacts.InMemoryArtifactService
import com.google.adk.events.Event
import com.google.adk.memory.InMemoryMemoryService
import com.google.adk.runner.Runner
import com.google.adk.sessions.InMemorySessionService
import com.google.genai.types.Content
import com.google.genai.types.Part
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.openprojectx.wren.adk.WrenAgents
import org.openprojectx.wren.adk.WrenAnthropicSettings
import org.openprojectx.wren.adk.WrenLlmProvider
import org.openprojectx.wren.adk.WrenLlms
import org.openprojectx.wren.adk.WrenMcpSettings
import org.openprojectx.wren.adk.WrenToolsets
import org.openprojectx.wren.adk.WrenTransport
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertTrue

/**
 * The full loop: the model calls Wren's MCP tools, Wren queries Postgres
 * through the semantic layer, and the model answers from the result.
 *
 * This is the path the chat UI drives, and the only one that proves tool
 * calling survives an Anthropic-compatible gateway — `tool_use` blocks are
 * where a compatible endpoint is most likely to diverge from the real API.
 *
 * Skipped unless ANTHROPIC_BASE_URL is set.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_BASE_URL", matches = ".+")
class WrenAgentLiveTest {

    private fun ask(question: String): List<Event> {
        val toolset = WrenToolsets.create(
            WrenMcpSettings(
                transport = WrenTransport.STREAMABLE_HTTP,
                url = EshopContainers.mcpUrl,
            ),
        )
        try {
            val llm = WrenLlms.create(
                provider = WrenLlmProvider.ANTHROPIC,
                model = System.getenv("ANTHROPIC_MODEL") ?: "claude-sonnet-4-5",
                anthropic = WrenAnthropicSettings(
                    apiKey = System.getenv("ANTHROPIC_API_KEY").orEmpty(),
                    authToken = System.getenv("ANTHROPIC_AUTH_TOKEN").orEmpty(),
                    baseUrl = System.getenv("ANTHROPIC_BASE_URL").orEmpty(),
                    maxTokens = 2048,
                ),
            )
            val agent = WrenAgents.analyticsAgent(llm = llm, toolset = toolset)

            val sessions = InMemorySessionService()
            val runner = Runner.builder()
                .agent(agent)
                .appName("wren-adk-live")
                .artifactService(InMemoryArtifactService())
                .sessionService(sessions)
                .memoryService(InMemoryMemoryService())
                .build()

            val sessionId = UUID.randomUUID().toString()
            sessions.createSession("wren-adk-live", "test", ConcurrentHashMap(), sessionId)
                .blockingGet()

            val message = Content.builder().role("user")
                .parts(listOf(Part.builder().text(question).build()))
                .build()

            return runner.runAsync("test", sessionId, message, RunConfig.builder().build())
                .toList().blockingGet()
        } finally {
            toolset.close()
        }
    }

    @Test
    fun `the agent queries wren and answers from the data`() {
        val events = ask(
            "How many orders are in the database? Use the tools to check, " +
                "then reply with just the number.",
        )

        val toolsCalled = events.flatMap { it.functionCalls() }.mapNotNull { it.name().orElse(null) }
        val answer = events.joinToString(" ") { it.stringifyContent() }

        println("--- tools called: $toolsCalled")
        println("--- answer: ${answer.takeLast(400)}")

        assertTrue(toolsCalled.isNotEmpty(), "the agent must call at least one Wren tool")
        assertTrue(
            toolsCalled.any { it in setOf("run_sql", "query_cube") },
            "expected a query tool, got $toolsCalled",
        )
        // The fixture holds exactly 212 orders.
        assertTrue("212" in answer, "expected the real row count 212 in: $answer")
    }
}
