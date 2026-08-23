package org.openprojectx.wren.adk.app

import com.google.adk.models.Claude
import com.google.adk.models.Gemini
import org.junit.jupiter.api.Test
import org.openprojectx.wren.adk.WrenAnthropicSettings
import org.openprojectx.wren.adk.WrenGeminiSettings
import org.openprojectx.wren.adk.WrenLlmProvider
import org.openprojectx.wren.adk.WrenLlms
import org.openprojectx.wren.adk.autoconfigure.WrenAdkProperties
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The provider switch is pure wiring, so it is asserted without a network call:
 * constructing a model must not contact the vendor.
 *
 * An explicit api-key is passed for Anthropic so the test does not depend on
 * ANTHROPIC_API_KEY being present in the environment.
 */
class WrenLlmProviderTest {

    @Test
    fun `anthropic provider builds a Claude model`() {
        val llm = WrenLlms.create(
            provider = WrenLlmProvider.ANTHROPIC,
            model = "claude-sonnet-4-5",
            anthropic = WrenAnthropicSettings(apiKey = "sk-ant-test-not-used"),
        )
        assertIs<Claude>(llm)
        assertEquals("claude-sonnet-4-5", llm.model())
    }

    @Test
    fun `gemini provider builds a Gemini model`() {
        // Gemini validates credentials when constructed, so supply one.
        val llm = WrenLlms.create(
            provider = WrenLlmProvider.GEMINI,
            model = "gemini-2.0-flash",
            gemini = WrenGeminiSettings(apiKey = "test-key-not-used"),
        )
        assertIs<Gemini>(llm)
        assertEquals("gemini-2.0-flash", llm.model())
    }

    @Test
    fun `each provider has a sensible default model`() {
        assertEquals(
            "claude-sonnet-4-5",
            WrenAdkProperties(provider = WrenLlmProvider.ANTHROPIC).resolvedModel(),
        )
        assertEquals(
            "gemini-2.0-flash",
            WrenAdkProperties(provider = WrenLlmProvider.GEMINI).resolvedModel(),
        )
        assertEquals(
            "claude-opus-4-1",
            WrenAdkProperties(provider = WrenLlmProvider.ANTHROPIC, model = "claude-opus-4-1")
                .resolvedModel(),
            "an explicit model must win over the default",
        )
    }
}
