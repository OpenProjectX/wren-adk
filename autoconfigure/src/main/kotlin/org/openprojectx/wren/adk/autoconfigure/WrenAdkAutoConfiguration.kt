package org.openprojectx.wren.adk.autoconfigure

import com.google.adk.agents.LlmAgent
import com.google.adk.models.BaseLlm
import com.google.adk.artifacts.InMemoryArtifactService
import com.google.adk.memory.InMemoryMemoryService
import com.google.adk.runner.Runner
import com.google.adk.sessions.BaseSessionService
import com.google.adk.sessions.InMemorySessionService
import com.google.adk.tools.mcp.McpToolset
import org.openprojectx.wren.adk.DEFAULT_WREN_INSTRUCTION
import org.openprojectx.wren.adk.WrenAgents
import org.openprojectx.wren.adk.WrenAnthropicSettings
import org.openprojectx.wren.adk.WrenGeminiSettings
import org.openprojectx.wren.adk.WrenLlms
import org.openprojectx.wren.adk.WrenMcpSettings
import org.openprojectx.wren.adk.WrenToolsets
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Wires a Wren-backed ADK agent.
 *
 * Every bean is `@ConditionalOnMissingBean`, so any of them can be replaced by
 * declaring your own — a persistent session service, a differently-instructed
 * agent, a multi-agent root, and so on.
 *
 * Disable entirely with `wren.adk.enabled=false`.
 */
@AutoConfiguration
@EnableConfigurationProperties(WrenAdkProperties::class)
@ConditionalOnProperty(prefix = "wren.adk", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class WrenAdkAutoConfiguration {

    /** Closed on context shutdown, which terminates the spawned `wren` process in STDIO mode. */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    fun wrenToolset(properties: WrenAdkProperties): McpToolset {
        val settings = WrenMcpSettings(
            transport = properties.mcp.transport,
            command = properties.mcp.command,
            args = properties.mcp.args,
            projectHome = properties.mcp.projectHome,
            env = properties.mcp.env,
            url = properties.mcp.url,
            headers = properties.mcp.headers,
            timeout = properties.mcp.timeout,
        )
        return WrenToolsets.create(
            settings = settings,
            readOnly = properties.readOnly,
            transpileOnly = properties.transpileOnly,
        )
    }

    /**
     * The model the agent runs on. Declare your own [BaseLlm] bean to use a
     * provider this starter does not cover — ADK core also ships an
     * OpenAI-compatible chat-completions client.
     */
    @Bean
    @ConditionalOnMissingBean
    fun wrenLlm(properties: WrenAdkProperties): BaseLlm = WrenLlms.create(
        provider = properties.provider,
        model = properties.resolvedModel(),
        anthropic = WrenAnthropicSettings(
            apiKey = properties.anthropic.apiKey,
            authToken = properties.anthropic.authToken,
            baseUrl = properties.anthropic.baseUrl,
            maxTokens = properties.anthropic.maxTokens,
            timeout = properties.anthropic.timeout,
            maxRetries = properties.anthropic.maxRetries,
            disableThinking = properties.anthropic.disableThinking,
        ),
        gemini = WrenGeminiSettings(apiKey = properties.gemini.apiKey),
    )

    @Bean
    @ConditionalOnMissingBean
    fun wrenAgent(properties: WrenAdkProperties, llm: BaseLlm, toolset: McpToolset): LlmAgent =
        WrenAgents.analyticsAgent(
            llm = llm,
            toolset = toolset,
            name = properties.agentName,
            instruction = properties.instruction.ifBlank { DEFAULT_WREN_INSTRUCTION },
        )

    @Bean
    @ConditionalOnMissingBean
    fun wrenSessionService(): BaseSessionService = InMemorySessionService()

    @Bean
    @ConditionalOnMissingBean
    fun wrenRunner(
        agent: LlmAgent,
        sessionService: BaseSessionService,
        properties: WrenAdkProperties,
    ): Runner = Runner.builder()
        .agent(agent)
        .appName(properties.appName)
        .artifactService(InMemoryArtifactService())
        .sessionService(sessionService)
        .memoryService(InMemoryMemoryService())
        .build()
}
