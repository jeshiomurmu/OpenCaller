package io.github.jeshiomurmu.opencaller.server.http

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders

/** The header the app sends with its `versionCode` on every request. */
const val APP_VERSION_HEADER = "X-OpenCaller-Version"

/**
 * Refuses API requests (`/v1/…`) from app versions older than [minVersionCode] with `426 upgrade_required`, so a
 * server can stop serving versions with a known problem (docs/security.md SEC-6). Requests without the header (the
 * business portal, scripts) or with an unreadable one are served; 0 turns the check off.
 */
fun Application.installMinimumAppVersion(minVersionCode: Int) {
    if (minVersionCode <= 0) return
    install(
        createApplicationPlugin("MinimumAppVersion") {
            onCall { call ->
                if (!call.request.local.uri
                        .startsWith("/v1/")
                ) {
                    return@onCall
                }
                val version =
                    call.request.headers[APP_VERSION_HEADER]
                        ?.trim()
                        ?.toIntOrNull() ?: return@onCall
                if (version < minVersionCode) call.respondProblem(upgradeRequired())
            }
        },
    )
}

/** The 426 an outdated app gets; its [ApiProblem.code] is what the app switches on. */
fun upgradeRequired() =
    ApiProblem(
        HttpStatusCode.UpgradeRequired,
        "upgrade_required",
        "Update OpenCaller",
        "This version of OpenCaller is too old for this server. Install the latest version.",
    )

/**
 * Behind the deploy guide's Caddy (infra/deploy), `request.origin` takes the client's address from the last
 * `X-Forwarded-For` entry, the one Caddy writes (it doesn't pass on a client's own header unless told to trust it),
 * so the per-IP limits count clients, not the proxy. Only for a server whose port is reachable through Caddy alone.
 */
fun Application.installForwardedHeaders(trusted: Boolean) {
    if (!trusted) return
    install(XForwardedHeaders) { useLastProxy() }
}
