package io.github.jeshiomurmu.opencaller.server.portal

import io.github.jeshiomurmu.opencaller.server.unlist.UnlistPages.escape

/**
 * The business dashboard's pages (`/portal`): plain HTML forms, no JavaScript, every value through [escape]. Forms
 * of a signed-in page carry the session's CSRF token.
 */
internal object PortalPages {
    /** Step 1: the business's number. */
    fun numberForm(error: String? = null): String =
        page(
            "Business dashboard",
            null,
            """
            <p>For verified businesses on OpenCaller. Enter one of your business's numbers; we send a one-time code by
            SMS to that number. Numbers of businesses that are not verified get no code.</p>
            ${errorBlock(error)}
            <form method="post" action="/portal/login">
              <label for="e164">Business phone number in international format</label>
              <input id="e164" name="e164" type="tel" inputmode="tel" autocomplete="tel" placeholder="+8801712345678"
                required maxlength="32">
              <button type="submit">Send code</button>
            </form>
            """,
        )

    /** Step 2: the code. */
    fun codeForm(
        number: String,
        validMinutes: Long,
        error: String? = null,
    ): String =
        page(
            "Enter the code",
            null,
            """
            <p>If ${escape(number)} belongs to a verified business, we sent it a 6-digit code. It expires in $validMinutes minutes.</p>
            ${errorBlock(error)}
            <form method="post" action="/portal/verify">
              <input type="hidden" name="e164" value="${escape(number)}">
              <label for="code">Code</label>
              <input id="code" name="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" required>
              <button type="submit">Sign in</button>
            </form>
            <p><a href="/portal">Use a different number</a></p>
            """,
        )

    /** The dashboard: numbers, the last 30 days of counts and the disputes. */
    fun dashboard(
        data: PortalDashboard,
        csrf: String,
    ): String {
        val numbers = data.numbers.joinToString("") { "<li>${escape(it.e164)} – ${escape(it.callReason ?: "no call reason")}</li>" }
        val rows =
            data.days.joinToString("") {
                "<tr><td>${it.day}</td><td>${escape(it.e164)}</td><td>${it.searches}</td>" +
                    "<td>${it.spamReports}</td><td>${it.notSpamReports}</td></tr>"
            }
        val days =
            if (rows.isEmpty()) {
                "<p class=\"muted\">Nothing in the last ${PortalStats.DAYS} days.</p>"
            } else {
                "<table><thead><tr><th>Day (UTC)</th><th>Number</th><th>Searches</th><th>Spam reports</th>" +
                    "<th>Not-spam reports</th></tr></thead>" +
                    "<tbody>$rows</tbody></table>"
            }
        val disputeRows =
            data.disputes.joinToString("") {
                "<tr><td>${escape(it.e164)}</td><td>${it.filedOn}</td><td>${escape(statusText(it.status))}</td>" +
                    "<td>${it.decidedOn ?: "–"}</td></tr>"
            }
        val disputes =
            if (disputeRows.isEmpty()) {
                "<p class=\"muted\">No disputes.</p>"
            } else {
                "<table><thead><tr><th>Number</th><th>Filed</th><th>Status</th><th>Decided</th></tr></thead>" +
                    "<tbody>$disputeRows</tbody></table>"
            }
        return page(
            data.name,
            csrf,
            """
            <h2>Your numbers</h2>
            <ul>$numbers</ul>
            <p><a href="/portal/profile">Change website, opening hours or call reasons</a></p>
            <h2>Last ${PortalStats.DAYS} days</h2>
            <p>Searches are people who typed one of your numbers into OpenCaller's search (we don't record who). Reports are
            OpenCaller users marking a call or message from your number as spam or as not spam. OpenCaller never learns
            whether people answered or declined your calls, so this page can't show it.</p>
            $days
            <h2>Disputes</h2>
            $disputes
            """,
        )
    }

    /** The fields a business may change without a new review. */
    fun profileForm(
        data: PortalDashboard,
        csrf: String,
        notice: String? = null,
        error: String? = null,
    ): String {
        val reasons =
            data.numbers.joinToString("") {
                """
                <label for="reason-${escape(it.e164)}">Call reason for ${escape(it.e164)}</label>
                <input id="reason-${escape(it.e164)}" name="reason:${escape(it.e164)}" maxlength="200"
                  value="${escape(it.callReason.orEmpty())}">
                """.trimIndent()
            }
        return page(
            "Profile",
            csrf,
            """
            <p>Your name, category and logo were checked when your claim was approved; to change them, file a new claim
            from the app. Changes here reach apps with their next daily update.</p>
            ${notice?.let { "<p class=\"notice\" role=\"status\">${escape(it)}</p>" }.orEmpty()}
            ${errorBlock(error)}
            <form method="post" action="/portal/profile">
              <input type="hidden" name="csrf" value="${escape(csrf)}">
              <label for="website">Website (https://)</label>
              <input id="website" name="website" type="url" maxlength="200" value="${escape(data.website.orEmpty())}">
              <label for="hours">Opening hours</label>
              <input id="hours" name="hours" maxlength="200" value="${escape(data.hours.orEmpty())}">
              $reasons
              <button type="submit">Save</button>
            </form>
            <p><a href="/portal">Back to the dashboard</a></p>
            """,
        )
    }

    private fun statusText(status: String) =
        when (status) {
            "pending" -> "Waiting for review"
            "approved" -> "Accepted"
            "rejected" -> "Not accepted"
            else -> status
        }

    private fun errorBlock(error: String?) = error?.let { "<p class=\"error\" role=\"alert\">${escape(it)}</p>" }.orEmpty()

    // [csrf] set: a signed-in page with the sign-out form.
    private fun page(
        title: String,
        csrf: String?,
        body: String,
    ): String {
        val signOut =
            csrf
                ?.let {
                    "<form class=\"out\" method=\"post\" action=\"/portal/logout\">" +
                        "<input type=\"hidden\" name=\"csrf\" value=\"${escape(it)}\">" +
                        "<button type=\"submit\">Sign out</button></form>"
                }.orEmpty()
        return """
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="referrer" content="no-referrer">
            <title>${escape(title)} · OpenCaller for business</title>
            <style>$STYLE</style>
            </head>
            <body>
            <header><b>OpenCaller for business</b>$signOut</header>
            <main>
            <h1>${escape(title)}</h1>
            ${body.trimIndent()}
            </main>
            </body>
            </html>
            """.trimIndent() + "\n"
    }

    private const val STYLE =
        "body{font-family:system-ui,sans-serif;margin:0;line-height:1.5;color:#1b1b1f}header{display:flex;align-items:center;" +
            "gap:1rem;padding:.6rem 1rem;background:#1b3a5c;color:#fff}.out{margin-left:auto}main{max-width:52rem;margin:1rem auto;" +
            "padding:0 1rem}label{display:block;margin-top:1rem;font-weight:600}input{display:block;width:100%;box-sizing:border-box;" +
            "padding:.6rem;font-size:1rem;margin-top:.25rem}button{margin-top:1rem;padding:.6rem 1.2rem;font-size:1rem}" +
            "header button{margin:0}table{border-collapse:collapse;width:100%}th,td{border:1px solid #ddd;padding:.4rem .6rem;" +
            "text-align:left}th{background:#eef1f5}.error{color:#b3261e;font-weight:600}.notice{background:#e7f2e7;padding:.5rem}" +
            ".muted{color:#666}"
}
