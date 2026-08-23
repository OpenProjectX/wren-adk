package org.openprojectx.wren.adk.app

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication


@SpringBootApplication
class WrenAdkApplication

fun main(args: Array<String>) {

    runApplication<WrenAdkApplication>(*args)
}