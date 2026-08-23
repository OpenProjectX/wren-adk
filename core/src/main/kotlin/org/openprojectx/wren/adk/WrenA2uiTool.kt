package org.openprojectx.wren.adk

import com.google.adk.tools.BaseTool
import com.google.adk.tools.ToolContext
import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.google.genai.types.Type
import io.reactivex.rxjava3.core.Single
import java.net.URI
import java.util.Optional

/**
 * Gives the model a bounded way to publish A2UI v0.9 surfaces to compatible clients.
 *
 * The tool deliberately accepts protocol messages as generic JSON in its function declaration:
 * Gemini's schema subset cannot express A2UI's component union and dynamic value bindings. The
 * server therefore performs the security and lifecycle checks before echoing the messages into the
 * ADK function response. The React client performs the catalog's full component-schema validation.
 */
class WrenA2uiTool : BaseTool(NAME, DESCRIPTION) {

    override fun declaration(): Optional<FunctionDeclaration> = Optional.of(
        FunctionDeclaration.builder()
            .name(name())
            .description(description())
            .parameters(PARAMETERS)
            .build(),
    )

    override fun runAsync(
        args: Map<String, Any>,
        toolContext: ToolContext,
    ): Single<Map<String, Any>> = Single.fromCallable { renderResult(args) }

    internal fun renderResult(args: Map<String, Any>): Map<String, Any> =
        try {
            render(args)
        } catch (error: IllegalArgumentException) {
            mapOf(
                "error" to (
                    "The A2UI surface was rejected: ${error.message ?: "invalid A2UI messages"}. " +
                        "Correct the messages and call render_a2ui again."
                    ),
            )
        }

    internal fun render(args: Map<String, Any>): Map<String, Any> {
        val messages = args[MESSAGES] as? List<*>
            ?: throw IllegalArgumentException("render_a2ui requires a messages array")
        val normalizedMessages = normalizeVersions(messages)
        validateBatch(normalizedMessages)
        return mapOf(MESSAGES to normalizedMessages)
    }

    companion object {
        const val NAME = "render_a2ui"
        const val BASIC_CATALOG = "https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json"

        private const val MESSAGES = "messages"
        private const val MAX_MESSAGES = 8
        private const val MAX_COMPONENTS = 100
        private const val MAX_DEPTH = 12
        private const val MAX_STRING_LENGTH = 16_384

        private val SAFE_COMPONENTS = setOf(
            "Text",
            "Row",
            "Column",
            "List",
            "Card",
            "Tabs",
            "Divider",
            "Modal",
            "Button",
            "TextField",
            "CheckBox",
            "ChoicePicker",
            "Slider",
            "DateTimeInput",
        )

        private val OPERATIONS = setOf(
            "createSurface",
            "updateComponents",
            "updateDataModel",
        )

        private const val DESCRIPTION = """
            Render a safe interactive A2UI v0.9 surface in the client. Call this only after using
            the Wren tools needed to obtain every displayed fact. Pass one complete surface as a
            messages array: createSurface, updateComponents, then optionally updateDataModel.
            Use catalogId https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json and a root
            component whose id is root. Supported components are Text, Row, Column, List, Card,
            Tabs, Divider, Modal, Button, TextField, CheckBox, ChoicePicker, Slider and
            DateTimeInput. Use {"path":"/field"} bindings for data supplied by updateDataModel.
            Buttons may use only server events: {"event":{"name":"...","context":{...}}}.
            Do not send HTML, executable code, custom SVG, URLs, charts, tables or functionCall
            actions. Keep the surface concise and include all component references by ID.
            Every message must have the exact string "version":"v0.9" (or "v0.9.1").
        """

        private val PARAMETERS: Schema = Schema.builder()
            .type(Type.Known.OBJECT)
            .properties(
                mapOf(
                    MESSAGES to Schema.builder()
                        .type(Type.Known.ARRAY)
                        .description(
                            "A complete A2UI v0.9/v0.9.1 message batch for one surface. " +
                                "Each item is a JSON object with version and exactly one operation.",
                        )
                        .minItems(2)
                        .maxItems(MAX_MESSAGES.toLong())
                        .items(
                            Schema.builder()
                                .type(Type.Known.OBJECT)
                                .description("One A2UI protocol message.")
                                .build(),
                        )
                        .build(),
                ),
            )
            .required(MESSAGES)
            .build()

        internal fun validateBatch(messages: List<*>) {
            require(messages.size in 2..MAX_MESSAGES) {
                "A2UI batches must contain between 2 and $MAX_MESSAGES messages"
            }

            val records = messages.mapIndexed { index, value -> value.asStringMap("messages[$index]") }
            val operationNames = records.mapIndexed { index, message ->
                require(message.keys.all { it == "version" || it in OPERATIONS }) {
                    "messages[$index] contains unsupported fields"
                }
                require(message["version"] == "v0.9" || message["version"] == "v0.9.1") {
                    "messages[$index].version must be v0.9 or v0.9.1"
                }
                val operations = OPERATIONS.filter(message::containsKey)
                require(operations.size == 1) { "messages[$index] must contain exactly one operation" }
                operations.single()
            }

            require(operationNames.first() == "createSurface") {
                "The first A2UI message must create the surface"
            }
            require(operationNames.count { it == "createSurface" } == 1) {
                "An A2UI batch must create exactly one surface"
            }
            require("updateComponents" in operationNames) {
                "An A2UI batch must define components"
            }

            val create = records.first()["createSurface"].asStringMap("createSurface")
            require(create.keys.all { it in setOf("surfaceId", "catalogId", "theme", "sendDataModel") }) {
                "createSurface contains unsupported fields"
            }
            val surfaceId = create.requiredString("surfaceId")
            require(surfaceId.length <= 128) { "surfaceId is too long" }
            require(create["catalogId"] == BASIC_CATALOG) { "Only the A2UI basic catalog is allowed" }

            var rootDefined = false
            records.zip(operationNames).forEachIndexed { index, (message, operation) ->
                val payload = message[operation].asStringMap("messages[$index].$operation")
                require(payload.requiredString("surfaceId") == surfaceId) {
                    "Every message in a batch must target the same surface"
                }
                when (operation) {
                    "updateComponents" -> {
                        require(payload.keys.all { it in setOf("surfaceId", "components") }) {
                            "updateComponents contains unsupported fields"
                        }
                        val components = payload["components"] as? List<*>
                            ?: throw IllegalArgumentException("updateComponents.components must be an array")
                        require(components.isNotEmpty() && components.size <= MAX_COMPONENTS) {
                            "A surface must contain between 1 and $MAX_COMPONENTS components"
                        }
                        val ids = mutableSetOf<String>()
                        components.forEachIndexed { componentIndex, rawComponent ->
                            val component = rawComponent.asStringMap("components[$componentIndex]")
                            val id = component.requiredString("id")
                            require(id.length <= 128 && ids.add(id)) { "Component IDs must be unique and at most 128 characters" }
                            rootDefined = rootDefined || id == "root"
                            require(component.requiredString("component") in SAFE_COMPONENTS) {
                                "Component '${component["component"]}' is not allowed"
                            }
                            validateComponent(component, componentIndex)
                            validateJson(component, depth = 0)
                        }
                    }

                    "updateDataModel" -> {
                        require(payload.keys.all { it in setOf("surfaceId", "path", "value") }) {
                            "updateDataModel contains unsupported fields"
                        }
                        payload["path"]?.let { require(it is String && it.startsWith("/")) {
                            "updateDataModel.path must be an absolute JSON pointer"
                        } }
                        validateJson(payload["value"], depth = 0)
                    }
                }
            }
            require(rootDefined) { "At least one updateComponents message must define component id 'root'" }
        }

        private fun normalizeVersions(messages: List<*>): List<Map<String, Any?>> =
            messages.mapIndexed { index, value ->
                val message = value.asStringMap("messages[$index]")
                val canonicalVersion = when (val version = message["version"]) {
                    null -> "v0.9"
                    "v0.9", "0.9", "v0.9.0", "0.9.0" -> "v0.9"
                    "v0.9.1", "0.9.1" -> "v0.9.1"
                    is Number -> if (version.toDouble() == 0.9) "v0.9" else null
                    else -> null
                } ?: throw IllegalArgumentException(
                    "messages[$index].version '${message["version"]}' is unsupported; use v0.9 or v0.9.1",
                )
                message + ("version" to canonicalVersion)
            }

        private fun validateComponent(component: Map<String, Any?>, index: Int) {
            require(component.keys.none { it in setOf("html", "script", "src", "url", "svgPath") }) {
                "components[$index] contains unsafe content"
            }

            val action = component["action"] ?: return
            val actionMap = action.asStringMap("components[$index].action")
            require(actionMap.keys == setOf("event")) { "Only server event actions are allowed" }
            val event = actionMap["event"].asStringMap("components[$index].action.event")
            require(event.keys.all { it in setOf("name", "context") }) { "Event actions contain unsupported fields" }
            require(event.requiredString("name").length <= 128) { "Event names are too long" }
            event["context"]?.let { validateJson(it, depth = 0) }
        }

        private fun validateJson(value: Any?, depth: Int) {
            require(depth <= MAX_DEPTH) { "A2UI data is nested too deeply" }
            when (value) {
                null, is Boolean, is Number -> Unit
                is String -> require(value.length <= MAX_STRING_LENGTH) { "A2UI strings are too long" }
                is List<*> -> {
                    require(value.size <= 500) { "A2UI arrays are too large" }
                    value.forEach { validateJson(it, depth + 1) }
                }
                is Map<*, *> -> {
                    require(value.size <= 256 && value.keys.all { it is String }) { "A2UI objects are invalid or too large" }
                    value.values.forEach { validateJson(it, depth + 1) }
                }
                else -> throw IllegalArgumentException("A2UI values must be JSON-compatible")
            }
        }

        private fun Any?.asStringMap(path: String): Map<String, Any?> {
            val map = this as? Map<*, *> ?: throw IllegalArgumentException("$path must be an object")
            require(map.keys.all { it is String }) { "$path must use string keys" }
            @Suppress("UNCHECKED_CAST")
            return map as Map<String, Any?>
        }

        private fun Map<String, Any?>.requiredString(name: String): String {
            val value = this[name] as? String
            require(!value.isNullOrBlank()) { "$name must be a non-empty string" }
            return value
        }
    }
}
