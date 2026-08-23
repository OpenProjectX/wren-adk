package org.openprojectx.wren.adk.app

import com.google.adk.agents.LlmAgent
import com.google.adk.web.AdkWebServer
import com.google.adk.web.AgentLoader
import com.google.adk.web.AgentStaticLoader
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean

/**
 * Hosts Google's ADK Dev UI and registers the Wren-backed agent with it.
 *
 * ADK's web components live outside this application's package, so they are
 * included explicitly in component scanning. The agent itself remains a Spring
 * bean supplied by the starter's auto-configuration.
 */
@SpringBootApplication(scanBasePackageClasses = [WrenAdkApplication::class, AdkWebServer::class])
class WrenAdkApplication {

    @Bean(name = ["agentLoader"])
    fun wrenAgentLoader(agent: LlmAgent): AgentLoader = AgentStaticLoader(agent)
}

fun main(args: Array<String>) {
    // Match AdkWebServer.main: live audio/video payloads can exceed Tomcat's
    // small default WebSocket message buffer.
    System.setProperty(
        "org.apache.tomcat.websocket.DEFAULT_BUFFER_SIZE",
        (10 * 1024 * 1024).toString(),
    )
    runApplication<WrenAdkApplication>(*args)
}
