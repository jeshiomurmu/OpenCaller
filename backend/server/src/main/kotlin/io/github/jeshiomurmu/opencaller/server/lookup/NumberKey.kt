package io.github.jeshiomurmu.opencaller.server.lookup

import com.google.i18n.phonenumbers.PhoneNumberUtil
import io.github.jeshiomurmu.opencaller.server.db.NumberReputation
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.batchInsert

/** Where a number lives in the lookup index: its country calling code and 16-bit bucket (docs/architecture.md §4.6). */
data class NumberKey(
    val e164: String,
    val countryCode: Int,
    val bucket16: Int,
) {
    /** Computes keys from validated E.164 strings. */
    companion object {
        private val util = PhoneNumberUtil.getInstance()
        private val hasher = BucketHasher(BucketHasher.DEFAULT_BITS)

        /** Key of the E.164 number [e164] (already validated, so parsing can't fail). */
        fun of(e164: String): NumberKey = NumberKey(e164, util.parse(e164, null).countryCode, hasher.bucket(e164))
    }
}

/**
 * Makes sure each of [numbers] has a `number_reputation` row (with no reports yet), which is the per-number index
 * bucket lookups read: signed-up users and business numbers must be findable by bucket even before any report.
 */
fun JdbcTransaction.ensureIndexed(numbers: Collection<String>) {
    if (numbers.isEmpty()) return
    NumberReputation.batchInsert(numbers.map(NumberKey::of), ignore = true, shouldReturnGeneratedValues = false) { key ->
        this[NumberReputation.e164] = key.e164
        this[NumberReputation.countryCode] = key.countryCode.toShort()
        this[NumberReputation.bucket16] = key.bucket16
    }
}
