package org.openprojectx.wren.adk

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Skill loading must degrade quietly: a missing CLI is the normal case when
 * Wren runs as a remote service, and must not stop the agent from starting.
 */
class WrenSkillsTest {

    @Test
    fun `a missing CLI yields null rather than throwing`() {
        assertNull(WrenSkills.load(listOf("usage"), command = "wren-does-not-exist"))
    }

    @Test
    fun `no skills requested yields null`() {
        assertNull(WrenSkills.load(emptyList()))
    }

    @Test
    fun `front matter is stripped`() {
        val raw = """
            ---
            name: usage
            description: "something"
            ---

            # Wren Engine CLI — Agent Workflow Guide
            body
        """.trimIndent()
        val stripped = WrenSkills.stripFrontMatter(raw)
        assertTrue(stripped.startsWith("# Wren Engine CLI"), "got: ${stripped.take(60)}")
        assertTrue("name: usage" !in stripped, "front matter should be gone")
    }

    @Test
    fun `text without front matter is left alone`() {
        assertEquals("# Guide\nbody", WrenSkills.stripFrontMatter("# Guide\nbody"))
    }

    @Test
    fun `loading the real guide prepends the MCP translation`() {
        // Only meaningful where the CLI is installed; skipped otherwise.
        val loaded = WrenSkills.load(listOf("usage")) ?: return
        assertTrue("Reading the guidance below" in loaded, "translation header missing")
        assertTrue("`run_sql`" in loaded, "tool mapping missing")
        assertTrue("Workflow 1" in loaded, "the official guide body missing")
    }
}
