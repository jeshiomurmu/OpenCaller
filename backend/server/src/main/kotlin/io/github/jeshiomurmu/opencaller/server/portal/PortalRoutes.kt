package io.github.jeshiomurmu.opencaller.server.portal

import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.security.MessageDigest

/**
 * The business dashboard at `/portal` (docs/prd.md F-BZ-04): sign in with a code sent to a number of an approved
 * business, then read the counts and change website, hours and call reasons. The session is an HttpOnly,
 * SameSite=Strict cookie limited to `/portal` ([secureCookie]: also `Secure`, outside development).
 */
fun Route.portalRoutes(
    portal: PortalService,
    secureCookie: Boolean,
) {
    signInRoutes(portal, secureCookie)
    profileRoutes(portal)
    post("/portal/logout") {
        val token = call.request.cookies[COOKIE]
        val session = portal.session(token)
        if (session != null && !csrfValid(session, call.receiveParameters())) return@post call.respondExpired()
        portal.logout(token)
        call.response.cookies.append(
            Cookie(
                COOKIE,
                "",
                maxAge = 0,
                path = "/portal",
                secure = secureCookie,
                httpOnly = true,
                extensions =
                    mapOf("SameSite" to "Strict"),
            ),
        )
        call.seeOther("/portal")
    }
}

private fun Route.signInRoutes(
    portal: PortalService,
    secureCookie: Boolean,
) {
    get("/portal") {
        val session = portal.session(call.request.cookies[COOKIE])
        if (session == null) {
            call.respondPage(PortalPages.numberForm())
        } else {
            call.respondPage(PortalPages.dashboard(portal.dashboard(session), session.csrf))
        }
    }
    post("/portal/login") {
        val number = call.receiveParameters()["e164"].orEmpty().take(MAX_FIELD)
        try {
            val parsed = portal.startLogin(number, call.request.origin.remoteAddress)
            call.respondPage(PortalPages.codeForm(parsed.value, portal.codeValidMinutes))
        } catch (problem: ApiProblem) {
            problem.retryAfterSeconds?.let { call.response.header(HttpHeaders.RetryAfter, it.toString()) }
            call.respondPage(PortalPages.numberForm(problem.forPeople()), problem.status)
        }
    }
    post("/portal/verify") {
        val form = call.receiveParameters()
        val number = form["e164"].orEmpty().take(MAX_FIELD)
        try {
            val login = portal.verify(number, form["code"].orEmpty().trim().take(MAX_FIELD))
            call.response.cookies.append(
                Cookie(
                    name = COOKIE,
                    value = login.token,
                    maxAge = (PortalService.SESSION_HOURS * SECONDS_PER_HOUR).toInt(),
                    path = "/portal",
                    secure = secureCookie,
                    httpOnly = true,
                    extensions = mapOf("SameSite" to "Strict"),
                ),
            )
            call.seeOther("/portal")
        } catch (problem: ApiProblem) {
            val page =
                if (problem.code == "invalid_phone_number") {
                    PortalPages.numberForm(problem.forPeople())
                } else {
                    PortalPages.codeForm(number, portal.codeValidMinutes, problem.forPeople())
                }
            call.respondPage(page, problem.status)
        }
    }
}

private fun Route.profileRoutes(portal: PortalService) {
    get("/portal/profile") {
        val session = portal.session(call.request.cookies[COOKIE]) ?: return@get call.respondRedirect("/portal")
        val notice =
            if (call.request.queryParameters["done"] ==
                "saved"
            ) {
                "Saved. Apps show the change after their next daily update."
            } else {
                null
            }
        call.respondPage(PortalPages.profileForm(portal.dashboard(session), session.csrf, notice))
    }
    post("/portal/profile") {
        val session = portal.session(call.request.cookies[COOKIE]) ?: return@post call.seeOther("/portal")
        val form = call.receiveParameters()
        if (!csrfValid(session, form)) return@post call.respondExpired()
        val reasons =
            form
                .names()
                .filter { it.startsWith(REASON_PREFIX) }
                .associate { it.removePrefix(REASON_PREFIX) to form[it].orEmpty().trim().take(MAX_TEXT) }
        val update =
            PortalProfileForm(
                website = form["website"].orEmpty().trim().take(MAX_TEXT),
                hours = form["hours"].orEmpty().trim().take(MAX_TEXT),
                callReasons = reasons,
            )
        val problem = portal.updateProfile(session, update)
        if (problem == null) {
            call.seeOther("/portal/profile?done=saved")
        } else {
            call.respondPage(PortalPages.profileForm(portal.dashboard(session), session.csrf, error = problem), HttpStatusCode.BadRequest)
        }
    }
}

private fun csrfValid(
    session: PortalSession,
    form: Parameters,
): Boolean {
    val sent = form["csrf"] ?: return false
    return MessageDigest.isEqual(sent.toByteArray(Charsets.UTF_8), session.csrf.toByteArray(Charsets.UTF_8))
}

// After a form POST: "303 See Other" makes every client fetch the next page with GET.
private suspend fun ApplicationCall.seeOther(path: String) {
    response.header(HttpHeaders.Location, path)
    respond(HttpStatusCode.SeeOther)
}

private suspend fun ApplicationCall.respondExpired() =
    respondText("This form has expired. Go back, reload the page and try again.", ContentType.Text.Plain, HttpStatusCode.Forbidden)

private fun ApiProblem.forPeople() = listOfNotNull(title, detail).joinToString(". ")

private suspend fun ApplicationCall.respondPage(
    html: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    // No framing (clickjacking), no scripts, forms post only back to this server.
    response.header(
        "Content-Security-Policy",
        "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
    )
    response.header("X-Frame-Options", "DENY")
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Referrer-Policy", "no-referrer")
    response.header(HttpHeaders.CacheControl, "no-store")
    respondText(html, ContentType.Text.Html.withParameter("charset", "utf-8"), status)
}

/** The session cookie's name. */
internal const val COOKIE = "oc_portal"
private const val REASON_PREFIX = "reason:"
private const val MAX_FIELD = 64
private const val MAX_TEXT = 400
private const val SECONDS_PER_HOUR = 3600
