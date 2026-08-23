package org.openprojectx.wren.adk

import com.google.adk.tools.BaseTool
import com.google.adk.tools.ToolContext
import com.google.genai.types.FunctionDeclaration
import com.google.genai.types.Schema
import com.google.genai.types.Type
import io.reactivex.rxjava3.core.Single
import org.slf4j.LoggerFactory
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
            val toolError =
                "The A2UI surface was rejected: ${error.message ?: "invalid A2UI messages"}. " +
                    "Correct the messages and call render_a2ui again."
            logger.warn("render_a2ui returned tool error: {}", toolError)
            mapOf("error" to toolError)
        }

    internal fun render(args: Map<String, Any>): Map<String, Any> {
        val messages = args[MESSAGES] as? List<*>
            ?: throw IllegalArgumentException("render_a2ui requires a messages array")
        val normalizedMessages = normalizeVersions(messages)
        validateBatch(normalizedMessages)
        return mapOf(MESSAGES to normalizedMessages)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(WrenA2uiTool::class.java)

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

        private val COMMON_COMPONENT_FIELDS = setOf("id", "component", "accessibility", "weight")
        private val CHECKABLE_FIELDS = setOf("checks", "isValid", "validationErrors")
        private val COMPONENT_FIELDS = mapOf(
            "Text" to setOf("text", "variant"),
            "Row" to setOf("children", "justify", "align"),
            "Column" to setOf("children", "justify", "align"),
            "List" to setOf("children", "direction", "align", "listStyle"),
            "Card" to setOf("child"),
            "Tabs" to setOf("tabs"),
            "Divider" to setOf("axis"),
            "Modal" to setOf("trigger", "content"),
            "Button" to setOf("child", "variant", "action") + CHECKABLE_FIELDS,
            "TextField" to setOf("label", "value", "variant", "validationRegexp") + CHECKABLE_FIELDS,
            "CheckBox" to setOf("label", "value") + CHECKABLE_FIELDS,
            "ChoicePicker" to setOf("label", "variant", "options", "value", "displayStyle", "filterable") + CHECKABLE_FIELDS,
            "Slider" to setOf("label", "min", "max", "value") + CHECKABLE_FIELDS,
            "DateTimeInput" to setOf("value", "enableDate", "enableTime", "min", "max", "label") + CHECKABLE_FIELDS,
        )

        private val REQUIRED_COMPONENT_FIELDS = mapOf(
            "Text" to setOf("text"),
            "Row" to setOf("children"),
            "Column" to setOf("children"),
            "List" to setOf("children"),
            "Card" to setOf("child"),
            "Tabs" to setOf("tabs"),
            "Modal" to setOf("trigger", "content"),
            "Button" to setOf("child"),
            "TextField" to setOf("label"),
            "CheckBox" to setOf("label", "value"),
            "ChoicePicker" to setOf("options", "value"),
            "Slider" to setOf("max", "value"),
            "DateTimeInput" to setOf("value"),
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

            Use this exact flattened shape (replace the IDs and displayed text as needed):
            {"messages":[
              {"version":"v0.9","createSurface":{"surfaceId":"result","catalogId":"https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json"}},
              {"version":"v0.9","updateComponents":{"surfaceId":"result","components":[
                {"id":"root","component":"Card","child":"content"},
                {"id":"content","component":"Column","children":["title","answer"]},
                {"id":"title","component":"Text","text":"Analysis","variant":"h2"},
                {"id":"answer","component":"Text","text":"The result"}
              ]}}
            ]}
            Put component properties directly beside id and component; never use a props wrapper.
            Every operation payload repeats the same surfaceId. updateDataModel uses optional path
            and value fields, never data.
            For repeated data, List.children is {"componentId":"row","path":"/rows"}; define
            row as a separate component. Never use items or template fields.
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
                val unsupported = message.keys - OPERATIONS - "version"
                require(unsupported.isEmpty()) {
                    "messages[$index] contains unsupported fields $unsupported; " +
                        "put operation data inside createSurface, updateComponents, or updateDataModel"
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

            val create = records.first()["createSurface"].asStringMap("messages[0].createSurface")
            require(create.keys.all { it in setOf("surfaceId", "catalogId", "theme", "sendDataModel") }) {
                "messages[0].createSurface contains unsupported fields " +
                    "${create.keys - setOf("surfaceId", "catalogId", "theme", "sendDataModel")}"
            }
            val surfaceId = create.requiredString("surfaceId", "messages[0].createSurface.surfaceId")
            require(surfaceId.length <= 128) { "messages[0].createSurface.surfaceId is too long" }
            require(create["catalogId"] == BASIC_CATALOG) {
                "messages[0].createSurface.catalogId must equal $BASIC_CATALOG"
            }

            var rootDefined = false
            records.zip(operationNames).forEachIndexed { index, (message, operation) ->
                val payload = message[operation].asStringMap("messages[$index].$operation")
                require(payload.requiredString("surfaceId", "messages[$index].$operation.surfaceId") == surfaceId) {
                    "messages[$index].$operation.surfaceId must equal '$surfaceId'"
                }
                when (operation) {
                    "updateComponents" -> {
                        require(payload.keys.all { it in setOf("surfaceId", "components") }) {
                            "messages[$index].updateComponents contains unsupported fields " +
                                "${payload.keys - setOf("surfaceId", "components")}"
                        }
                        val components = payload["components"] as? List<*>
                            ?: throw IllegalArgumentException(
                                "messages[$index].updateComponents.components must be an array",
                            )
                        require(components.isNotEmpty() && components.size <= MAX_COMPONENTS) {
                            "A surface must contain between 1 and $MAX_COMPONENTS components"
                        }
                        val ids = mutableSetOf<String>()
                        components.forEachIndexed { componentIndex, rawComponent ->
                            val component = rawComponent.asStringMap("components[$componentIndex]")
                            val id = component.requiredString("id", "messages[$index].updateComponents.components[$componentIndex].id")
                            require(id.length <= 128 && ids.add(id)) { "Component IDs must be unique and at most 128 characters" }
                            rootDefined = rootDefined || id == "root"
                            require(
                                component.requiredString(
                                    "component",
                                    "messages[$index].updateComponents.components[$componentIndex].component",
                                ) in SAFE_COMPONENTS,
                            ) {
                                "Component '${component["component"]}' is not allowed"
                            }
                            validateComponent(component, componentIndex)
                            validateJson(component, depth = 0)
                        }
                    }

                    "updateDataModel" -> {
                        require(payload.keys.all { it in setOf("surfaceId", "path", "value") }) {
                            "messages[$index].updateDataModel contains unsupported fields " +
                                "${payload.keys - setOf("surfaceId", "path", "value")}; use path and value, not data"
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
            val type = component["component"] as String
            val allowedFields = COMMON_COMPONENT_FIELDS + COMPONENT_FIELDS.getValue(type)
            val unsupportedFields = component.keys - allowedFields
            require(unsupportedFields.isEmpty()) {
                "Validation failed for component '$type' (components[$index]): " +
                    "unrecognized fields $unsupportedFields"
            }
            val missingFields = REQUIRED_COMPONENT_FIELDS[type].orEmpty() - component.keys
            require(missingFields.isEmpty()) {
                "Validation failed for component '$type' (components[$index]): " +
                    "missing required fields $missingFields"
            }
            require(component.keys.none { it in setOf("html", "script", "src", "url", "svgPath") }) {
                "components[$index] contains unsafe content"
            }

            if (type in setOf("Row", "Column", "List")) {
                validateChildList(component["children"], "components[$index].children")
            }

            val action = component["action"] ?: return
            val actionMap = action.asStringMap("components[$index].action")
            require(actionMap.keys == setOf("event")) { "Only server event actions are allowed" }
            val event = actionMap["event"].asStringMap("components[$index].action.event")
            require(event.keys.all { it in setOf("name", "context") }) { "Event actions contain unsupported fields" }
            require(event.requiredString("name", "components[$index].action.event.name").length <= 128) {
                "components[$index].action.event.name is too long"
            }
            event["context"]?.let { validateJson(it, depth = 0) }
        }

        private fun validateChildList(value: Any?, path: String) {
            when (value) {
                is List<*> -> require(value.all { it is String && it.isNotBlank() }) {
                    "$path must contain component ID strings"
                }
                is Map<*, *> -> {
                    val template = value.asStringMap(path)
                    require(template.keys == setOf("componentId", "path")) {
                        "$path dynamic form must contain exactly componentId and path"
                    }
                    template.requiredString("componentId", "$path.componentId")
                    require(template.requiredString("path", "$path.path").startsWith("/")) {
                        "$path.path must be an absolute JSON pointer"
                    }
                }
                else -> throw IllegalArgumentException(
                    "$path must be an array of component IDs or {componentId, path}",
                )
            }
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

        private fun Map<String, Any?>.requiredString(name: String, path: String): String {
            val value = this[name] as? String
            require(!value.isNullOrBlank()) { "$path must be a non-empty string" }
            return value
        }
    }
}
