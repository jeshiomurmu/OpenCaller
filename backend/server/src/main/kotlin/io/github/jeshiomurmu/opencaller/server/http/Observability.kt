package io.github.jeshiomurmu.opencaller.server.http

import io.github.jeshiomurmu.opencaller.server.logging.JsonLogEncoder
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.metrics.micrometer.MicrometerMetrics
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.slf4j.event.Level
import java.util.UUID

private const val MAX_REQUEST_ID_LENGTH = 64

/** Largest request body the API accepts (the biggest is a batch of 50 reports with comments). */
const val MAX_BODY_BYTES = 64 * 1024L

/**
 * Request IDs: a client's `X-Request-Id` is kept if it is short and plain (letters, digits, `-`, `_`), otherwise
 * a UUID is generated; it is returned in `X-Request-Id` and put in every log line of the call (`requestId`).
 * One access log line per request: status, method, path, duration.
 */
fun Application.installRequestIds() {
    install(CallId) {
        retrieve { call -> call.request.headers[HttpHeaders.XRequestId]?.takeIf(::isPlainRequestId) }
        generate { UUID.randomUUID().toString() }
        // Replaces Ktor's default dictionary (which has no `_`) with the rule above.
        verify(::isPlainRequestId)
        replyToHeader(HttpHeaders.XRequestId)
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc(JsonLogEncoder.REQUEST_ID)
        format { call -> "${call.response.status()?.value} ${call.request.httpMethod.value} ${call.request.path()}" }
    }
}

private fun isPlainRequestId(id: String) =
    id.length in 1..MAX_REQUEST_ID_LENGTH && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }

/** Rejects bodies over [MAX_BODY_BYTES] (by `Content-Length`) with 413 problem+json before routing. */
fun Application.installBodyLimit() {
    intercept(ApplicationCallPipeline.Plugins) {
        val length = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (length != null && length > MAX_BODY_BYTES) {
            call.respondProblem(ApiProblem(HttpStatusCode.PayloadTooLarge, "payload_too_large", "The request body is too large"))
            finish()
        }
    }
}

/** Request metrics for the API (`ktor.http.server.requests`, with a histogram for latency percentiles). */
fun Application.installMetrics(registry: PrometheusMeterRegistry) {
    install(MicrometerMetrics) {
        this.registry = registry
        distributionStatisticConfig = DistributionStatisticConfig.Builder().percentilesHistogram(true).build()
    }
}

/** The internal metrics server's only route: `GET /metrics` in the Prometheus text format. */
fun Application.metricsModule(registry: PrometheusMeterRegistry) {
    routing {
        get("/metrics") {
            call.respondText(registry.scrape(), ContentType.parse("text/plain; version=0.0.4; charset=utf-8"))
        }
    }
}
