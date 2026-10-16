package io.github.jeshiomurmu.opencaller.core.telephony.screening

import io.github.jeshiomurmu.opencaller.core.data.CachedReputation
import io.github.jeshiomurmu.opencaller.core.data.SpamListHit
import io.github.jeshiomurmu.opencaller.core.model.BlockRule
import io.github.jeshiomurmu.opencaller.core.model.PhoneNumber
import io.github.jeshiomurmu.opencaller.core.model.RuleAction
import io.github.jeshiomurmu.opencaller.core.model.RuleTarget
import io.github.jeshiomurmu.opencaller.core.model.RuleType
import io.github.jeshiomurmu.opencaller.core.model.UserPreferences

/** In-memory screening inputs; every field can be changed by a test. */
class FakeSources {
    var prefs = UserPreferences()
    val contacts = mutableMapOf<PhoneNumber, String>()
    var canReadContacts = true
    val rules = mutableListOf<BlockRule>()
    val spamList = mutableMapOf<PhoneNumber, SpamListHit>()
    val reputation = mutableMapOf<PhoneNumber, CachedReputation>()
    val verifiedBusinesses = mutableSetOf<PhoneNumber>()
    val emergencyNumbers = mutableSetOf("112", "911", "999")
    var homeRegion: String? = "BD"
    var network: NetworkLookup = NoNetworkLookup
    var rulesRead = 0
    var settingsRead = 0
    var failWith: Exception? = null

    fun device(): DeviceSources =
        DeviceSources(
            settings = {
                settingsRead++
                failWith?.let { throw it }
                prefs
            },
            contacts =
                object : ContactLookup {
                    override fun canRead(): Boolean = canReadContacts

                    override suspend fun displayName(number: PhoneNumber): String? = contacts[number].takeIf { canReadContacts }
                },
            homeRegion = { homeRegion },
            emergencyNumbers = { it in emergencyNumbers },
        )

    fun lists(): ListSources =
        ListSources(
            rules = {
                rulesRead++
                rules.toList()
            },
            verifiedBusinesses = { it in verifiedBusinesses },
            spamList = { spamList[it] },
            reputation = { reputation[it] },
            network = { network.lookup(it) },
        )

    fun rule(
        type: RuleType,
        pattern: String,
        action: RuleAction = RuleAction.BLOCK,
        enabled: Boolean = true,
    ): BlockRule = BlockRule(rules.size + 1L, type, pattern, action, RuleTarget.CALLS, 0, enabled).also { rules += it }

    fun spam(
        number: PhoneNumber,
        score: Float,
    ) {
        spamList[number] = SpamListHit(number, score, "Loan", 12)
    }
}
