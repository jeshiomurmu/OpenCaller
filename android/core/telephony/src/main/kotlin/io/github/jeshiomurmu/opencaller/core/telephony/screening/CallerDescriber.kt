package io.github.jeshiomurmu.opencaller.core.telephony.screening

import io.github.jeshiomurmu.opencaller.core.data.CachedReputation
import io.github.jeshiomurmu.opencaller.core.model.CallerInfo
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.SpamVerdict
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences
import io.github.jeshiomurmu.opencaller.core.model.VerdictSource
import io.github.jeshiomurmu.opencaller.core.phonenumber.PhoneNumberNormalizer

/** What the device already knows about a number: verified business (step 7) and the reputation cache (step 9). */
internal class KnownCaller(
    val verified: Boolean,
    val cached: CachedReputation?,
)

/** What [ScreeningEngine] knows about a caller: country, line type, spam verdict and the caller card details. */
internal class CallerDescriber(
    private val normalizer: PhoneNumberNormalizer,
) {
    // Step 6: a number is allowed when its country is on the user's list.
    fun isForeign(
        number: PhoneNumber,
        region: String,
        prefs: UserPreferences,
    ): Boolean =
        normalizer.isForeign(number, region) &&
            normalizer.countryOf(number).let { country ->
                country == null || prefs.allowedRegionCodes.none { it.equals(country, ignoreCase = true) }
            }

    // Steps 9–11: the reputation cache, the live lookup and STIR annotate the card; the spam list verdict wins, then
    // the stronger of the others.
    fun enrich(
        caller: CallerInfo,
        found: SpamVerdict?,
        network: NetworkVerdict?,
        prefs: UserPreferences,
        known: KnownCaller,
    ): CallerInfo {
        val cached = known.cached
        val verdict =
            found
                ?: cached?.let { c ->
                    c.spamScore?.let { verdict(it, c.spamCategory, c.reportCount, prefs, VerdictSource.REPUTATION_CACHE) }
                }
        val named =
            network?.let { n -> n.displayName?.let { it to n.nameSource } }
                ?: cached?.let { c -> c.displayName?.let { it to c.nameSource } }
        return caller.copy(
            displayName = named?.first,
            nameSource = named?.second,
            spamVerdict = verdict,
            verifiedBusinessId = cached?.verifiedBusinessId ?: VERIFIED_WITHOUT_ID.takeIf { known.verified },
            logoUrl = cached?.logoUrl,
            callReason = cached?.callReason,
            lineType = cached?.lineType ?: caller.lineType,
        )
    }

    fun verdict(
        score: Float,
        category: String?,
        reportCount: Int,
        prefs: UserPreferences,
        source: VerdictSource,
    ) = SpamVerdict(
        score.coerceIn(0f, 1f),
        SpamVerdict.levelFor(score, prefs.spamThreshold),
        category,
        reportCount.coerceAtLeast(0),
        source,
    )

    // Step 10's answer as a verdict (none without a score: a name alone is no spam signal).
    fun networkVerdict(
        network: NetworkVerdict?,
        prefs: UserPreferences,
    ): SpamVerdict? =
        network?.spamScore?.let { score ->
            val source = if (network.fromCache) VerdictSource.REPUTATION_CACHE else VerdictSource.LIVE_LOOKUP
            verdict(score, network.category, network.reportCount, prefs, source)
        }

    // Step 1 plus step 11: number, country, line type and the STIR/SHAKEN verdict.
    fun describe(
        number: PhoneNumber?,
        call: ScreeningCall,
    ): CallerInfo =
        CallerInfo(
            number = number,
            regionCode = number?.let(normalizer::countryOf),
            lineType = number?.let(normalizer::numberType),
            verification = call.verification,
        )

    private companion object {
        // Phase 9 provides business ids; until then a verified business found by the lookup has none.
        const val VERIFIED_WITHOUT_ID = "verified"
    }
}
