package io.github.jeshiomurmu.opencaller.server.admin.web

import io.github.jeshiomurmu.opencaller.server.business.ClaimReview
import io.github.jeshiomurmu.opencaller.server.business.ClaimStatus
import io.github.jeshiomurmu.opencaller.server.config.AdminWebConfig
import io.github.jeshiomurmu.opencaller.server.recording.RecordingCountryPolicy
import io.github.jeshiomurmu.opencaller.server.registry.ImportRunLog
import io.github.jeshiomurmu.opencaller.server.registry.Importer
import io.github.jeshiomurmu.opencaller.server.registry.OtpSourceCuration
import io.github.jeshiomurmu.opencaller.server.registry.SenderIdStore
import io.github.jeshiomurmu.opencaller.server.scamfeed.ScamFeedService
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.withCharset
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.basicAuthenticationCredentials
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Sender-ID imports: where rows go, the run log and the configured importers. */
class ImportTools(
    val store: SenderIdStore,
    val runs: ImportRunLog,
    val importers: () -> List<Importer<*>>,
)

/** Everything the admin web works with (one value per page group, hence the parameter count). */
@Suppress("LongParameterList")
class AdminWebServices(
    val claims: ClaimReview,
    val curation: OtpSourceCuration,
    val imports: ImportTools,
    val audit: AdminAuditLog,
    val clock: Clock,
    val scamCards: ScamFeedService,
    val recordingCountries: RecordingCountryPolicy,
)

/** The signed-in admin of one request: who, from where, and the CSRF token their forms carry. */
class AdminContext(
    val call: ApplicationCall,
    val admin: String,
    val ip: String,
    val csrf: String,
)

/**
 * Who may use the admin web (docs/rules.md §7: separate port, behind auth and an IP allowlist): the request must
 * come from [AdminWebConfig.allowedNetworks] (else 403, before any password is asked for), carry the admin's HTTP
 * Basic credentials (compared in constant time), and every form post must carry the CSRF token of the page it
 * came from (an HMAC of the admin's name with a key made at start, so pages expire on restart).
 */
class AdminGuard(
    private val config: AdminWebConfig,
    private val remoteAddress: (ApplicationCall) -> String,
) {
    private val user = config.user.toByteArray(Charsets.UTF_8)
    private val password = config.password.toByteArray(Charsets.UTF_8)
    private val csrfKey = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)

    /** The admin of [call], or `null` after answering it with 403 or 401. */
    suspend fun admit(call: ApplicationCall): AdminContext? {
        securityHeaders(call)
        val ip = remoteAddress(call)
        val allowed = config.allowedNetworks.any { it.contains(ip) }
        val credentials = call.request.basicAuthenticationCredentials()
        val userOk = MessageDigest.isEqual(credentials?.name.orEmpty().toByteArray(Charsets.UTF_8), user)
        val passwordOk = MessageDigest.isEqual(credentials?.password.orEmpty().toByteArray(Charsets.UTF_8), password)
        when {
            !allowed -> {
                call.respondText("Forbidden", ContentType.Text.Plain, HttpStatusCode.Forbidden)
            }

            !(userOk && passwordOk) -> {
                call.response.header(HttpHeaders.WWWAuthenticate, "Basic realm=\"OpenCaller admin\", charset=\"UTF-8\"")
                call.respondText("Sign in", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
            }
        }
        return if (allowed && userOk && passwordOk) AdminContext(call, config.user, ip, csrfToken(config.user)) else null
    }

    /** `true` if [token] is [admin]'s CSRF token. */
    fun csrfValid(
        admin: String,
        token: String?,
    ): Boolean = token != null && MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), csrfToken(admin).toByteArray(Charsets.UTF_8))

    private fun csrfToken(admin: String): String {
        val mac = Mac.getInstance(HMAC)
        mac.init(SecretKeySpec(csrfKey, HMAC))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(admin.toByteArray(Charsets.UTF_8)))
    }

    private fun securityHeaders(call: ApplicationCall) {
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("X-Frame-Options", "DENY")
        call.response.header("Referrer-Policy", "no-referrer")
        call.response.header(HttpHeaders.CacheControl, "no-store")
    }

    private companion object {
        const val KEY_BYTES = 32
        const val HMAC = "HmacSHA256"
    }
}

/**
 * The admin web (docs/architecture.md: "Admin web (review queues)"): business-claim review, verified businesses,
 * OTP-source curation, sender-ID imports, the scam feed, recording countries and the audit log. Served on its own
 * port ([AdminWebConfig.port]) by `main`; [remoteAddress] is the socket peer (no forwarded headers are trusted).
 */
fun Application.adminWebModule(
    config: AdminWebConfig,
    services: AdminWebServices,
    remoteAddress: (ApplicationCall) -> String = { it.request.local.remoteAddress },
) {
    val guard = AdminGuard(config, remoteAddress)
    routing {
        adminGet("/", guard) { overview(services) }
        adminGet("/audit", guard) { respondHtml(auditPage(services)) }
        claimPages(guard, services)
        registryPages(guard, services)
        scamCardPages(guard, services)
        recordingCountryPages(guard, services)
    }
}

/** A GET page behind the [guard]. */
fun Route.adminGet(
    path: String,
    guard: AdminGuard,
    handler: suspend AdminContext.() -> Unit,
) {
    get(path) { guard.admit(call)?.handler() }
}

/** A form POST behind the [guard] with a valid CSRF token; [handler] gets the form fields. */
fun Route.adminPost(
    path: String,
    guard: AdminGuard,
    handler: suspend AdminContext.(Parameters) -> Unit,
) {
    post(path) {
        val context = guard.admit(call) ?: return@post
        val form = call.receiveParameters()
        if (!guard.csrfValid(context.admin, form["csrf"])) {
            call.respondText(
                "This form has expired. Go back, reload the page and try again.",
                ContentType.Text.Plain,
                HttpStatusCode.Forbidden,
            )
            return@post
        }
        context.handler(form)
    }
}

/** Responds with an HTML page (no scripts allowed, forms only to this site, never framed). */
suspend fun AdminContext.respondHtml(
    html: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    call.response.header("Content-Security-Policy", PAGE_POLICY)
    call.respondText(html, ContentType.Text.Html.withCharset(Charsets.UTF_8), status)
}

private const val PAGE_POLICY =
    "default-src 'none'; style-src 'unsafe-inline'; img-src 'self'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"

/** Responds with a plain error page. */
suspend fun AdminContext.respondNotFound(what: String) =
    respondHtml(page("Not found", admin, "<p>${esc(what)}</p>"), HttpStatusCode.NotFound)

/** The fixed notice for the page's `?done=` code (set after a form post); unknown codes show nothing. */
fun AdminContext.notice(notices: Map<String, String>): String? = call.request.queryParameters["done"]?.let(notices::get)

private suspend fun AdminContext.overview(services: AdminWebServices) {
    val waiting = services.claims.claims(ClaimStatus.SUBMITTED).size
    val body =
        "<p>${link("/claims", "$waiting business claim(s) waiting for review")}.</p>" +
            "<p>Every action here is recorded in the ${link("/audit", "audit log")} with your name and address.</p>"
    respondHtml(page("Overview", admin, body))
}

private suspend fun AdminContext.auditPage(services: AdminWebServices): String {
    val rows =
        services.audit.recent().map { entry ->
            listOf(esc(time(entry.at)), esc(entry.admin), esc(entry.ip), esc(entry.action), esc(entry.target), esc(entry.detail))
        }
    return page("Audit log", admin, table(listOf("When", "Admin", "From", "Action", "Target", "Detail"), rows, "No actions yet."))
}
