package io.github.jeshiomurmu.opencaller.server.admin.web

import io.github.jeshiomurmu.opencaller.server.recording.RecordingCountry
import io.github.jeshiomurmu.opencaller.server.recording.RecordingCountryPolicy
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route

private val COUNTRY_NOTICES =
    mapOf(
        "on" to "Recording is now offered in that country (new sessions only).",
        "off" to "Recording is no longer offered in that country (running calls finish).",
        "invalid" to "Enter a two-letter country code such as NP.",
        "unconfirmed" to "Tick the confirmation: a country is switched on only after the operator's legal review approved it.",
    )

/**
 * The per-country legal switch of the recording line (ADR 0009): which countries may use it, from
 * `RECORDING_COUNTRIES` and the admin web's overrides. Switching a country on requires the admin to confirm that
 * the operator's legal review approved it; every change is in the audit log.
 */
fun Route.recordingCountryPages(
    guard: AdminGuard,
    services: AdminWebServices,
) {
    val policy = services.recordingCountries
    adminGet("/recording-countries", guard) {
        respondHtml(page("Recording countries", admin, body(policy.all(), csrf), notice(COUNTRY_NOTICES)))
    }
    adminPost("/recording-countries", guard) { form ->
        val iso = form["country"].orEmpty().trim().uppercase()
        val enable = form["enabled"] == "true"
        val done =
            when {
                !RecordingCountryPolicy.COUNTRY.matches(iso) -> {
                    "invalid"
                }

                enable && form["approved"] != "yes" -> {
                    "unconfirmed"
                }

                else -> {
                    policy.set(iso, enable)
                    services.audit.record(admin, ip, if (enable) "recording-country.on" else "recording-country.off", iso, "")
                    if (enable) "on" else "off"
                }
            }
        call.respondRedirect("/recording-countries?done=$done")
    }
}

private fun body(
    countries: List<RecordingCountry>,
    csrf: String,
): String {
    val rows =
        countries.map { country ->
            val source =
                when {
                    country.fromAdmin -> "Admin web"
                    country.inEnvironment -> "RECORDING_COUNTRIES"
                    else -> "–"
                }
            val action =
                if (country.approved) {
                    form("/recording-countries", csrf, "Switch off", hidden(country.iso, false), FormStyle.DANGER)
                } else {
                    form("/recording-countries", csrf, "Switch on", hidden(country.iso, true) + CONFIRMATION)
                }
            listOf(
                esc(country.iso),
                esc(if (country.approved) "Offered" else "Off"),
                esc(source),
                esc(if (country.hasLine) "Yes" else "No (set RECORDING_LINES)"),
                action,
            )
        }
    val add =
        "<h2>Another country</h2><p>Only after the operator's own legal review of that country's call-recording and consent " +
            "law has approved it. Many countries and US states require every party's consent.</p>" +
            form(
                "/recording-countries",
                csrf,
                "Switch on",
                input("country", "Country", 2, "[A-Za-z]{2}", "NP") + "<input type=\"hidden\" name=\"enabled\" value=\"true\">" +
                    CONFIRMATION,
            )
    return "<p>Users whose number belongs to an offered country can start recordings, if a line is configured there. " +
        "The default list is BD and IN (RECORDING_COUNTRIES); a change here overrides it for that country.</p>" +
        table(listOf("Country", "Recording", "Set by", "Line", ""), rows, "No country is configured.") + add
}

private fun hidden(
    iso: String,
    enabled: Boolean,
): String =
    "<input type=\"hidden\" name=\"country\" value=\"${esc(iso)}\">" +
        "<input type=\"hidden\" name=\"enabled\" value=\"$enabled\">"

private const val CONFIRMATION =
    "<label><input type=\"checkbox\" name=\"approved\" value=\"yes\" required> Approved after legal review</label>"
