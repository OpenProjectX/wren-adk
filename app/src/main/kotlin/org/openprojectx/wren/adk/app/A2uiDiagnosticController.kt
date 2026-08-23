package org.openprojectx.wren.adk.app

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** Receives catalog-validation failures that can only be detected by the browser renderer. */
@RestController
class A2uiDiagnosticController {

    @PostMapping("/ui/diagnostics/a2ui")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun report(@RequestBody diagnostic: A2uiDiagnostic) {
        val message = diagnostic.message.sanitize(MAX_MESSAGE_LENGTH)
        if (message.isBlank()) return

        val surface = diagnostic.surfaceId?.sanitize(MAX_SURFACE_ID_LENGTH)?.ifBlank { "unknown" } ?: "unknown"
        logger.warn("A2UI client validation failed [surface={}]: {}", surface, message)
    }

    private fun String.sanitize(maxLength: Int): String =
        replace(Regex("[\\r\\n\\t]+"), " ").take(maxLength)

    data class A2uiDiagnostic(
        val message: String = "",
        val surfaceId: String? = null,
    )

    companion object {
        private const val MAX_MESSAGE_LENGTH = 4_096
        private const val MAX_SURFACE_ID_LENGTH = 128
        private val logger = LoggerFactory.getLogger(A2uiDiagnosticController::class.java)
    }
}
