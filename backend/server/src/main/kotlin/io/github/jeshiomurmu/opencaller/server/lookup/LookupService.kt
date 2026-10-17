package io.github.jeshiomurmu.opencaller.server.lookup

import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.github.jeshiomurmu.opencaller.server.db.NumberReputation
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.http.ApiProblem
import io.github.jeshiomurmu.opencaller.server.http.requireValid
import io.github.jeshiomurmu.opencaller.server.lookup.NumberDirectory.infos
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.between
import org.jetbrains.exposed.v1.core.eq
import java.util.Base64

/** One bucket entry: the number's full SHA-256 (base64) and what is known about it. */
@Serializable
data class BucketEntry(
    val hash: String,
    val info: NumberInfo,
)

/** `GET /v1/lookup/buckets/{cc}/{bucketId}` response. */
@Serializable
data class BucketResponse(
    val countryCode: Int,
    val bucket: Int,
    val bits: Int,
    val entries: List<BucketEntry>,
)

/**
 * k-anonymous live lookup (docs/architecture.md §4.6): the client sends only the country calling code and the
 * first [bits] bits of SHA-256(E.164); the server returns every entry in that bucket with its full hash and the
 * client matches locally, so the server never learns which number was called. Buckets of 12–16 bits are served
 * from the stored 16-bit bucket (a 12-bit bucket is 16 consecutive 16-bit ones).
 */
class LookupService(
    private val db: ServerDatabase,
) {
    private val hasher = BucketHasher(BucketHasher.DEFAULT_BITS)
    private val callingCodes = PhoneNumberUtil.getInstance().supportedCallingCodes

    /** The entries of bucket [bucketId] (of width [bits]) in country calling code [countryCode], sorted by hash. */
    suspend fun bucket(
        countryCode: Int,
        bucketId: Int,
        bits: Int,
        authenticated: Boolean,
    ): BucketResponse {
        requireValid(countryCode in callingCodes) { invalidBucket("Unknown country calling code $countryCode.") }
        requireValid(
            bits in MIN_BITS..BucketHasher.DEFAULT_BITS,
        ) { invalidBucket("bits must be in $MIN_BITS..${BucketHasher.DEFAULT_BITS}.") }
        requireValid(bucketId in 0 until (1 shl bits)) { invalidBucket("bucketId must be in 0..${(1 shl bits) - 1} for $bits bits.") }
        val shift = BucketHasher.DEFAULT_BITS - bits
        val first = bucketId shl shift
        val last = first + (1 shl shift) - 1
        val found =
            db.query {
                infos(
                    (NumberReputation.countryCode eq countryCode.toShort()) and (NumberReputation.bucket16.between(first, last)),
                    authenticated,
                )
            }
        val entries =
            found
                .filterValues { !it.isEmpty }
                .map { (number, info) -> BucketEntry(Base64.getEncoder().encodeToString(hasher.digest(number)), info) }
                .sortedBy { it.hash }
        return BucketResponse(countryCode, bucketId, bits, entries)
    }

    private fun invalidBucket(detail: String) = ApiProblem(HttpStatusCode.BadRequest, "invalid_bucket", "Invalid bucket", detail)

    /** Bucket widths. */
    companion object {
        /** Smallest bucket width served (docs/architecture.md §4.6: 12–20; above 16 needs a finer stored bucket). */
        const val MIN_BITS = 12

        /** Client cache lifetime of a bucket (docs/architecture.md §4.6). */
        const val MAX_AGE_SECONDS = 86_400
    }
}
