package io.github.jeshiomurmu.opencaller.server.http

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.application.plugin
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.CannotTransformContentToTypeException
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** RFC 9457 problem details as the API returns them (docs/architecture.md §8): `{type, title, status, detail, code}`. */
@Serializable
data class ProblemDetails(
    val type: String,
    val title: String,
    val status: Int,
    val detail: String? = null,
    val code: String,
)

/**
 * An error a route reports to the client. [code] is the stable machine-readable value apps switch on; [title]
 * and [detail] are for people. Never put stack traces, SQL or secrets in [detail].
 */
class ApiProblem(
    val status: HttpStatusCode,
    val code: String,
    val title: String,
    val detail: String? = null,
    val retryAfterSeconds: Long? = null,
    cause: Throwable? = null,
) : RuntimeException("$code: $title", cause)

/** `application/problem+json`. */
val ProblemJson: ContentType = ContentType("application", "problem+json")

private val problemJson = Json { explicitNulls = false }
private val log = LoggerFactory.getLogger("io.github.jeshiomurmu.opencaller.server.http.Problems")

/** Responds with [problem] as problem+json (with `Retry-After` when set). */
suspend fun ApplicationCall.respondProblem(problem: ApiProblem) {
    problem.retryAfterSeconds?.let { response.header(HttpHeaders.RetryAfter, it.toString()) }
    val body =
        ProblemDetails(
            type = "urn:opencaller:problem:${problem.code}",
            title = problem.title,
            status = problem.status.value,
            detail = problem.detail,
            code = problem.code,
        )
    respondText(problemJson.encodeToString(ProblemDetails.serializer(), body), ProblemJson, problem.status)
}

/**
 * Maps every failure to problem+json: [ApiProblem] as given, unreadable requests to 400 or 415, unknown routes
 * and methods to 404/405, anything unexpected to a generic 500 that is logged with the request ID but never
 * shows its details to the client.
 */
fun Application.installProblemResponses() {
    install(StatusPages) {
        exception<ApiProblem> { call, problem -> call.respondProblem(problem) }
        // A body in a type the API can't read (e.g. text/plain) surfaces as either of these two.
        exception<UnsupportedMediaTypeException> { call, _ -> call.respondProblem(unsupportedMediaType()) }
        exception<CannotTransformContentToTypeException> { call, _ -> call.respondProblem(unsupportedMediaType()) }
        exception<BadRequestException> { call, _ ->
            call.respondProblem(
                ApiProblem(
                    HttpStatusCode.BadRequest,
                    "invalid_request",
                    "The request body is not valid",
                    "Send JSON with the documented fields.",
                ),
            )
        }
        exception<Throwable> { call, cause ->
            // The request ID is in the log line's `requestId` field (MDC).
            log.error("Unhandled error on {} {}", call.request.httpMethod.value, call.request.path(), cause)
            call.respondProblem(ApiProblem(HttpStatusCode.InternalServerError, "internal_error", "Something went wrong on our side"))
        }
    }
    installUnmatchedRouteResponses()
}

/**
 * Unmatched requests get 404 `not_found`, or 405 `method_not_allowed` with `Allow` when the path exists for other
 * methods. A catch-all route (`{...}`, which every real route outranks) answers them once; StatusPages `status()`
 * handlers would send a second response after routing's own empty 404, which counts the call twice in the request
 * metrics and drives the active-requests gauge negative.
 */
private fun Application.installUnmatchedRouteResponses() {
    val routes by lazy { RouteTable(plugin(RoutingRoot).getAllRoutes()) }
    routing {
        route("{...}") {
            handle {
                val allowed = routes.methodsFor(call.request.path())
                if (allowed.isEmpty()) {
                    call.respondProblem(ApiProblem(HttpStatusCode.NotFound, "not_found", "No such endpoint"))
                } else {
                    call.response.header(HttpHeaders.Allow, allowed.joinToString(", "))
                    call.respondProblem(
                        ApiProblem(HttpStatusCode.MethodNotAllowed, "method_not_allowed", "This method is not allowed here"),
                    )
                }
            }
        }
    }
}

/** Registered paths and their methods; `{name}` segments match any single segment. */
private class RouteTable(
    routes: List<RoutingNode>,
) {
    private val methodsByPattern: Map<List<String>, List<String>> =
        routes
            .mapNotNull { route -> (route.selector as? HttpMethodRouteSelector)?.let { routePath(route) to it.method.value } }
            .groupBy({ segments(it.first) }, { it.second })

    fun methodsFor(path: String): List<String> {
        val requested = segments(path)
        return methodsByPattern.entries
            .filter { (pattern, _) ->
                pattern.size == requested.size && pattern.zip(requested).all { (p, r) -> p == r || p.startsWith("{") }
            }.flatMap { it.value }
            .distinct()
            .sorted()
    }

    private fun segments(path: String) = path.split('/').filter { it.isNotEmpty() }
}

/**
 * The path of [route] as registered, e.g. `/v1/reports`. Ktor prints a route as its path plus selector segments
 * in brackets, e.g. `/v1/reports/(authenticate "access")/(method:POST)`; those segments are dropped.
 */
fun routePath(route: RoutingNode): String =
    route
        .toString()
        .split('/')
        .filterNot { it.startsWith("(") }
        .joinToString("/")
        .ifEmpty { "/" }

private fun unsupportedMediaType() = ApiProblem(HttpStatusCode.UnsupportedMediaType, "unsupported_media_type", "Send application/json")
