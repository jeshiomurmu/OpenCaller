package io.github.jeshiomurmu.opencaller.server.recording

import io.github.jeshiomurmu.opencaller.server.config.RecordingConfig
import io.github.jeshiomurmu.opencaller.server.db.RecordingCountries
import io.github.jeshiomurmu.opencaller.server.db.ServerDatabase
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.upsert
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** One country on the admin web's list: approved or not, why, and whether a line is configured there. */
data class RecordingCountry(
    val iso: String,
    val approved: Boolean,
    val fromAdmin: Boolean,
    val inEnvironment: Boolean,
    val hasLine: Boolean,
)

/**
 * The per-country legal switch (ADR 0009): a country is approved if `RECORDING_COUNTRIES` lists it, unless the
 * admin web switched it off; the admin web can also switch on a country the variable doesn't list (after the
 * operator's legal review, which the form asks the admin to confirm). Every change is in the audit log.
 */
class RecordingCountryPolicy(
    private val db: ServerDatabase,
    private val config: RecordingConfig,
    private val clock: Clock,
) {
    /** `true` when this server has a recording line (numbers and the agents' token). */
    val enabled: Boolean get() = config.enabled

    /** The line of country [iso], or `null` if it has none. */
    fun lineIn(iso: String): String? = config.lines[iso]

    /** `true` if recording is approved in [iso]. */
    suspend fun approved(iso: String): Boolean {
        val override =
            db.query {
                RecordingCountries
                    .selectAll()
                    .where { RecordingCountries.countryIso eq iso }
                    .singleOrNull()
                    ?.get(RecordingCountries.enabled)
            }
        return override ?: (iso in config.countries)
    }

    /** Every country the environment, the admin web or a line names, sorted. */
    suspend fun all(): List<RecordingCountry> {
        val overrides =
            db.query {
                RecordingCountries.selectAll().associate { it[RecordingCountries.countryIso] to it[RecordingCountries.enabled] }
            }
        return (config.countries + overrides.keys + config.lines.keys).sorted().map { iso ->
            RecordingCountry(
                iso = iso,
                approved = overrides[iso] ?: (iso in config.countries),
                fromAdmin = iso in overrides,
                inEnvironment = iso in config.countries,
                hasLine = iso in config.lines,
            )
        }
    }

    /** Switches [iso] on or off (the caller writes the audit log). */
    suspend fun set(
        iso: String,
        enabled: Boolean,
    ) {
        require(COUNTRY.matches(iso)) { "Not a country code" }
        db.query {
            RecordingCountries.upsert {
                it[countryIso] = iso
                it[RecordingCountries.enabled] = enabled
                it[updatedAt] = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC)
            }
        }
    }

    /** Country codes. */
    companion object {
        /** An ISO 3166-1 alpha-2 code in upper case. */
        val COUNTRY = Regex("[A-Z]{2}")
    }
}
