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
 * Settings for the Anthropic provider, and for any endpoint that speaks the
 * Anthropic API.
 *
 * Two auth styles are supported, because compatible gateways differ:
 * - [apiKey] sends `x-api-key`, which is what api.anthropic.com expects.
 * - [authToken] sends `Authorization: Bearer …`, which most Anthropic-compatible
 *   gateways expect (Aliyun MaaS, Bedrock-style proxies, internal routers).
 *
 * Set [baseUrl] to point at such a gateway. Leave all three blank to let the
 * SDK read `ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_BASE_URL`
 * from the environment — which is what `.env` feeds.
 */
data class WrenAnthropicSettings(
    val apiKey: String = "",
    val authToken: String = "",
    val baseUrl: String = "",
    val maxTokens: Int = 8192,
    val timeout: Duration = Duration.ofMinutes(2),
    val maxRetries: Int = 2,
    /**
     * Send `thinking: {type: "disabled"}` on every request.
     *
     * ADK's `Claude` cannot parse `thinking` response blocks — it throws
     * `UnsupportedOperationException` — and several compatible gateways emit
     * them by default. Leave this on unless your endpoint never does.
     * See [ThinkingDisabledClient].
     */
    val disableThinking: Boolean = true,
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

        WrenLlmProvider.ANTHROPIC -> {
            val client = anthropicClient(anthropic).let {
                if (anthropic.disableThinking) ThinkingDisabledClient.wrap(it) else it
            }
            Claude(model, client, anthropic.maxTokens)
        }
    }

    private fun anthropicClient(settings: WrenAnthropicSettings) =
        if (settings.apiKey.isBlank() && settings.authToken.isBlank() && settings.baseUrl.isBlank()) {
            // Reads ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN / ANTHROPIC_BASE_URL
            // from the environment.
            AnthropicOkHttpClient.fromEnv()
        } else {
            AnthropicOkHttpClient.builder()
                .apply {
                    if (settings.baseUrl.isNotBlank()) baseUrl(settings.baseUrl)
                    // The SDK requires one credential. Prefer the bearer token
                    // when both are present: a gateway that issues one usually
                    // ignores x-api-key entirely.
                    when {
                        settings.authToken.isNotBlank() -> authToken(settings.authToken)
                        settings.apiKey.isNotBlank() -> apiKey(settings.apiKey)
                        else -> apiKey(System.getenv("ANTHROPIC_API_KEY").orEmpty())
                    }
                }
                .timeout(settings.timeout)
                .maxRetries(settings.maxRetries)
                .build()
        }
}
