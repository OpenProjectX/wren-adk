package org.openprojectx.wren.adk.app

import com.google.adk.agents.RunConfig
import com.google.adk.events.Event
import com.google.adk.runner.Runner
import com.google.adk.sessions.BaseSessionService
import com.google.genai.types.Content
import com.google.genai.types.Part
import org.openprojectx.wren.adk.autoconfigure.WrenAdkProperties
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** A chat turn: the user's question plus the conversation it belongs to. */
data class ChatRequest(val message: String, val sessionId: String? = null)

/** Identifies a fresh conversation. */
data class SessionResponse(val sessionId: String)

/**
 * Streams an agent turn to the browser over Server-Sent Events.
 *
 * ADK emits a [Event] stream per turn: intermediate tool calls, tool results and
 * the model's text. Rather than waiting for the whole turn, each event is
 * forwarded as it arrives so the UI can show the agent working — which matters
 * here because a Wren turn typically inspects the schema before it queries.
 */
@RestController
@RequestMapping("/api/chat")
@CrossOrigin
class ChatController(
    private val runner: Runner,
    private val sessionService: BaseSessionService,
    private val properties: WrenAdkProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newCachedThreadPool()
    private val known = ConcurrentHashMap.newKeySet<String>()

    private val userId = "web"

    @PostMapping("/session")
    fun newSession(): SessionResponse = SessionResponse(createSession())

    @GetMapping("/health")
    fun health(): Map<String, Any> = mapOf(
        "status" to "up",
        "agent" to properties.agentName,
        "model" to properties.model,
        "transport" to properties.mcp.transport.name,
        "readOnly" to properties.readOnly,
    )

    @PostMapping(produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun chat(@RequestBody request: ChatRequest): SseEmitter {
        // 0 = no timeout: a turn that inspects the schema then queries can take
        // a while, and an emitter timeout surfaces as a truncated answer.
        val emitter = SseEmitter(0L)
        val sessionId = request.sessionId?.takeIf { it in known } ?: createSession()

        executor.submit {
            try {
                emitter.send(SseEmitter.event().name("session").data(sessionId))

                val message = Content.builder()
                    .role("user")
                    .parts(listOf(Part.builder().text(request.message).build()))
                    .build()

                runner.runAsync(userId, sessionId, message, RunConfig.builder().build())
                    .blockingForEach { event -> emitter.send(toSse(event)) }

                emitter.send(SseEmitter.event().name("done").data("ok"))
                emitter.complete()
            } catch (e: Exception) {
                log.warn("chat turn failed for session {}", sessionId, e)
                runCatching {
                    emitter.send(
                        SseEmitter.event().name("error")
                            .data(e.message ?: e::class.java.simpleName),
                    )
                }
                emitter.complete()
            }
        }
        return emitter
    }

    /**
     * Splits an ADK event into what the UI can render: tool calls, tool results
     * and assistant text are visually distinct, so a user can see which models
     * were inspected and which SQL ran.
     */
    private fun toSse(event: Event): SseEmitter.SseEventBuilder {
        val calls = event.functionCalls()
        if (calls.isNotEmpty()) {
            val names = calls.mapNotNull { it.name().orElse(null) }
            val args = calls.firstOrNull()?.args()?.orElse(null)?.toString().orEmpty()
            return SseEmitter.event().name("tool")
                .data(mapOf("tools" to names, "args" to args.take(4000)))
        }
        if (event.functionResponses().isNotEmpty()) {
            val text = event.stringifyContent().take(8000)
            return SseEmitter.event().name("result").data(mapOf("text" to text))
        }
        return SseEmitter.event().name("message")
            .data(mapOf("text" to event.stringifyContent()))
    }

    private fun createSession(): String {
        val id = UUID.randomUUID().toString()
        sessionService
            .createSession(properties.appName, userId, ConcurrentHashMap(), id)
            .blockingGet()
        known.add(id)
        return id
    }
}
