package io.github.jeshiomurmu.opencaller.server.admin.web

import io.github.jeshiomurmu.opencaller.server.scamfeed.ScamCard
import io.github.jeshiomurmu.opencaller.server.scamfeed.ScamCardForm
import io.github.jeshiomurmu.opencaller.server.scamfeed.ScamCardSave
import io.github.jeshiomurmu.opencaller.server.scamfeed.ScamFeedService
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import java.util.UUID

private val CARD_NOTICES =
    mapOf(
        "saved" to "Saved. Publish the card to show it in apps.",
        "published" to "Published. Apps show it with their next update of the feed (within a day).",
        "withdrawn" to "Withdrawn. Apps drop it with their next update of the feed.",
        "deleted" to "Deleted.",
        "missing" to "That card no longer exists.",
    )

/** The scam feed (docs/prd.md F-PR-15): write, publish, withdraw and delete cards per country and language. */
fun Route.scamCardPages(
    guard: AdminGuard,
    services: AdminWebServices,
) {
    val cards = services.scamCards
    adminGet("/scam-cards", guard) {
        val country = call.request.queryParameters["country"]?.uppercase()
        respondHtml(page("Scam feed", admin, listBody(cards.all(country), csrf), notice(CARD_NOTICES)))
    }
    adminGet("/scam-cards/{id}", guard) {
        val card = idOf(call.parameters["id"])?.let { cards.card(it) } ?: return@adminGet respondNotFound("No such card.")
        respondHtml(page("Edit card", admin, cardForm("/scam-cards/${card.id}", csrf, formOf(card), "Save")))
    }
    adminPost("/scam-cards", guard) { form -> save(services, null, form) }
    adminPost("/scam-cards/{id}", guard) { form ->
        val id = idOf(call.parameters["id"]) ?: return@adminPost respondNotFound("No such card.")
        save(services, id, form)
    }
    adminPost("/scam-cards/{id}/publish", guard) { setPublished(services, call.parameters["id"], true) }
    adminPost("/scam-cards/{id}/withdraw", guard) { setPublished(services, call.parameters["id"], false) }
    adminPost("/scam-cards/{id}/delete", guard) {
        val id = idOf(call.parameters["id"])
        val deleted = id != null && cards.delete(id)
        if (deleted) services.audit.record(admin, ip, "scam-card.delete", id.toString(), "")
        call.respondRedirect("/scam-cards?done=${if (deleted) "deleted" else "missing"}")
    }
}

private suspend fun AdminContext.save(
    services: AdminWebServices,
    id: UUID?,
    form: Parameters,
) {
    val fields =
        ScamCardForm(
            country = form["country"].orEmpty().trim().uppercase(),
            language = form["language"].orEmpty().trim(),
            title = form["title"].orEmpty().trim(),
            body = form["body"].orEmpty().replace("\r\n", "\n").trim(),
            link = form["link"].orEmpty().trim(),
            expiresOn = form["expires"].orEmpty().trim(),
        )
    when (val result = services.scamCards.save(id, fields)) {
        is ScamCardSave.Invalid -> {
            val action = id?.let { "/scam-cards/$it" } ?: "/scam-cards"
            respondHtml(page("Scam feed", admin, cardForm(action, csrf, fields, "Save"), result.reason), HttpStatusCode.BadRequest)
        }

        is ScamCardSave.Saved -> {
            val detail = "${fields.country}/${fields.language}: ${fields.title}".take(MAX_DETAIL)
            services.audit.record(admin, ip, "scam-card.save", result.id.toString(), detail)
            call.respondRedirect("/scam-cards?done=saved")
        }
    }
}

private suspend fun AdminContext.setPublished(
    services: AdminWebServices,
    rawId: String?,
    published: Boolean,
) {
    val id = idOf(rawId)
    val changed = id != null && services.scamCards.setPublished(id, published)
    if (changed) services.audit.record(admin, ip, if (published) "scam-card.publish" else "scam-card.withdraw", id.toString(), "")
    val done =
        when {
            !changed -> "missing"
            published -> "published"
            else -> "withdrawn"
        }
    call.respondRedirect("/scam-cards?done=$done")
}

private fun listBody(
    cards: List<ScamCard>,
    csrf: String,
): String {
    val rows =
        cards.map { card ->
            val state = if (card.published) "Published" else "Draft"
            val toggle =
                if (card.published) {
                    form("/scam-cards/${card.id}/withdraw", csrf, "Withdraw")
                } else {
                    form("/scam-cards/${card.id}/publish", csrf, "Publish")
                }
            listOf(
                esc(card.country),
                esc(card.language),
                link("/scam-cards/${card.id}", card.title),
                esc(state),
                esc(card.expiresOn?.toString() ?: "–"),
                esc(time(card.updatedAt)),
                toggle + form("/scam-cards/${card.id}/delete", csrf, "Delete", style = FormStyle.DANGER),
            )
        }
    val filter =
        "<form method=\"get\" action=\"/scam-cards\">" +
            "<label>Country <input name=\"country\" maxlength=\"2\" pattern=\"[A-Za-z]{2}\"></label>" +
            "<button type=\"submit\">Filter</button></form>"
    return "<p>Short cards about scams going around in a country. Apps show the published cards of their country in the " +
        "Protection tab, in the reader's language when there is a card in it, else in English.</p>" + filter +
        table(listOf("Country", "Language", "Title", "State", "Shown until", "Updated", ""), rows, "No cards yet.") +
        "<h2>New card</h2>" + cardForm("/scam-cards", csrf, ScamCardForm("", "en", "", "", "", ""), "Save as draft")
}

private fun cardForm(
    action: String,
    csrf: String,
    card: ScamCardForm,
    submit: String,
): String {
    val fields =
        "<label>Country <input name=\"country\" required maxlength=\"2\" pattern=\"[A-Za-z]{2}\" value=\"${esc(card.country)}\"></label>" +
            "<label>Language <input name=\"language\" required maxlength=\"6\" value=\"${esc(card.language)}\"></label>" +
            "<label>Title <input name=\"title\" required maxlength=\"${ScamFeedService.MAX_TITLE}\" size=\"50\" " +
            "value=\"${esc(card.title)}\"></label>" +
            "<label>Text <textarea name=\"body\" required maxlength=\"${ScamFeedService.MAX_BODY}\" rows=\"5\" cols=\"60\">" +
            "${esc(card.body)}</textarea></label>" +
            "<label>Link (optional) <input name=\"link\" maxlength=\"${ScamFeedService.MAX_LINK}\" size=\"40\" placeholder=\"https://\" " +
            "value=\"${esc(card.link)}\"></label>" +
            "<label>Show until (optional) <input name=\"expires\" type=\"date\" value=\"${esc(card.expiresOn)}\"></label>"
    return form(action, csrf, submit, fields)
}

private fun formOf(card: ScamCard) =
    ScamCardForm(card.country, card.language, card.title, card.body, card.link.orEmpty(), card.expiresOn?.toString().orEmpty())

private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

private fun idOf(raw: String?): UUID? = raw?.takeIf(UUID_PATTERN::matches)?.let(UUID::fromString)

private const val MAX_DETAIL = 200
