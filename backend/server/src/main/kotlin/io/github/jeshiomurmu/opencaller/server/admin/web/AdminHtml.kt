package io.github.jeshiomurmu.opencaller.server.admin.web

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Small server-rendered HTML for the admin web: no scripts, one inline stylesheet, every value escaped with [esc].

/** [text] with the five HTML-special characters escaped (safe in element content and quoted attributes). */
fun esc(text: String?): String =
    buildString {
        text.orEmpty().forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

/** A time as `2026-11-02 14:05 UTC`, or a dash. */
fun time(at: OffsetDateTime?): String = at?.withOffsetSameInstant(ZoneOffset.UTC)?.format(TIME_FORMAT)?.plus(" UTC") ?: "–"

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** A whole page: navigation, an optional [notice] (already a plain message, escaped here) and [body] (HTML). */
fun page(
    title: String,
    admin: String,
    body: String,
    notice: String? = null,
): String =
    """
    <!doctype html>
    <html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
    <meta name="referrer" content="no-referrer"><title>${esc(title)} – OpenCaller admin</title>
    <style>$STYLE</style></head><body>
    <header><b>OpenCaller admin</b>
    <nav><a href="/">Overview</a><a href="/claims">Claims</a><a href="/businesses">Businesses</a>
    <a href="/otp-sources">OTP sources</a><a href="/sender-ids">Sender IDs</a><a href="/scam-cards">Scam feed</a>
    <a href="/audit">Audit log</a></nav>
    <span class="who">${esc(admin)}</span></header>
    <main><h1>${esc(title)}</h1>${notice?.let { "<p class=\"notice\">${esc(it)}</p>" }.orEmpty()}$body</main>
    </body></html>
    """.trimIndent()

/** A table with [headers] and [rows] (each cell already HTML); [empty] when there are no rows. */
fun table(
    headers: List<String>,
    rows: List<List<String>>,
    empty: String = "Nothing here.",
): String {
    if (rows.isEmpty()) return "<p class=\"muted\">${esc(empty)}</p>"
    val head = headers.joinToString("") { "<th>${esc(it)}</th>" }
    val body = rows.joinToString("") { row -> "<tr>" + row.joinToString("") { "<td>$it</td>" } + "</tr>" }
    return "<table><thead><tr>$head</tr></thead><tbody>$body</tbody></table>"
}

/** How a form looks and what it sends. */
enum class FormStyle {
    /** A normal button. */
    PLAIN,

    /** A red button for actions that take something away. */
    DANGER,

    /** A file upload (`multipart/form-data`). */
    UPLOAD,
}

/** A POST form to [action] with the CSRF [token], [fields] (HTML) and a submit button labelled [submit]. */
fun form(
    action: String,
    token: String,
    submit: String,
    fields: String = "",
    style: FormStyle = FormStyle.PLAIN,
): String {
    val encoding = if (style == FormStyle.UPLOAD) " enctype=\"multipart/form-data\"" else ""
    val buttonClass = if (style == FormStyle.DANGER) " class=\"danger\"" else ""
    return "<form method=\"post\" action=\"${esc(action)}\"$encoding><input type=\"hidden\" name=\"csrf\" value=\"${esc(token)}\">" +
        "$fields<button type=\"submit\"$buttonClass>${esc(submit)}</button></form>"
}

/** A labelled text input. */
fun input(
    name: String,
    label: String,
    maxLength: Int,
    pattern: String? = null,
    placeholder: String? = null,
): String {
    val patternAttr = pattern?.let { " pattern=\"${esc(it)}\"" }.orEmpty()
    val placeholderAttr = placeholder?.let { " placeholder=\"${esc(it)}\"" }.orEmpty()
    return "<label>${esc(label)} <input name=\"${esc(name)}\" required maxlength=\"$maxLength\"$patternAttr$placeholderAttr></label>"
}

/** A link. */
fun link(
    href: String,
    text: String,
): String = "<a href=\"${esc(href)}\">${esc(text)}</a>"

private const val STYLE =
    "body{font:15px/1.45 system-ui,sans-serif;margin:0;color:#1b1b1f;background:#fafafa}" +
        "header{display:flex;gap:16px;align-items:center;padding:10px 20px;background:#1b3a5c;color:#fff;flex-wrap:wrap}" +
        "header a{color:#fff;margin-right:14px}.who{margin-left:auto;opacity:.8}main{padding:16px 20px;max-width:1100px}" +
        "table{border-collapse:collapse;width:100%;background:#fff}th,td{border:1px solid #ddd;padding:6px 8px;text-align:left;" +
        "vertical-align:top}th{background:#eef1f5}form{display:inline-flex;gap:8px;flex-wrap:wrap;align-items:center;margin:4px 0}" +
        "label{display:inline-flex;gap:6px;align-items:center}input,textarea,select{font:inherit;padding:4px 6px}" +
        "button{font:inherit;padding:5px 12px;cursor:pointer}button.danger{background:#b3261e;color:#fff;border:0}" +
        ".notice{background:#e7f2e7;border:1px solid #9c9;padding:8px}.muted{color:#666}dl{display:grid;" +
        "grid-template-columns:max-content 1fr;gap:4px 16px}dt{font-weight:600}img.logo{max-width:160px;border:1px solid #ddd}"
