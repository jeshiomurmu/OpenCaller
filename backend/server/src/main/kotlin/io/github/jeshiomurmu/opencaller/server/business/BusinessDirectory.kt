package io.github.jeshiomurmu.opencaller.server.business

import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.github.jeshiomurmu.opencaller.server.db.BusinessNumbers
import io.github.jeshiomurmu.opencaller.server.db.Businesses
import io.github.jeshiomurmu.opencaller.server.db.ReviewStatus
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.storage.ObjectStore
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Verified businesses as everyone may see them (docs/prd.md F-BZ-02): a business's public profile, the per-country
 * directory apps keep on the phone (so "verified business" works before any network lookup, e.g. for "block
 * non-contacts", and offline), and logos. Businesses claimed their numbers to be shown, so their numbers and names
 * are public; nothing here is about users.
 */
class BusinessDirectory(
    private val db: ServerDatabase,
    private val objects: ObjectStore,
) {
    private val regions = PhoneNumberUtil.getInstance().supportedRegions

    /** The public profile of verified business [rawId]; 404 for anything else. */
    suspend fun profile(rawId: String): BusinessProfileResponse {
        val id = parseId(rawId)
        return db.query {
            val rows =
                Businesses
                    .join(BusinessNumbers, JoinType.LEFT, Businesses.id, BusinessNumbers.businessId)
                    .selectAll()
                    .where { (Businesses.id eq id) and (Businesses.status eq ReviewStatus.APPROVED) }
                    .orderBy(BusinessNumbers.e164 to SortOrder.ASC)
                    .toList()
            val first = rows.firstOrNull() ?: throw businessNotFound()
            BusinessProfileResponse(
                id = id.toString(),
                name = first[Businesses.displayName],
                category = first[Businesses.category],
                website = first[Businesses.website],
                hours = first[Businesses.hours],
                logoUrl = first[Businesses.logoKey]?.let { logoPath(id) },
                numbers =
                    rows.mapNotNull { row ->
                        row.getOrNull(BusinessNumbers.e164)?.let { BusinessNumberDto(it, row.getOrNull(BusinessNumbers.defaultCallReason)) }
                    },
            )
        }
    }

    /**
     * Business numbers of [rawIso] changed after [rawSince] (epoch ms): verified ones, and ones whose business was
     * revoked (`removed`). A number never verified is never listed.
     */
    suspend fun directory(
        rawIso: String,
        rawSince: String?,
    ): DirectoryResponse {
        val iso = rawIso.uppercase().takeIf { it.length == 2 && it in regions } ?: throw invalidCountry()
        val since = sinceOf(rawSince)
        val entries =
            db.query {
                BusinessNumbers
                    .join(Businesses, JoinType.INNER, BusinessNumbers.businessId, Businesses.id)
                    .selectAll()
                    .where {
                        (Businesses.countryIso eq iso) and Businesses.verifiedAt.isNotNull() and
                            (BusinessNumbers.updatedAt greater OffsetDateTime.ofInstant(since, ZoneOffset.UTC))
                    }.orderBy(BusinessNumbers.updatedAt to SortOrder.ASC, BusinessNumbers.e164 to SortOrder.ASC)
                    .limit(MAX_ROWS)
                    .map { row ->
                        val id = row[Businesses.id]
                        DirectoryEntryDto(
                            e164 = row[BusinessNumbers.e164],
                            businessId = id.toString(),
                            name = row[Businesses.displayName],
                            category = row[Businesses.category],
                            callReason = row[BusinessNumbers.defaultCallReason],
                            logoUrl = row[Businesses.logoKey]?.let { logoPath(id) },
                            updatedAt = row[BusinessNumbers.updatedAt].toInstant().toEpochMilli(),
                            removed = row[Businesses.status] != ReviewStatus.APPROVED,
                        )
                    }
            }
        return DirectoryResponse(iso, entries.maxOfOrNull { it.updatedAt } ?: since.toEpochMilli(), entries)
    }

    /** The logo of verified business [rawId] (bytes and content type); 404 if it has none. */
    suspend fun logo(rawId: String): Pair<ByteArray, String> {
        val id = parseId(rawId)
        val key =
            db.query {
                Businesses
                    .select(Businesses.logoKey)
                    .where { (Businesses.id eq id) and (Businesses.status eq ReviewStatus.APPROVED) }
                    .singleOrNull()
                    ?.get(Businesses.logoKey)
            } ?: throw businessNotFound()
        val bytes = objects.get(key) ?: throw businessNotFound()
        val type = if (key.endsWith(".png")) "image/png" else "image/jpeg"
        return bytes to type
    }

    private fun parseId(raw: String): UUID = runCatching { UUID.fromString(raw) }.getOrNull() ?: throw businessNotFound()

    private fun sinceOf(raw: String?): Instant {
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

    /** Paths. */
    companion object {
        // Rows per response; a client that gets this many asks again with the returned syncedAt.
        private const val MAX_ROWS = 5000

        /** API path of business [id]'s logo. */
        fun logoPath(id: UUID) = "/v1/business/$id/logo"
    }
}

/** 404 `business_not_found`. */
internal fun businessNotFound() = ApiProblem(HttpStatusCode.NotFound, "business_not_found", "No such verified business")
