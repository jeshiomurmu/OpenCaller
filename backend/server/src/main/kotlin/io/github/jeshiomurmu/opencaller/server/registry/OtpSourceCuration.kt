package io.github.jeshiomurmu.opencaller.server.registry

import io.github.jeshiomurmu.opencaller.server.db.OtpAppHashes
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import io.github.jeshiomurmu.opencaller.server.db.VerificationRanges
import io.github.jeshiomurmu.opencaller.server.sync.RegistryRules
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import java.time.OffsetDateTime

/** A current SMS Retriever app hash. */
data class AppHashRow(
    val hash: String,
    val packageName: String,
    val appName: String,
    val updatedAt: OffsetDateTime,
)

/** A current verification-provider number prefix. */
data class RangeRow(
    val prefix: String,
    val provider: String,
    val updatedAt: OffsetDateTime,
)

/** What a curation change did; [Invalid] names the bad field. */
sealed interface CurationResult {
    /** Saved (added, changed or restored). */
    data object Saved : CurationResult

    /** Removed (apps drop it at their next sync). */
    data object Removed : CurationResult

    /** Nothing to remove. */
    data object NotFound : CurationResult

    /** A value has the wrong shape. */
    data class Invalid(
        val reason: String,
    ) : CurationResult
}

/**
 * The OTP sources apps use to name an OTP's sender (docs/architecture.md §4.5): SMS Retriever app hashes and the
 * number ranges flash-call verification providers call from. Curated by hand in the admin web; every change sets
 * `updated_at`, and removal keeps the row with `deleted_at`, so apps syncing with `?since=` add, change and remove
 * it too. Adding a removed value again restores it.
 */
class OtpSourceCuration(
    private val db: ServerDatabase,
    private val clock: Clock,
) {
    /** Current app hashes, by app name. */
    suspend fun appHashes(): List<AppHashRow> =
        db.query {
            OtpAppHashes
                .selectAll()
                .where { OtpAppHashes.deletedAt.isNull() }
                .orderBy(OtpAppHashes.appName to SortOrder.ASC)
                .map {
                    AppHashRow(
                        it[OtpAppHashes.hash],
                        it[OtpAppHashes.packageName],
                        it[OtpAppHashes.appName],
                        it[OtpAppHashes.updatedAt],
                    )
                }
        }

    /** Current verification ranges, by prefix. */
    suspend fun ranges(): List<RangeRow> =
        db.query {
            VerificationRanges
                .selectAll()
                .where { VerificationRanges.deletedAt.isNull() }
                .orderBy(VerificationRanges.prefix to SortOrder.ASC)
                .map { RangeRow(it[VerificationRanges.prefix], it[VerificationRanges.provider], it[VerificationRanges.updatedAt]) }
        }

    /** Adds or changes app hash [hash] of [packageName] ([appName] is what apps show). */
    suspend fun saveAppHash(
        hash: String,
        packageName: String,
        appName: String,
    ): CurationResult {
        val problem =
            when {
                !RegistryRules.APP_HASH.matches(hash) -> "The hash must be 11 characters of base64."
                !RegistryRules.PACKAGE.matches(packageName) -> "The package name isn't valid."
                !RegistryRules.isName(appName) -> "The app name must be 1 to ${RegistryRules.MAX_NAME} characters."
                else -> null
            }
        if (problem != null) return CurationResult.Invalid(problem)
        db.query {
            OtpAppHashes.upsert {
                it[OtpAppHashes.hash] = hash
                it[OtpAppHashes.packageName] = packageName
                it[OtpAppHashes.appName] = appName
                it[updatedAt] = OffsetDateTime.now(clock)
                it[deletedAt] = null
            }
        }
        return CurationResult.Saved
    }

    /** Removes app hash [hash]. */
    suspend fun removeAppHash(hash: String): CurationResult =
        db.query {
            val now = OffsetDateTime.now(clock)
            val updated =
                OtpAppHashes.update({ (OtpAppHashes.hash eq hash) and OtpAppHashes.deletedAt.isNull() }) {
                    it[updatedAt] = now
                    it[deletedAt] = now
                }
            if (updated == 1) CurationResult.Removed else CurationResult.NotFound
        }

    /** Adds or changes range [prefix] of verification provider [provider]. */
    suspend fun saveRange(
        prefix: String,
        provider: String,
    ): CurationResult {
        val problem =
            when {
                !RegistryRules.PREFIX.matches(prefix) || prefix.length < MIN_PREFIX_LENGTH -> {
                    "The prefix must be + and 4 to 15 digits (a whole country code is too wide)."
                }

                !RegistryRules.isName(provider) -> {
                    "The provider name must be 1 to ${RegistryRules.MAX_NAME} characters."
                }

                else -> {
                    null
                }
            }
        if (problem != null) return CurationResult.Invalid(problem)
        db.query {
            VerificationRanges.upsert {
                it[VerificationRanges.prefix] = prefix
                it[VerificationRanges.provider] = provider
                it[updatedAt] = OffsetDateTime.now(clock)
                it[deletedAt] = null
            }
        }
        return CurationResult.Saved
    }

    /** Removes range [prefix]. */
    suspend fun removeRange(prefix: String): CurationResult =
        db.query {
            val now = OffsetDateTime.now(clock)
            val updated =
                VerificationRanges.update({ (VerificationRanges.prefix eq prefix) and VerificationRanges.deletedAt.isNull() }) {
                    it[updatedAt] = now
                    it[deletedAt] = now
                }
            if (updated == 1) CurationResult.Removed else CurationResult.NotFound
        }

    private companion object {
        // "+" and at least 4 digits: a flash-call range must be narrower than a country code.
        const val MIN_PREFIX_LENGTH = 5
    }
}
