package io.github.jeshiomurmu.opencaller.server.sync

import java.time.Instant
import java.time.format.DateTimeParseException

/** A row of `seed/sender_ids.csv`. */
data class SenderIdSeed(
    val countryIso: String,
    val header: String,
    val brandName: String,
    val category: String,
    val source: String,
    val updatedAt: Instant,
)

/** A row of `seed/otp_app_hashes.csv`: an SMS Retriever app hash and the app it belongs to. */
data class AppHashSeed(
    val hash: String,
    val packageName: String,
    val appName: String,
    val updatedAt: Instant,
)

/** A row of `seed/verification_ranges.csv`: a number prefix used by a flash-call verification provider. */
data class VerificationRangeSeed(
    val prefix: String,
    val provider: String,
    val updatedAt: Instant,
)

/** A seed file is malformed; the message names the file and line. */
class SeedDataException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** All seed files, parsed and validated (backend/server/seed/README.md). */
data class SeedData(
    val senderIds: List<SenderIdSeed>,
    val appHashes: List<AppHashSeed>,
    val verificationRanges: List<VerificationRangeSeed>,
) {
    /** Loading from the classpath (`seed/…`, packaged from backend/server/seed). */
    companion object {
        private val ISO = Regex("[A-Z]{2}")
        private val HEADER = Regex("[A-Za-z0-9-]{2,20}")
        private val SLUG = Regex("[a-z_]{2,32}")
        private val APP_HASH = Regex("[A-Za-z0-9+/]{11}")
        private val PACKAGE = Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+")
        private val PREFIX = Regex("\\+[1-9][0-9]{0,14}")
        private val SOURCE = Regex("[a-z0-9-]{2,32}")
        private const val MAX_NAME = 100

        /** Reads the three files from the classpath. */
        fun load(): SeedData =
            SeedData(
                senderIds =
                    rows("sender_ids.csv", listOf("country_iso", "header", "brand_name", "category", "source", "updated_at")) { c ->
                        SenderIdSeed(
                            countryIso = c.cell("country_iso", ISO),
                            header = c.cell("header", HEADER),
                            brandName = c.name("brand_name"),
                            category = c.cell("category", SLUG),
                            source = c.cell("source", SOURCE),
                            updatedAt = c.instant("updated_at"),
                        )
                    }.also { rows -> unique(rows.map { it.countryIso to it.header }, "sender_ids.csv") },
                appHashes =
                    rows("otp_app_hashes.csv", listOf("hash", "package_name", "app_name", "updated_at")) { c ->
                        AppHashSeed(c.cell("hash", APP_HASH), c.cell("package_name", PACKAGE), c.name("app_name"), c.instant("updated_at"))
                    }.also { rows -> unique(rows.map { it.hash }, "otp_app_hashes.csv") },
                verificationRanges =
                    rows("verification_ranges.csv", listOf("prefix", "provider", "updated_at")) { c ->
                        VerificationRangeSeed(c.cell("prefix", PREFIX), c.name("provider"), c.instant("updated_at"))
                    }.also { rows -> unique(rows.map { it.prefix }, "verification_ranges.csv") },
            )

        /** One CSV line by column name; [where] names the file and line for error messages. */
        private class Cells(
            val where: String,
            private val values: Map<String, String>,
        ) {
            operator fun get(column: String): String = values.getValue(column)
        }

        private fun <T> rows(
            file: String,
            columns: List<String>,
            parse: (Cells) -> T,
        ): List<T> {
            val text = SeedData::class.java.getResource("/seed/$file")?.readText(Charsets.UTF_8) ?: fail("seed/$file is missing")
            return text
                .lines()
                .withIndex()
                .filter { (_, line) -> line.isNotBlank() && !line.startsWith("#") }
                .map { (index, line) ->
                    val where = "seed/$file line ${index + 1}"
                    val cells = line.split(',').map { it.trim() }
                    if ('"' in line || cells.size != columns.size) fail("$where: expected ${columns.size} unquoted columns")
                    parse(Cells(where, columns.zip(cells).toMap()))
                }
        }

        private fun fail(message: String): Nothing = throw SeedDataException(message)

        private fun Cells.cell(
            column: String,
            pattern: Regex,
        ): String = this[column].takeIf(pattern::matches) ?: fail("$where: invalid $column")

        private fun Cells.name(column: String): String =
            this[column].takeIf { it.length in 1..MAX_NAME && it.none(Char::isISOControl) } ?: fail("$where: invalid $column")

        private fun Cells.instant(column: String): Instant =
            try {
                Instant.parse(this[column])
            } catch (e: DateTimeParseException) {
                throw SeedDataException("$where: $column must be an ISO-8601 instant", e)
            }

        private fun unique(
            keys: List<Any>,
            file: String,
        ) {
            val duplicate =
                keys
                    .groupBy { it }
                    .filterValues { it.size > 1 }
                    .keys
                    .firstOrNull()
            if (duplicate != null) fail("seed/$file: duplicate key $duplicate")
        }
    }
}
