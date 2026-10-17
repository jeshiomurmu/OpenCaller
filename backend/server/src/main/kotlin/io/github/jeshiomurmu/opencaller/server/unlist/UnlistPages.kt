package io.github.jeshiomurmu.opencaller.server.unlist

/**
 * The server-rendered unlist pages (`/unlist`): plain HTML forms that work in any browser without JavaScript or
 * the app. Every value placed in a page goes through [escape].
 */
object UnlistPages {
    /** Step 1: enter the number. */
    fun numberForm(error: String? = null): String =
        page(
            "Unlist your number",
            """
            <p>Remove your name from OpenCaller caller ID and search. We send a one-time code by SMS to prove the number is yours.
            Spam reports about the number are not removed.</p>
            ${errorBlock(error)}
            <form method="post" action="/unlist">
              <label for="e164">Phone number in international format</label>
              <input id="e164" name="e164" type="tel" inputmode="tel" autocomplete="tel" placeholder="+14155550123" required maxlength="32">
              <button type="submit">Send code</button>
            </form>
            """,
        )

    /** Step 2: enter the code sent to [number]. */
    fun codeForm(
        number: String,
        validMinutes: Long,
        error: String? = null,
    ): String =
        page(
            "Enter the code",
            """
            <p>We sent a 6-digit code to ${escape(number)}. It expires in $validMinutes minutes.</p>
            ${errorBlock(error)}
            <form method="post" action="/unlist/verify">
              <input type="hidden" name="e164" value="${escape(number)}">
              <label for="code">Code</label>
              <input id="code" name="code" inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]{6}" maxlength="6" required>
              <button type="submit">Unlist</button>
            </form>
            <p><a href="/unlist">Use a different number</a></p>
            """,
        )

    /** Done. */
    fun done(
        number: String,
        withinMinutes: Int,
    ): String =
        page(
            "Request received",
            "<p>${escape(number)} will be unlisted within $withinMinutes minutes. " +
                "After that OpenCaller shows no name for it in caller ID or search.</p>",
        )

    /** HTML-escapes [text] for element content and quoted attribute values. */
    fun escape(text: String): String =
        buildString(text.length) {
            text.forEach { c ->
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

    private fun errorBlock(error: String?) = error?.let { "<p class=\"error\" role=\"alert\">${escape(it)}</p>" }.orEmpty()

    private fun page(
        title: String,
        body: String,
    ) = """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>${escape(title)} · OpenCaller</title>
        <style>
          body { font-family: system-ui, sans-serif; max-width: 32rem; margin: 2rem auto; padding: 0 1rem; line-height: 1.5; }
          label { display: block; margin-top: 1rem; font-weight: 600; }
          input { display: block; width: 100%; box-sizing: border-box; padding: .6rem; font-size: 1rem; margin-top: .25rem; }
          button { margin-top: 1rem; padding: .6rem 1.2rem; font-size: 1rem; }
          .error { color: #b3261e; font-weight: 600; }
        </style>
        </head>
        <body>
        <h1>${escape(title)}</h1>
        ${body.trimIndent()}
        </body>
        </html>
        """.trimIndent() + "\n"
}
