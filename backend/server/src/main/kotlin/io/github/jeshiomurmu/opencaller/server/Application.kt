package io.github.jeshiomurmu.opencaller.server

import com.auth0.jwt.interfaces.JWTVerifier
import io.github.jeshiomurmu.opencaller.server.auth.TokenService
import io.github.jeshiomurmu.opencaller.server.auth.authRoutes
import io.github.jeshiomurmu.opencaller.server.config.ConfigException
import io.github.jeshiomurmu.opencaller.server.config.loadConfig
import io.github.jeshiomurmu.opencaller.server.health.configureHealthRoutes
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.installBodyLimit
import io.github.jeshiomurmu.opencaller.server.http.installMetrics
import io.github.jeshiomurmu.opencaller.server.http.installProblemResponses
import io.github.jeshiomurmu.opencaller.server.http.installRequestIds
import io.github.jeshiomurmu.opencaller.server.http.metricsModule
import io.github.jeshiomurmu.opencaller.server.http.respondProblem
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

/** Name of the access-token authentication for `authenticate(ACCESS_AUTH) { … }` routes. */
const val ACCESS_AUTH = "access"

private val log = LoggerFactory.getLogger("io.github.jeshiomurmu.opencaller.server.Application")

fun main() {
    val config =
        try {
            loadConfig(System.getenv())
        } catch (e: ConfigException) {
            // Fail fast with every missing or invalid variable listed (logged without the values).
            log.error(e.message)
            exitProcess(1)
        }
    val deps = ServerDependencies.create(config, Dispatchers.IO)
    val metrics = embeddedServer(Netty, port = config.http.metricsPort, host = "0.0.0.0") { metricsModule(deps.meterRegistry) }
    val api = embeddedServer(Netty, port = config.http.port, host = "0.0.0.0") { module(deps) }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            api.stop()
            metrics.stop()
            deps.close()
        },
    )
    log.info(
        "OpenCaller server {} ({}) on port {}, metrics on {}",
        config.version,
        config.environment,
        config.http.port,
        config.http.metricsPort,
    )
    metrics.start(wait = false)
    api.start(wait = true)
}

/** The public API: plugins, error mapping and routes. */
fun Application.module(deps: ServerDependencies) {
    installRequestIds()
    installMetrics(deps.meterRegistry)
    installProblemResponses()
    installBodyLimit()
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
    installAccessAuth(deps.tokens.verifier)
    configureHealthRoutes(deps.config.version)
    routing {
        authRoutes(deps.auth)
    }
}

/** Bearer access tokens (JWT); a missing or invalid token gets 401 problem+json. */
private fun Application.installAccessAuth(verifier: JWTVerifier) {
    install(Authentication) {
        jwt(ACCESS_AUTH) {
            verifier(verifier)
            validate { credential ->
                val payload = credential.payload
                if (payload.subject != null &&
                    payload.getClaim(TokenService.DEVICE_CLAIM).asString() != null
                ) {
                    JWTPrincipal(payload)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                call.respondProblem(ApiProblem(HttpStatusCode.Unauthorized, "unauthorized", "A valid access token is required"))
            }
        }
    }
}
