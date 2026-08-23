package org.openprojectx.wren.adk

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.google.adk.models.BaseLlm
import com.google.adk.models.Claude
import com.google.adk.models.Gemini
import java.time.Duration

/** Which LLM backs the agent. */
enum class WrenLlmProvider {
    /** Google Gemini, resolved by name through ADK's registry. Uses GOOGLE_API_KEY or Vertex AI. */
    GEMINI,

    /** Anthropic Claude via `com.anthropic:anthropic-java`, which ships in ADK core. */
    ANTHROPIC,
}

/**
 * Settings for the Gemini provider.
 *
 * Leave [apiKey] blank to let the SDK read `GOOGLE_API_KEY` / `GEMINI_API_KEY`
 * from the environment. Note that unlike Claude, Gemini validates credentials
 * when it is constructed — a blank key with nothing in the environment throws
 * immediately rather than at first use.
 */
data class WrenGeminiSettings(
    val apiKey: String = "",
)

/**
 * Settings for the Anthropic provider.
 *
 * Leave [apiKey] blank to let the SDK read `ANTHROPIC_API_KEY` from the
 * environment — which is what `.env` feeds. Set [baseUrl] to route through a
 * gateway or proxy that speaks the Anthropic API.
 */
data class WrenAnthropicSettings(
    val apiKey: String = "",
    val baseUrl: String = "",
    val maxTokens: Int = 8192,
    val timeout: Duration = Duration.ofMinutes(2),
    val maxRetries: Int = 2,
)

/** Builds the [BaseLlm] the agent runs on. */
object WrenLlms {

    /**
     * @param provider which backend to use
     * @param model the model name, e.g. `gemini-2.0-flash` or `claude-sonnet-4-5`
     * @param anthropic settings used only when [provider] is [WrenLlmProvider.ANTHROPIC]
     * @param gemini settings used only when [provider] is [WrenLlmProvider.GEMINI]
     */
    @JvmStatic
    @JvmOverloads
    fun create(
        provider: WrenLlmProvider,
        model: String,
        anthropic: WrenAnthropicSettings = WrenAnthropicSettings(),
        gemini: WrenGeminiSettings = WrenGeminiSettings(),
    ): BaseLlm = when (provider) {
        WrenLlmProvider.GEMINI -> Gemini.builder()
            .modelName(model)
            .apply { if (gemini.apiKey.isNotBlank()) apiKey(gemini.apiKey) }
            .build()

        WrenLlmProvider.ANTHROPIC -> Claude(model, anthropicClient(anthropic), anthropic.maxTokens)
    }

    private fun anthropicClient(settings: WrenAnthropicSettings) =
        if (settings.apiKey.isBlank() && settings.baseUrl.isBlank()) {
            // Reads ANTHROPIC_API_KEY (and ANTHROPIC_BASE_URL) from the environment.
            AnthropicOkHttpClient.fromEnv()
        } else {
            AnthropicOkHttpClient.builder()
                .apply {
                    if (settings.apiKey.isNotBlank()) apiKey(settings.apiKey)
                    if (settings.baseUrl.isNotBlank()) baseUrl(settings.baseUrl)
                }
                .timeout(settings.timeout)
                .maxRetries(settings.maxRetries)
                .build()
        }
}
