package io.github.jeshiomurmu.opencaller.core.telephony.messaging

import io.github.jeshiomurmu.opencaller.core.common.getOrNull
import io.github.jeshiomurmu.opencaller.core.data.RegisteredSender
import io.github.jeshiomurmu.opencaller.core.ml.otp.OtpExtraction
import io.github.jeshiomurmu.opencaller.core.ml.sender.HeaderType
import io.github.jeshiomurmu.opencaller.core.ml.sender.SenderHeader
import io.github.jeshiomurmu.opencaller.core.ml.transaction.TransactionInfo
import io.github.jeshiomurmu.opencaller.core.model.Address
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.SenderId
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import io.github.jeshiomurmu.opencaller.core.telephony.screening.HomeRegionProvider
import javax.inject.Inject

/** The registries on the phone, as the resolver reads them (`core:data` `SenderRegistryRepository`). */
interface SenderRegistry {
    /** The registered sender behind [header] in [countryIso], or `null`. */
    suspend fun sender(
        countryIso: String,
        header: String,
    ): RegisteredSender?

    /** The app an SMS Retriever [hash] belongs to, or `null`. */
    suspend fun appForHash(hash: String): String?
}

/** Who sent a message, as far as the device can tell. [isContact] is true when the name comes from the contacts. */
data class ResolvedSender(
    val address: Address?,
    val name: String? = null,
    val source: NameSource? = null,
    val headerType: HeaderType? = null,
    val registryCategory: String? = null,
) {
    /** The sender is in the user's contacts. */
    val isContact: Boolean get() = source == NameSource.CONTACT

    /** Never prints the name or the address. */
    override fun toString(): String = "ResolvedSender(source=$source, headerType=$headerType)"
}

/** What the extractors found in the text, which some sources use (app hash, origin, the bank's template). */
data class TextHints(
    val otp: OtpExtraction? = null,
    val transaction: TransactionInfo? = null,
)

/**
 * Names the sender of an SMS on the device (docs/architecture.md §4.4 step 2), in order: the user's contact, the
 * sender-ID registry (Indian `XX-HEADER-T` headers by their header part), OTP signals (the app an SMS Retriever hash
 * belongs to, the site of an origin-bound code), then template inference (a bank whose message format matched, a
 * "Brand:" prefix). Claimed businesses, self-registered names and community labels arrive with their data (Phase 8
 * and 9). Template inference is never used for phone numbers: a scammer's "Bank:" prefix must not become a name.
 */
class SenderResolver
    @Inject
    constructor(
        private val contacts: SenderNames,
        private val registry: SenderRegistry,
        private val normalizer: PhoneNumberNormalizer,
        private val region: HomeRegionProvider,
    ) {
        /** Resolves [rawAddress] (as the network gave it; [address] is [addressOf] it) of a message with [hints]. */
        suspend fun resolve(
            rawAddress: String,
            address: Address?,
            text: String,
            hints: TextHints,
        ): ResolvedSender {
            val header = (address as? SenderId)?.let { SenderHeader.parseIndian(it.value) }
            val contact = contacts.nameOf(rawAddress)?.takeIf(String::isNotBlank)
            return when {
                contact != null -> ResolvedSender(address, contact, NameSource.CONTACT, header?.type)
                address is SenderId -> fromBusiness(address, header, text, hints)
                else -> fromOtpSignals(address, hints) ?: ResolvedSender(address)
            }
        }

        private suspend fun fromBusiness(
            address: SenderId,
            header: SenderHeader?,
            text: String,
            hints: TextHints,
        ): ResolvedSender {
            val country = if (header != null) INDIA else region.homeRegion()
            val registered = country?.let { registry.sender(it, header?.header ?: address.value) }
            return registered?.let { ResolvedSender(address, it.brandName, NameSource.SENDER_REGISTRY, header?.type, it.category) }
                ?: fromOtpSignals(address, hints)?.copy(headerType = header?.type)
                ?: inferred(address, text, hints)?.copy(headerType = header?.type)
                ?: ResolvedSender(address, headerType = header?.type)
        }

        private suspend fun fromOtpSignals(
            address: Address?,
            hints: TextHints,
        ): ResolvedSender? {
            val app = hints.otp?.appHash?.let { registry.appForHash(it) }
            return when {
                app != null -> ResolvedSender(address, app, NameSource.SENDER_REGISTRY)
                hints.otp?.origin != null -> ResolvedSender(address, hints.otp.origin, NameSource.DEVICE_INFERENCE)
                else -> null
            }
        }

        private fun inferred(
            address: Address,
            text: String,
            hints: TextHints,
        ): ResolvedSender? {
            val name =
                hints.transaction?.bank ?: BRAND_PREFIX
                    .find(text.trimStart())
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
            return name?.let { ResolvedSender(address, it, NameSource.DEVICE_INFERENCE) }
        }

        /** [raw] as a [PhoneNumber] (in the home region's format too) or a [SenderId]; `null` when it is neither. */
        fun addressOf(raw: String): Address? =
            region.homeRegion()?.let { normalizer.parseAddress(raw, it).getOrNull() }
                ?: raw.trim().takeIf(PhoneNumber::isE164)?.let(::PhoneNumber)
                ?: runCatching { SenderId(raw.trim()) }.getOrNull()

        private companion object {
            const val INDIA = "IN"

            // "Northpeak Bank: …", "Chase: …" at the very start of the message.
            val BRAND_PREFIX = Regex("""^([A-Z][A-Za-z0-9&' ]{1,28}[A-Za-z0-9]):\s""")
        }
    }
