package io.github.jeshiomurmu.opencaller.core.telephony.screening

import io.github.jeshiomurmu.opencaller.core.common.DispatcherProvider
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.Direction
import io.github.jeshiomurmu.opencaller.core.model.NameSource
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.VerdictSource
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * What the device knows about a number for the in-call screen, without deciding anything: contacts, the spam list,
 * the reputation cache and verified businesses (the same sources as [ScreeningEngine]), never the network. Outgoing
 * calls are never looked up, and an incoming call's live lookup already ran during screening. Emergency numbers and
 * hidden callers get no number.
 */
class CallerLookup(
    private val device: DeviceSources,
    private val lists: ListSources,
    private val normalizer: PhoneNumberNormalizer,
    private val dispatchers: DispatcherProvider,
    private val config: ScreeningConfig,
) {
    private val describer = CallerDescriber(normalizer)

    /**
     * The caller card for [handle] (the number part of a call's `tel:` address). Bounded by
     * [ScreeningConfig.budgetMillis]; after a timeout or a failure only the number, its country and line type are
     * known. With [includeContacts] false the contacts are skipped: the contact detail screen shows what the spam
     * list and verified businesses say about a contact's own numbers. Never throws except to propagate the caller's
     * own cancellation.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun describe(
        handle: String?,
        includeContacts: Boolean = true,
    ): CallerInfo {
        val basic = basicCaller(handle?.trim().orEmpty())
        val number = basic.number ?: return basic
        return try {
            withTimeoutOrNull(config.budgetMillis) { withContext(dispatchers.io) { known(number, basic, includeContacts) } } ?: basic
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Caller details for the in-call screen failed")
            basic
        }
    }

    // The number, its country and line type, without any lookup; nothing for emergency numbers or a hidden caller.
    @Suppress("TooGenericExceptionCaught")
    private fun basicCaller(raw: String): CallerInfo =
        try {
            raw
                .takeIf { it.isNotEmpty() && !device.emergencyNumbers.isEmergency(it) }
                ?.let { normalizer.fromHandle(it, device.homeRegion.homeRegion() ?: UNKNOWN_REGION) }
                ?.let { describer.describe(it, ScreeningCall(raw, Direction.OUTGOING)) }
                ?: CallerInfo(number = null)
        } catch (e: RuntimeException) {
            Timber.w(e, "Could not read the number for the in-call screen")
            CallerInfo(number = null)
        }

    // A contact's own name, else the spam list, the reputation cache and verified businesses.
    private suspend fun known(
        number: PhoneNumber,
        caller: CallerInfo,
        includeContacts: Boolean,
    ): CallerInfo {
        val contact = if (includeContacts) device.contacts.displayName(number) else null
        if (contact != null) return caller.copy(displayName = contact, nameSource = NameSource.CONTACT)
        val prefs = device.settings.current()
        val listVerdict =
            lists.spamList.find(number)?.let {
                describer.verdict(it.score, it.category.ifBlank { null }, it.reportCount, prefs, VerdictSource.SPAM_LIST)
            }
        val known = KnownCaller(verified = lists.verifiedBusinesses.isVerifiedBusiness(number), cached = lists.reputation.find(number))
        return describer.enrich(caller, listVerdict, network = null, prefs = prefs, known = known)
    }

    private companion object {
        const val UNKNOWN_REGION = "ZZ"
    }
}
