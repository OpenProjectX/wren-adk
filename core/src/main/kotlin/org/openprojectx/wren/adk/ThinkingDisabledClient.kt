package org.openprojectx.wren.adk

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.ThinkingConfigDisabled
import com.anthropic.services.blocking.MessageService
import java.lang.reflect.Proxy

/**
 * Wraps an [AnthropicClient] so every `messages().create(...)` call carries
 * `thinking: {type: "disabled"}`.
 *
 * ### Why this exists
 *
 * ADK's `Claude` model converts response content blocks with a handler that
 * covers only `text` and `tool_use`; anything else hits
 * `throw new UnsupportedOperationException("Not supported yet.")`
 * (`Claude.anthropicContentBlockToPart`). Several Anthropic-compatible
 * gateways — Aliyun MaaS serving Qwen, for one — emit a `thinking` block ahead
 * of the text, so every call fails before the agent sees a token.
 *
 * ADK's conversion methods are private, so they cannot be overridden. The
 * request side is reachable though: disabling thinking makes the endpoint
 * return a plain `text` block, which ADK does understand.
 *
 * Both `AnthropicClient` and `MessageService` are interfaces, so this is a
 * dynamic proxy rather than a fork. Remove it once ADK handles thinking blocks.
 */
object ThinkingDisabledClient {

    private val DISABLED: ThinkingConfigDisabled = ThinkingConfigDisabled.builder().build()

    /** Returns [delegate] with thinking disabled on every message create call. */
    @JvmStatic
    fun wrap(delegate: AnthropicClient): AnthropicClient =
        Proxy.newProxyInstance(
            AnthropicClient::class.java.classLoader,
            arrayOf(AnthropicClient::class.java),
        ) { _, method, args ->
            val result = invoke(method, delegate, args)
            if (method.name == "messages" && result is MessageService) wrapMessages(result) else result
        } as AnthropicClient

    private fun wrapMessages(delegate: MessageService): MessageService =
        Proxy.newProxyInstance(
            MessageService::class.java.classLoader,
            arrayOf(MessageService::class.java),
        ) { _, method, args ->
            val patched = args?.map { arg ->
                if (arg is MessageCreateParams) arg.toBuilder().thinking(DISABLED).build() else arg
            }?.toTypedArray()
            invoke(method, delegate, patched)
        } as MessageService

    /** Unwraps reflection failures so callers see the original exception. */
    private fun invoke(method: java.lang.reflect.Method, target: Any, args: Array<out Any?>?): Any? =
        try {
            if (args == null) method.invoke(target) else method.invoke(target, *args)
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
}
