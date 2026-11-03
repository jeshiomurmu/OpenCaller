package io.github.jeshiomurmu.opencaller.feature.messages.ui

import io.github.jeshiomurmu.opencaller.core.model.MessageCategory
import io.github.jeshiomurmu.opencaller.core.model.MessageMeta
import io.github.jeshiomurmu.opencaller.core.model.MessageRisk
import io.github.jeshiomurmu.opencaller.core.model.NameSource

/** The smart inbox's tabs (PRD F-SMS-03) and the categories each shows. Spam is the spam folder. */
enum class InboxTab(
    val categories: Set<MessageCategory>,
) {
    /** People, and anything the classifier left Personal. */
    PERSONAL(setOf(MessageCategory.PERSONAL)),

    /** One-time codes. */
    OTP(setOf(MessageCategory.OTP)),

    /** Bank and wallet transactions. */
    TRANSACTIONS(setOf(MessageCategory.TRANSACTION)),

    /** Bills and payment reminders. */
    BILLS(setOf(MessageCategory.BILL)),

    /** Orders and parcels. */
    DELIVERIES(setOf(MessageCategory.DELIVERY)),

    /** Offers and sales. */
    PROMOTIONS(setOf(MessageCategory.PROMOTION)),

    /** Spam and phishing (the spam folder). */
    SPAM(setOf(MessageCategory.SPAM, MessageCategory.PHISHING)),
    ;

    /** The tab of each category. */
    companion object {
        /** The tab [category] is shown in. */
        fun of(category: MessageCategory): InboxTab = entries.first { category in it.categories }
    }
}

/** A tab and how many unread conversations it holds. */
data class TabSummary(
    val tab: InboxTab,
    val unread: Int,
)

/**
 * What the inbox knows about a conversation from OpenCaller's metadata: its category, whether a message is risky,
 * and the sender's name with its source as the newest named message had it (contacts are read again on screen, so
 * a stored contact name is never used).
 */
data class ThreadFacts(
    val category: MessageCategory = MessageCategory.PERSONAL,
    val risky: Boolean = false,
    val senderName: String? = null,
    val senderSource: NameSource? = null,
)

/** The newest code that hasn't expired, for the OTP tab's card. */
data class OtpCardState(
    val code: String,
    val from: String?,
    val expiresAtMillis: Long?,
) {
    /** Never prints the code. */
    override fun toString(): String = "OtpCardState(expiresAtMillis=$expiresAtMillis)"
}

/**
 * Each conversation's category is the one of its newest received message that OpenCaller classified (one thread per
 * address, so a bank's thread stays a bank's). A conversation is risky when any of its messages had a link risk or
 * arrived as a code during a call with a stranger.
 */
internal fun threadFacts(metas: List<MessageMeta>): Map<Long, ThreadFacts> =
    metas.groupBy { it.threadId }.mapValues { (_, rows) ->
        val risks = MessageRisk.LINK_RISKS + MessageRisk.OTP_DURING_CALL
        val named = rows.filter { it.senderName != null && it.senderSource != null && it.senderSource != NameSource.CONTACT }
        val newestNamed = named.maxByOrNull { it.atMillis }
        ThreadFacts(
            category = rows.maxBy { it.atMillis }.category,
            risky = rows.any { row -> MessageRisk.fromBits(row.riskFlags).any { it in risks } },
            senderName = newestNamed?.senderName,
            senderSource = newestNamed?.senderSource,
        )
    }

/** The unread conversations per tab, every tab listed. */
internal fun tabSummaries(rows: List<ConversationRow>): List<TabSummary> =
    InboxTab.entries.map { tab -> TabSummary(tab, rows.count { InboxTab.of(it.category) == tab && it.unread > 0 }) }

/** The newest code in [metas] that is still valid at [nowMillis] (a code without an expiry counts for 10 minutes). */
internal fun currentOtp(
    metas: List<MessageMeta>,
    nowMillis: Long,
): OtpCardState? =
    metas
        .filter { it.otp != null }
        .maxByOrNull { it.atMillis }
        ?.takeIf { meta -> nowMillis < (meta.otp?.expiresAtMillis ?: (meta.atMillis + DEFAULT_OTP_MILLIS)) }
        ?.let { meta -> meta.otp?.let { OtpCardState(it.code, meta.senderName, it.expiresAtMillis) } }

private const val DEFAULT_OTP_MILLIS = 10 * 60 * 1000L
