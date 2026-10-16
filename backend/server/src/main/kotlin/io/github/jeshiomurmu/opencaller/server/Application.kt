package io.github.jeshiomurmu.opencaller.server

import io.github.jeshiomurmu.opencaller.server.health.configureHealthRoutes
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation

private const val DEFAULT_PORT = 8080
private const val DEFAULT_VERSION = "dev"

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: DEFAULT_PORT
    val version = System.getenv("APP_VERSION")?.takeIf { it.isNotBlank() } ?: DEFAULT_VERSION
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module(version = version)
    }.start(wait = true)
}

/** Installs the server plugins and routes. [version] is reported by `/health` (git SHA or "dev"). */
fun Application.module(version: String) {
    install(ContentNegotiation) {
        json()
    }
    configureHealthRoutes(version)
}
