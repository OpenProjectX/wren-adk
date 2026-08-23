package org.openprojectx.wren.adk.app

import com.google.adk.models.Claude
import com.google.adk.models.LlmRequest
import com.google.genai.types.Content
import com.google.genai.types.Part
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.openprojectx.wren.adk.WrenAnthropicSettings
import org.openprojectx.wren.adk.WrenLlmProvider
import org.openprojectx.wren.adk.WrenLlms
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Live check against whatever Anthropic-compatible endpoint the environment
 * points at — api.anthropic.com or a gateway such as Aliyun MaaS.
 *
 * Skipped unless ANTHROPIC_BASE_URL is set, so the default suite stays offline,
 * fast and free.
 */
@EnabledIfEnvironmentVariable(named = "ANTHROPIC_BASE_URL", matches = ".+")
class AnthropicCompatibleLiveTest {

    @Test
    fun `the configured endpoint answers a generate call`() {
        val llm = WrenLlms.create(
            provider = WrenLlmProvider.ANTHROPIC,
            model = System.getenv("ANTHROPIC_MODEL") ?: "claude-sonnet-4-5",
            anthropic = WrenAnthropicSettings(
                apiKey = System.getenv("ANTHROPIC_API_KEY").orEmpty(),
                authToken = System.getenv("ANTHROPIC_AUTH_TOKEN").orEmpty(),
                baseUrl = System.getenv("ANTHROPIC_BASE_URL").orEmpty(),
                maxTokens = 64,
            ),
        )
        assertIs<Claude>(llm)

        val request = LlmRequest.builder()
            .contents(
                listOf(
                    Content.builder().role("user")
                        .parts(listOf(Part.builder().text("Reply with exactly: WREN_OK").build()))
                        .build(),
                ),
            )
            .build()

        val text = llm.generateContent(request, false)
            .blockingIterable().joinToString("") { r ->
                r.content().orElse(null)?.parts()?.orElse(emptyList())
                    ?.mapNotNull { it.text().orElse(null) }?.joinToString("") ?: ""
            }

        println("endpoint replied: $text")
        assertTrue(text.isNotBlank(), "endpoint returned no text")
    }
}
