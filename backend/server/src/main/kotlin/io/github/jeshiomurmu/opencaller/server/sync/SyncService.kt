package io.github.jeshiomurmu.opencaller.server.sync

import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.github.jeshiomurmu.opencaller.server.db.SenderIds
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.batchUpsert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** A registered SMS sender ID (docs/architecture.md §6 `SenderIdEntity`); times are epoch milliseconds. */
@Serializable
data class SenderIdDto(
    val header: String,
    val brandName: String,
    val category: String,
    val source: String,
    val businessId: String? = null,
    val updatedAt: Long,
)

/** `GET /v1/sender-ids/{countryIso}` response. Pass [syncedAt] as `since` next time to get only newer rows. */
@Serializable
data class SenderIdsResponse(
    val countryIso: String,
    val syncedAt: Long,
    val senderIds: List<SenderIdDto>,
)

/** An SMS Retriever app hash. */
@Serializable
data class AppHashDto(
    val hash: String,
    val packageName: String,
    val appName: String,
    val updatedAt: Long,
)

/** A flash-call verification provider's number prefix. */
@Serializable
data class VerificationRangeDto(
    val prefix: String,
    val provider: String,
    val updatedAt: Long,
)

/** `GET /v1/otp-sources` response. */
@Serializable
data class OtpSourcesResponse(
    val syncedAt: Long,
    val appHashes: List<AppHashDto>,
    val verificationRanges: List<VerificationRangeDto>,
)

/**
 * Registry data apps sync incrementally with `?since=<epoch ms>` (rows with a later `updated_at`): sender IDs from
 * the `sender_ids` table (seeded from `seed/sender_ids.csv` on start) and OTP sources from the seed files.
 */
class SyncService(
    private val db: ServerDatabase,
    private val seed: SeedData,
) {
    private val regions = PhoneNumberUtil.getInstance().supportedRegions

    /** Writes the seed sender IDs into `sender_ids` (insert or update by country + header). */
    suspend fun seedSenderIds() {
        db.query {
            SenderIds.batchUpsert(seed.senderIds, shouldReturnGeneratedValues = false) { row ->
                this[SenderIds.countryIso] = row.countryIso
                this[SenderIds.header] = row.header
                this[SenderIds.brandName] = row.brandName
                this[SenderIds.category] = row.category
                this[SenderIds.sourceName] = row.source
                this[SenderIds.updatedAt] = row.updatedAt.atOffset(ZoneOffset.UTC)
            }
        }
    }

    /** Sender IDs of [rawIso] updated after [rawSince]. */
    suspend fun senderIds(
        rawIso: String,
        rawSince: String?,
    ): SenderIdsResponse {
        val iso = rawIso.uppercase().takeIf { it.length == 2 && it in regions } ?: throw invalidCountry()
        val since = since(rawSince)
        val rows =
            db.query {
                SenderIds
                    .selectAll()
                    .where {
                        (SenderIds.countryIso eq iso) and (
                            SenderIds.updatedAt greater
                                OffsetDateTime.ofInstant(
                                    since,
                                    ZoneOffset.UTC,
                                )
                        )
                    }.orderBy(SenderIds.updatedAt to SortOrder.ASC, SenderIds.header to SortOrder.ASC)
                    .limit(MAX_ROWS)
                    .map { row ->
                        SenderIdDto(
                            header = row[SenderIds.header],
                            brandName = row[SenderIds.brandName],
                            category = row[SenderIds.category],
                            source = row[SenderIds.sourceName],
                            businessId = row[SenderIds.businessId]?.toString(),
                            updatedAt = row[SenderIds.updatedAt].toInstant().toEpochMilli(),
                        )
                    }
            }
        return SenderIdsResponse(iso, rows.maxOfOrNull { it.updatedAt } ?: since.toEpochMilli(), rows)
    }

    /** App hashes and verification ranges updated after [rawSince]. */
    fun otpSources(rawSince: String?): OtpSourcesResponse {
        val since = since(rawSince)
        val hashes =
            seed.appHashes
                .filter { it.updatedAt.isAfter(since) }
                .map { AppHashDto(it.hash, it.packageName, it.appName, it.updatedAt.toEpochMilli()) }
        val ranges =
            seed.verificationRanges
                .filter { it.updatedAt.isAfter(since) }
                .map { VerificationRangeDto(it.prefix, it.provider, it.updatedAt.toEpochMilli()) }
        val newest = (hashes.map { it.updatedAt } + ranges.map { it.updatedAt }).maxOrNull() ?: since.toEpochMilli()
        return OtpSourcesResponse(newest, hashes, ranges)
    }

    private fun since(raw: String?): Instant {
        if (raw == null) return Instant.EPOCH
        val millis =
            raw.toLongOrNull()?.takeIf { it >= 0 }
                ?: throw ApiProblem(
                    HttpStatusCode.BadRequest,
                    "invalid_request",
                    "Invalid since",
                    "since must be epoch milliseconds (>= 0).",
                )
        return Instant.ofEpochMilli(millis)
    }

    private fun invalidCountry() =
        ApiProblem(HttpStatusCode.BadRequest, "invalid_country", "Unknown country", "Use an ISO 3166-1 alpha-2 code such as BD.")

    private companion object {
        // Rows per response; a client that gets this many asks again with the returned syncedAt.
        const val MAX_ROWS = 5000
    }
}
