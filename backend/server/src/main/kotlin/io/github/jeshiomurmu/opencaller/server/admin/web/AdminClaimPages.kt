package io.github.jeshiomurmu.opencaller.server.admin.web

import io.github.jeshiomurmu.opencaller.server.business.ClaimDetail
import io.github.jeshiomurmu.opencaller.server.business.ClaimStatus
import io.github.jeshiomurmu.opencaller.server.business.ReviewOutcome
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import java.util.UUID

private val CLAIM_NOTICES =
    mapOf(
        "approved" to "Approved. The business shows in lookups now; apps get it with their next directory sync.",
        "rejected" to "Rejected. The claimant sees your note.",
        "refused" to "Nothing changed: the claim isn't waiting for review, or the number already belongs to a verified business.",
        "note" to "Write a note for the claimant (1 to 500 characters) to reject a claim.",
    )

private val BUSINESS_NOTICES =
    mapOf(
        "revoked" to "Revoked. It left lookups now; apps drop it with their next directory sync.",
        "unchanged" to "Nothing changed: the business wasn't verified.",
    )

/** Claims queue, claim details with files, approve and reject; verified businesses and revoke. */
fun Route.claimPages(
    guard: AdminGuard,
    services: AdminWebServices,
) {
    adminGet("/claims", guard) {
        val status = ClaimStatus.entries.firstOrNull { it.value == call.request.queryParameters["status"] } ?: ClaimStatus.SUBMITTED
        respondHtml(page("Business claims", admin, claimList(status, services.claims.claims(status)), notice(CLAIM_NOTICES)))
    }
    adminGet("/claims/{id}", guard) {
        val claim = claimId()?.let { services.claims.claim(it) } ?: return@adminGet respondNotFound("No such claim.")
        respondHtml(page("Claim for ${claim.e164}", admin, claimDetail(claim, csrf), notice(CLAIM_NOTICES)))
    }
    adminGet("/claims/{id}/file", guard) {
        val id = claimId() ?: return@adminGet respondNotFound("No such claim.")
        val key = call.request.queryParameters["key"].orEmpty()
        val (bytes, type) = services.claims.file(id, key) ?: return@adminGet respondNotFound("No such file.")
        services.audit.record(admin, ip, "claim.file.view", id.toString(), key)
        // Downloaded, never rendered in the admin page's origin; the sandbox stops anything active in it.
        call.response.header(
            HttpHeaders.ContentDisposition,
            ContentDisposition.Attachment.withParameter("filename", key.substringAfterLast('/')).toString(),
        )
        call.response.header("Content-Security-Policy", "default-src 'none'; sandbox")
        call.respondBytes(bytes, ContentType.parse(type))
    }
    adminPost("/claims/{id}/approve", guard) {
        val id = claimId() ?: return@adminPost respondNotFound("No such claim.")
        val outcome = services.claims.approve(id, admin)
        services.audit.record(admin, ip, "claim.approve", id.toString(), outcome.toString())
        call.respondRedirect("/claims/$id?done=${if (outcome is ReviewOutcome.Approved) "approved" else "refused"}")
    }
    adminPost("/claims/{id}/reject", guard) { form ->
        val id = claimId() ?: return@adminPost respondNotFound("No such claim.")
        val note = form["note"]?.trim().orEmpty()
        if (note.length !in 1..MAX_NOTE ||
            note.any { it.isISOControl() && it != '\n' }
        ) {
            return@adminPost call.respondRedirect("/claims/$id?done=note")
        }
        val outcome = services.claims.reject(id, admin, note)
        services.audit.record(admin, ip, "claim.reject", id.toString(), note)
        call.respondRedirect("/claims/$id?done=${if (outcome == ReviewOutcome.Rejected) "rejected" else "refused"}")
    }
    businessPages(guard, services)
}

private fun Route.businessPages(
    guard: AdminGuard,
    services: AdminWebServices,
) {
    adminGet("/businesses", guard) {
        val rows =
            services.claims.businesses().map { business ->
                listOf(
                    esc(business.name),
                    esc(business.category),
                    esc(business.countryIso),
                    esc(business.numbers.joinToString(", ")),
                    if (business.approved) "verified" else "revoked",
                    esc(time(business.verifiedAt)),
                    if (business.approved) form("/businesses/${business.id}/revoke", csrf, "Revoke", style = FormStyle.DANGER) else "",
                )
            }
        val body = table(listOf("Name", "Category", "Country", "Numbers", "Status", "Verified", ""), rows, "No verified businesses yet.")
        respondHtml(page("Verified businesses", admin, body, notice(BUSINESS_NOTICES)))
    }
    adminPost("/businesses/{id}/revoke", guard) {
        val id =
            call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: return@adminPost respondNotFound("No such business.")
        val revoked = services.claims.revoke(id)
        services.audit.record(admin, ip, "business.revoke", id.toString(), if (revoked) "revoked" else "not verified")
        call.respondRedirect("/businesses?done=${if (revoked) "revoked" else "unchanged"}")
    }
}

private fun AdminContext.claimId(): UUID? = call.parameters["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }

private fun claimList(
    status: ClaimStatus,
    claims: List<ClaimDetail>,
): String {
    val tabs =
        ClaimStatus.entries.joinToString(" · ") {
            if (it ==
                status
            ) {
                "<b>${it.value}</b>"
            } else {
                link("/claims?status=${it.value}", it.value)
            }
        }
    val rows =
        claims.map { claim ->
            listOf(
                link("/claims/${claim.id}", claim.e164),
                esc(claim.displayName),
                esc(claim.category),
                esc(claim.countryIso),
                esc(time(claim.submittedAt)),
            )
        }
    return "<p>$tabs</p>" +
        table(listOf("Number", "Business", "Category", "Country", "Submitted"), rows, "No claims with status ${status.value}.")
}

private fun claimDetail(
    claim: ClaimDetail,
    csrf: String,
): String {
    val fields =
        listOf(
            "Status" to claim.status.value,
            "Number" to claim.e164,
            "Proven by" to if (claim.verifiedAt != null) "${claim.method} code, ${time(claim.verifiedAt)}" else "not yet",
            "Legal name" to claim.legalName,
            "Display name" to claim.displayName,
            "Category" to claim.category,
            "Website" to claim.website,
            "Hours" to claim.hours,
            "Call reason" to claim.callReason,
            "Submitted" to time(claim.submittedAt),
            "Decided" to claim.decidedAt?.let { "${time(it)} by ${claim.reviewer}" },
            "Note to claimant" to claim.reviewNote,
        )
    val list = "<dl>" + fields.joinToString("") { (name, value) -> "<dt>${esc(name)}</dt><dd>${esc(value ?: "–")}</dd>" } + "</dl>"
    val files =
        claim.documents.filter { it.sizeBytes > 0 }.map { file ->
            listOf(
                link("/claims/${claim.id}/file?key=${file.key}", file.key.substringAfterLast('/')),
                esc(file.contentType),
                "${file.sizeBytes} bytes",
            )
        }
    val logo = claim.logoKey?.let { "<p>Logo: ${link("/claims/${claim.id}/file?key=$it", "download")}</p>" }.orEmpty()
    val actions =
        if (claim.status == ClaimStatus.SUBMITTED) {
            "<h2>Decision</h2><p>Approve only if the documents show that this business owns the number.</p>" +
                form("/claims/${claim.id}/approve", csrf, "Approve and publish") + "<br>" +
                form(
                    "/claims/${claim.id}/reject",
                    csrf,
                    "Reject",
                    "<label>Note to the claimant <input name=\"note\" required maxlength=\"$MAX_NOTE\"></label>",
                    style = FormStyle.DANGER,
                )
        } else {
            ""
        }
    return list + "<h2>Documents</h2>" + table(listOf("File", "Type", "Size"), files, "No documents.") + logo + actions
}

private const val MAX_NOTE = 500
