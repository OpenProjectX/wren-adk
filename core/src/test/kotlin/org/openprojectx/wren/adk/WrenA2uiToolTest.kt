package org.openprojectx.wren.adk

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WrenA2uiToolTest {

    private val validMessages: List<Map<String, Any>> = listOf(
        mapOf(
            "version" to "v0.9",
            "createSurface" to mapOf(
                "surfaceId" to "orders-summary",
                "catalogId" to WrenA2uiTool.BASIC_CATALOG,
            ),
        ),
        mapOf(
            "version" to "v0.9",
            "updateComponents" to mapOf(
                "surfaceId" to "orders-summary",
                "components" to listOf(
                    mapOf("id" to "root", "component" to "Card", "child" to "headline"),
                    mapOf("id" to "headline", "component" to "Text", "text" to "212 orders", "variant" to "h2"),
                ),
            ),
        ),
    )

    @Test
    fun `the declaration exposes render_a2ui and requires messages`() {
        val declaration = WrenA2uiTool().declaration().orElseThrow()
        assertEquals("render_a2ui", declaration.name().orElseThrow())
        assertEquals(listOf("messages"), declaration.parameters().orElseThrow().required().orElseThrow())
    }

    @Test
    fun `a valid surface is echoed into the function response`() {
        val result = WrenA2uiTool().render(mapOf("messages" to validMessages))
        assertEquals(validMessages, result["messages"])
    }

    @Test
    fun `common model version aliases are normalized for the renderer`() {
        val messages = validMessages.map { it + ("version" to "0.9") }

        val result = WrenA2uiTool().render(mapOf("messages" to messages))
        val rendered = result["messages"] as List<*>

        rendered.forEach { message ->
            assertEquals("v0.9", (message as Map<*, *>)["version"])
        }
    }

    @Test
    fun `invalid model output becomes a tool error instead of escaping`() {
        val messages = validMessages.map { it + ("version" to "version-nine") }

        val result = WrenA2uiTool().renderResult(mapOf("messages" to messages))

        assertTrue(result["error"].toString().contains("use v0.9 or v0.9.1"))
    }

    @Test
    fun `unknown catalog components are rejected`() {
        val messages = validMessages.map { message ->
            val update = message["updateComponents"] as? Map<*, *> ?: return@map message
            message + ("updateComponents" to (update + ("components" to listOf(
                mapOf("id" to "root", "component" to "DataTable"),
            ))))
        }

        val error = assertFailsWith<IllegalArgumentException> {
            WrenA2uiTool.validateBatch(messages)
        }
        assertTrue("not allowed" in error.message.orEmpty())
    }

    @Test
    fun `client function actions are rejected`() {
        val messages: List<Map<String, Any>> = listOf(
            validMessages.first(),
            mapOf(
                "version" to "v0.9",
                "updateComponents" to mapOf(
                    "surfaceId" to "orders-summary",
                    "components" to listOf(
                        mapOf("id" to "root", "component" to "Column", "children" to listOf("button", "label")),
                        mapOf(
                            "id" to "button",
                            "component" to "Button",
                            "child" to "label",
                            "action" to mapOf("functionCall" to mapOf("call" to "openUrl")),
                        ),
                        mapOf("id" to "label", "component" to "Text", "text" to "Open"),
                    ),
                ),
            ),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            WrenA2uiTool.validateBatch(messages)
        }
        assertTrue("server event" in error.message.orEmpty())
    }

    @Test
    fun `mixed surface batches are rejected`() {
        val messages = validMessages.mapIndexed { index, message ->
            if (index == 0) message else {
                val update = message["updateComponents"] as Map<*, *>
                message + ("updateComponents" to (update + ("surfaceId" to "another-surface")))
            }
        }

        assertFailsWith<IllegalArgumentException> {
            WrenA2uiTool.validateBatch(messages)
        }
    }
}
