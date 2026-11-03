package io.github.jeshiomurmu.opencaller.server.sync

import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.github.jeshiomurmu.opencaller.server.db.OtpAppHashes
import io.github.jeshiomurmu.opencaller.server.db.SenderIds
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.VerificationRanges
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.jdbc.batchInsert
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
    val deleted: Boolean = false,
)

/** `GET /v1/sender-ids/{countryIso}` response. Pass [syncedAt] as `since` next time to get only newer rows. */
@Serializable
data class SenderIdsResponse(
    val countryIso: String,
    val syncedAt: Long,
    val senderIds: List<SenderIdDto>,
)

/** An SMS Retriever app hash; [deleted] rows tell apps to remove it. */
@Serializable
data class AppHashDto(
    val hash: String,
    val packageName: String,
    val appName: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** A flash-call verification provider's number prefix; [deleted] rows tell apps to remove it. */
@Serializable
data class VerificationRangeDto(
    val prefix: String,
    val provider: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/** `GET /v1/otp-sources` response. */
@Serializable
data class OtpSourcesResponse(
    val syncedAt: Long,
    val appHashes: List<AppHashDto>,
    val verificationRanges: List<VerificationRangeDto>,
)

/**
 * `GET /v1/link-prefixes` response: every known-bad host's [HostPrefix], sorted. Always the whole list (it is small),
 * so a host taken off the list disappears from apps on their next sync.
 */
@Serializable
data class LinkPrefixesResponse(
    val syncedAt: Long,
    val prefixes: List<String>,
)

/**
 * Registry data apps sync incrementally with `?since=<epoch ms>` (rows with a later `updated_at`, removed rows
 * included with `deleted: true`): sender IDs (`sender_ids`, filled by the importers), app hashes and verification
 * ranges (`otp_app_hashes`, `verification_ranges`, curated in the admin web); the seed files fill each table once
 * on start, without overwriting rows already there. The known-bad link list is served only as hash prefixes.
 */
class SyncService(
    private val db: ServerDatabase,
    private val seed: SeedData,
) {
    private val regions = PhoneNumberUtil.getInstance().supportedRegions

    /** Inserts the seed rows that aren't in the tables yet (admin edits and imports are never overwritten). */
    suspend fun seed() {
        db.query {
            SenderIds.batchInsert(seed.senderIds, ignore = true, shouldReturnGeneratedValues = false) { row ->
                this[SenderIds.countryIso] = row.countryIso
                this[SenderIds.header] = row.header
                this[SenderIds.brandName] = row.brandName
                this[SenderIds.category] = row.category
                this[SenderIds.sourceName] = row.source
                this[SenderIds.updatedAt] = utc(row.updatedAt)
            }
            OtpAppHashes.batchInsert(seed.appHashes, ignore = true, shouldReturnGeneratedValues = false) { row ->
                this[OtpAppHashes.hash] = row.hash
                this[OtpAppHashes.packageName] = row.packageName
                this[OtpAppHashes.appName] = row.appName
                this[OtpAppHashes.updatedAt] = utc(row.updatedAt)
            }
            VerificationRanges.batchInsert(seed.verificationRanges, ignore = true, shouldReturnGeneratedValues = false) { row ->
                this[VerificationRanges.prefix] = row.prefix
                this[VerificationRanges.provider] = row.provider
                this[VerificationRanges.updatedAt] = utc(row.updatedAt)
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
                    .where { (SenderIds.countryIso eq iso) and (SenderIds.updatedAt greater utc(since)) }
                    .orderBy(SenderIds.updatedAt to SortOrder.ASC, SenderIds.header to SortOrder.ASC)
                    .limit(MAX_ROWS)
                    .map { row ->
                        SenderIdDto(
                            header = row[SenderIds.header],
                            brandName = row[SenderIds.brandName],
                            category = row[SenderIds.category],
                            source = row[SenderIds.sourceName],
                            businessId = row[SenderIds.businessId]?.toString(),
                            updatedAt = row[SenderIds.updatedAt].toInstant().toEpochMilli(),
                            deleted = row[SenderIds.deletedAt] != null,
                        )
                    }
            }
        return SenderIdsResponse(iso, rows.maxOfOrNull { it.updatedAt } ?: since.toEpochMilli(), rows)
    }

    /** App hashes and verification ranges updated (or removed) after [rawSince]. */
    suspend fun otpSources(rawSince: String?): OtpSourcesResponse {
        val since = utc(since(rawSince))
        val (hashes, ranges) =
            db.query {
                val hashes =
                    OtpAppHashes
                        .selectAll()
                        .where { OtpAppHashes.updatedAt greater since }
                        .orderBy(OtpAppHashes.updatedAt to SortOrder.ASC, OtpAppHashes.hash to SortOrder.ASC)
                        .limit(MAX_ROWS)
                        .map {
                            AppHashDto(
                                hash = it[OtpAppHashes.hash],
                                packageName = it[OtpAppHashes.packageName],
                                appName = it[OtpAppHashes.appName],
                                updatedAt = it[OtpAppHashes.updatedAt].toInstant().toEpochMilli(),
                                deleted = it[OtpAppHashes.deletedAt] != null,
                            )
                        }
                val ranges =
                    VerificationRanges
                        .selectAll()
                        .where { VerificationRanges.updatedAt greater since }
                        .orderBy(VerificationRanges.updatedAt to SortOrder.ASC, VerificationRanges.prefix to SortOrder.ASC)
                        .limit(MAX_ROWS)
                        .map {
                            VerificationRangeDto(
                                prefix = it[VerificationRanges.prefix],
                                provider = it[VerificationRanges.provider],
                                updatedAt = it[VerificationRanges.updatedAt].toInstant().toEpochMilli(),
                                deleted = it[VerificationRanges.deletedAt] != null,
                            )
                        }
                hashes to ranges
            }
        val newest = (hashes.map { it.updatedAt } + ranges.map { it.updatedAt }).maxOrNull() ?: since.toInstant().toEpochMilli()
        return OtpSourcesResponse(newest, hashes, ranges)
    }

    /** The known-bad link hosts as hash prefixes; the host names never leave the server. */
    fun linkPrefixes(): LinkPrefixesResponse =
        LinkPrefixesResponse(
            syncedAt = seed.badHosts.maxOfOrNull { it.updatedAt.toEpochMilli() } ?: 0L,
            prefixes =
                seed.badHosts
                    .map { HostPrefix.of(it.host) }
                    .distinct()
                    .sorted(),
        )

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

    private fun utc(instant: Instant): OffsetDateTime = OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)

    private fun invalidCountry() =
        ApiProblem(HttpStatusCode.BadRequest, "invalid_country", "Unknown country", "Use an ISO 3166-1 alpha-2 code such as BD.")

    private companion object {
        // Rows per response; a client that gets this many asks again with the returned syncedAt.
        const val MAX_ROWS = 5000
    }
}
