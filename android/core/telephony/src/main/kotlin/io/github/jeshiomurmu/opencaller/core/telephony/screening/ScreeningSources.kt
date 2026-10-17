package io.github.jeshiomurmu.opencaller.core.telephony.screening

import io.github.jeshiomurmu.opencaller.core.common.AppError
import io.github.jeshiomurmu.opencaller.core.data.CachedReputation
import io.github.jeshiomurmu.opencaller.core.data.SpamListHit
import io.github.jeshiomurmu.opencaller.core.model.BlockRule
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences

/** The user's contacts, read on the device only. */
interface ContactLookup {
    /** True when OpenCaller may read contacts. Without it every caller counts as a non-contact. */
    fun canRead(): Boolean

    /** The name the user saved for [number], or `null` when it isn't a contact (or contacts can't be read). */
    suspend fun displayName(number: PhoneNumber): String?
}

/** Enabled rules that apply to calls. */
fun interface RuleSource {
    /** The current rules. */
    suspend fun callRules(): List<BlockRule>
}

/** The on-device spam list. */
fun interface SpamListSource {
    /** The entry for [number], or `null`. */
    suspend fun find(number: PhoneNumber): SpamListHit?
}

/** The reputation cache (TTL 24 h). */
fun interface ReputationSource {
    /** Unexpired information for [number], or `null`. */
    suspend fun find(number: PhoneNumber): CachedReputation?
}

/**
 * What a live lookup found (docs/architecture.md §4.1 step 10).
 *
 * @property spamScore community spam score in `0.0..1.0`, `null` when nobody reported the number.
 * @property fromCache true when the answer came from an earlier lookup cached on the phone.
 */
data class NetworkVerdict(
    val spamScore: Float?,
    val category: String?,
    val reportCount: Int,
    val displayName: String?,
    val nameSource: NameSource?,
    val fromCache: Boolean = false,
)

/**
 * Live k-anonymous bucket lookup (docs/architecture.md §4.6): only the country calling code and a 16-bit hash bucket
 * leave the phone. Production uses [ServerNetworkLookup]; [NoNetworkLookup] sends nothing.
 */
fun interface NetworkLookup {
    /** The server's verdict for [number], or `null` when unknown, slow or offline. */
    suspend fun lookup(number: PhoneNumber): NetworkVerdict?
}

/** No live lookups: nothing leaves the phone. */
object NoNetworkLookup : NetworkLookup {
    override suspend fun lookup(number: PhoneNumber): NetworkVerdict? = null
}

/** Whether a number belongs to a verified business (Phase 9 fills this; until then nothing is verified). */
fun interface VerifiedBusinessLookup {
    /** True when [number] is a verified business. */
    suspend fun isVerifiedBusiness(number: PhoneNumber): Boolean
}

/** The user's country, for national numbers and "foreign" checks. */
fun interface HomeRegionProvider {
    /** ISO 3166-1 alpha-2 code in upper case, or `null` when unknown (then nothing counts as foreign). */
    fun homeRegion(): String?
}

/** Emergency numbers, which are never screened. */
fun interface EmergencyNumbers {
    /** True when [handle] is an emergency number on this device. */
    fun isEmergency(handle: String): Boolean
}

/** The user's screening settings. */
fun interface ScreeningSettingsSource {
    /** The current settings. */
    suspend fun current(): UserPreferences
}

/**
 * Debug-build hooks for the emulator QA script (`scripts/qa/blocking.sh`): the emulator can't fake a SIM country or
 * a verified business. Bound only in debug builds; release builds never have them.
 */
interface ScreeningTestHooks {
    /** Home region to use instead of the SIM's, or `null` for none. */
    fun homeRegionOverride(): String?

    /** True when the QA script marked [number] as a verified business. */
    fun isVerifiedBusiness(number: PhoneNumber): Boolean
}

/** Facts about the device and the user's settings. */
class DeviceSources(
    val settings: ScreeningSettingsSource,
    val contacts: ContactLookup,
    val homeRegion: HomeRegionProvider,
    val emergencyNumbers: EmergencyNumbers,
)

/** Lists and lookups the engine consults, in decision order. */
class ListSources(
    val rules: RuleSource,
    val verifiedBusinesses: VerifiedBusinessLookup,
    val spamList: SpamListSource,
    val reputation: ReputationSource,
    val network: NetworkLookup,
)

/** A screening input could not be read; the engine lets the call ring (reason error). */
class ScreeningSourceException(
    val error: AppError,
) : RuntimeException("Screening input unavailable: $error")
