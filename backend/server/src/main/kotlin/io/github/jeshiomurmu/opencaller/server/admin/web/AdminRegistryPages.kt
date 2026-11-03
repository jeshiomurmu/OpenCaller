package io.github.jeshiomurmu.opencaller.server.admin.web

import io.github.jeshiomurmu.opencaller.server.registry.CsvSenderIdImporter
import io.github.jeshiomurmu.opencaller.server.registry.CurationResult
import io.github.jeshiomurmu.opencaller.server.registry.MAX_FILE_BYTES
import io.github.jeshiomurmu.opencaller.server.registry.SenderIdImportJob
import io.github.jeshiomurmu.opencaller.server.registry.UnavailableRegistryImporter
import io.github.jeshiomurmu.opencaller.server.sync.RegistryRules
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

private val OTP_NOTICES =
    mapOf(
        "saved" to "Saved. Apps get the change with their next sync.",
        "removed" to "Removed. Apps drop it with their next sync.",
        "missing" to "Nothing to remove.",
    )

/** OTP-source curation and sender-ID imports. */
fun Route.registryPages(
    guard: AdminGuard,
    services: AdminWebServices,
) {
    adminGet("/otp-sources", guard) {
        respondHtml(page("OTP sources", admin, otpSourcesBody(services, csrf), notice(OTP_NOTICES)))
    }
    adminPost("/otp-sources/app-hashes", guard) { form ->
        val result =
            services.curation.saveAppHash(
                form["hash"].orEmpty().trim(),
                form["package"].orEmpty().trim(),
                form["app"].orEmpty().trim(),
            )
        finishCuration(services, result, "otp.app-hash.save", form["hash"].orEmpty())
    }
    adminPost("/otp-sources/app-hashes/remove", guard) { form ->
        finishCuration(services, services.curation.removeAppHash(form["hash"].orEmpty()), "otp.app-hash.remove", form["hash"].orEmpty())
    }
    adminPost("/otp-sources/ranges", guard) { form ->
        val result = services.curation.saveRange(form["prefix"].orEmpty().trim(), form["provider"].orEmpty().trim())
        finishCuration(services, result, "otp.range.save", form["prefix"].orEmpty())
    }
    adminPost("/otp-sources/ranges/remove", guard) { form ->
        finishCuration(services, services.curation.removeRange(form["prefix"].orEmpty()), "otp.range.remove", form["prefix"].orEmpty())
    }
    adminGet("/sender-ids", guard) { respondHtml(page("Sender IDs", admin, senderIdsBody(services, csrf))) }
    senderIdImport(guard, services)
}

private suspend fun AdminContext.finishCuration(
    services: AdminWebServices,
    result: CurationResult,
    action: String,
    target: String,
) {
    when (result) {
        is CurationResult.Invalid -> {
            respondHtml(page("OTP sources", admin, otpSourcesBody(services, csrf), result.reason), HttpStatusCode.BadRequest)
        }

        else -> {
            services.audit.record(admin, ip, action, target.take(MAX_MESSAGE), result.toString())
            val done =
                if (result == CurationResult.NotFound) {
                    "missing"
                } else if (result == CurationResult.Removed) {
                    "removed"
                } else {
                    "saved"
                }
            call.respondRedirect("/otp-sources?done=$done")
        }
    }
}

// The upload is multipart: the CSRF token is one of its fields, so it is checked after reading the parts.
private fun Route.senderIdImport(
    guard: AdminGuard,
    services: AdminWebServices,
) {
    post("/sender-ids/import") {
        val context = guard.admit(call) ?: return@post
        val fields = mutableMapOf<String, String>()
        var file: ByteArray? = null
        call.receiveMultipart(formFieldLimit = MAX_FILE_BYTES + 1).forEachPart { part ->
            when (part) {
                is PartData.FormItem -> part.name?.let { fields[it] = part.value.take(MAX_MESSAGE) }
                is PartData.FileItem -> file = part.provider().readBuffer(MAX_FILE_BYTES + 1).readByteArray()
                else -> Unit
            }
            part.release()
        }
        if (!guard.csrfValid(context.admin, fields["csrf"])) {
            call.respondText(
                "This form has expired. Go back, reload the page and try again.",
                ContentType.Text.Plain,
                HttpStatusCode.Forbidden,
            )
            return@post
        }
        context.runImport(services, fields, file)
    }
}

private suspend fun AdminContext.runImport(
    services: AdminWebServices,
    fields: Map<String, String>,
    file: ByteArray?,
) {
    val source = fields["source"].orEmpty().trim()
    val country = fields["country"].orEmpty().trim().uppercase()
    val text = file?.takeIf { it.size <= MAX_FILE_BYTES }?.let(::utf8OrNull)
    val problem =
        when {
            !RegistryRules.SOURCE.matches(source) -> "The source must be 2 to 32 lower-case letters, digits or dashes."
            !RegistryRules.ISO.matches(country) -> "The country must be a two-letter code such as IN."
            text == null -> "Choose a UTF-8 CSV file of at most $MAX_FILE_BYTES bytes."
            else -> null
        }
    if (problem != null) {
        respondHtml(page("Sender IDs", admin, "<p>${esc(problem)}</p><p>${link("/sender-ids", "Back")}</p>"), HttpStatusCode.BadRequest)
        return
    }
    val importer =
        CsvSenderIdImporter(source, country, services.imports.store, complete = fields["complete"] == "yes") { checkNotNull(text) }
    val status = SenderIdImportJob.runRecorded(importer, services.imports.runs, services.clock)
    services.audit.record(admin, ip, "sender-ids.import", "$source/$country", "status $status, complete=${fields["complete"] == "yes"}")
    call.respondRedirect("/sender-ids")
}

private fun utf8OrNull(bytes: ByteArray): String? =
    try {
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        // Not UTF-8: the page asks for a UTF-8 file instead of importing garbled names.
        log.info("An uploaded sender-ID file isn't UTF-8: {}", e.message)
        null
    }

private suspend fun otpSourcesBody(
    services: AdminWebServices,
    csrf: String,
): String {
    val hashes =
        services.curation.appHashes().map { row ->
            listOf(
                "<code>${esc(row.hash)}</code>",
                esc(row.packageName),
                esc(row.appName),
                esc(time(row.updatedAt)),
                form(
                    "/otp-sources/app-hashes/remove",
                    csrf,
                    "Remove",
                    "<input type=\"hidden\" name=\"hash\" value=\"${esc(row.hash)}\">",
                    style = FormStyle.DANGER,
                ),
            )
        }
    val ranges =
        services.curation.ranges().map { row ->
            listOf(
                "<code>${esc(row.prefix)}</code>",
                esc(row.provider),
                esc(time(row.updatedAt)),
                form(
                    "/otp-sources/ranges/remove",
                    csrf,
                    "Remove",
                    "<input type=\"hidden\" name=\"prefix\" value=\"${esc(row.prefix)}\">",
                    style = FormStyle.DANGER,
                ),
            )
        }
    return "<h2>SMS Retriever app hashes</h2><p>An app's 11-character hash names it as the sender of a code message.</p>" +
        table(listOf("Hash", "Package", "App name", "Changed", ""), hashes, "No app hashes.") +
        form(
            "/otp-sources/app-hashes",
            csrf,
            "Save app hash",
            input("hash", "Hash", APP_HASH_LENGTH, "[A-Za-z0-9+/]{11}") + input("package", "Package", MAX_PACKAGE) +
                input("app", "App name", RegistryRules.MAX_NAME),
        ) +
        "<h2>Flash-call verification ranges</h2><p>A missed call from a number with one of these prefixes is a verification call.</p>" +
        table(listOf("Prefix", "Provider", "Changed", ""), ranges, "No ranges.") +
        form(
            "/otp-sources/ranges",
            csrf,
            "Save range",
            input("prefix", "Prefix", MAX_PREFIX, "\\+[1-9][0-9]{3,14}", "+4479") + input("provider", "Provider", RegistryRules.MAX_NAME),
        )
}

private suspend fun senderIdsBody(
    services: AdminWebServices,
    csrf: String,
): String {
    val registries =
        services.imports.importers().filterIsInstance<UnavailableRegistryImporter>().map {
            listOf(esc(it.registry), esc(it.countryIso), esc(it.reason))
        }
    val runs =
        services.imports.runs.recent().map { run ->
            listOf(
                esc(time(run.startedAt)),
                esc(run.source),
                esc(run.countryIso),
                esc(run.status),
                "${run.upserted}",
                "${run.removed}",
                esc(run.detail),
            )
        }
    val upload =
        form(
            "/sender-ids/import",
            csrf,
            "Import",
            input("source", "Source", MAX_SOURCE, "[a-z0-9-]{2,32}", "csv-in-dlt") + input("country", "Country", 2, "[A-Za-z]{2}", "IN") +
                "<label><input type=\"checkbox\" name=\"complete\" value=\"yes\"> The file is this source's whole list " +
                "(rows it no longer has are removed)</label><input type=\"file\" name=\"file\" accept=\".csv,text/csv\" required>",
            style = FormStyle.UPLOAD,
        )
    return "<h2>Import a CSV extract</h2><p>First line <code>header,brand_name,category</code>, then one sender ID per line " +
        "(UTF-8, at most $MAX_FILE_BYTES bytes). Use only extracts we may use.</p>$upload" +
        "<h2>Registries without documented access</h2>" + table(listOf("Registry", "Country", "Why not imported"), registries) +
        "<h2>Recent import runs</h2>" +
        table(listOf("Started", "Source", "Country", "Status", "Added/changed", "Removed", "Detail"), runs, "No imports yet.")
}

private const val MAX_MESSAGE = 300
private val log = LoggerFactory.getLogger("io.github.jeshiomurmu.opencaller.server.admin.web.AdminRegistryPages")
private const val APP_HASH_LENGTH = 11
private const val MAX_PACKAGE = 200
private const val MAX_PREFIX = 16
private const val MAX_SOURCE = 32
