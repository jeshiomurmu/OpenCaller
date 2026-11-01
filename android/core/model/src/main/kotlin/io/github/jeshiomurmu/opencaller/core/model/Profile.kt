package io.github.jeshiomurmu.opencaller.core.model

/** Who sees a self-registered name in caller ID and search (docs/prd.md F-SO-01). */
enum class ProfileVisibility {
    /** Anyone, also people who aren't signed in. */
    EVERYONE,

    /** Signed-in OpenCaller users (the default). */
    REGISTERED_ONLY,

    /** Nobody; others can ask with a contact request. */
    NOBODY,
}

/** A supporter's badge on their caller card and profile (docs/prd.md F-SO-06). */
enum class SupporterBadge {
    SUPPORTER,
    GOLD,
    ;

    /** Parsing the server's (`supporter`, `gold`) and the cache's (`SUPPORTER`, `GOLD`) spelling. */
    companion object {
        /** The badge named [value] (any case), or `null` for none or an unknown one. */
        fun parse(value: String?): SupporterBadge? = entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
    }
}

/**
 * The signed-in user's own profile.
 *
 * @property avatarKey the picture's storage key (sent back unchanged when only other fields change).
 * @property avatarUrl a download link for the picture, valid about 15 minutes.
 * @property supporter true when "who viewed / searched" are unlocked.
 * @property viewerCount profile views of the last 90 days; [searcherCount] likewise for searches.
 */
data class MyProfile(
    val number: PhoneNumber,
    val displayName: String?,
    val avatarKey: String?,
    val avatarUrl: String?,
    val visibility: ProfileVisibility,
    val incognito: Boolean,
    val badge: SupporterBadge?,
    val supporter: Boolean,
    val viewerCount: Int,
    val searcherCount: Int,
    val pendingContactRequests: Int,
)

/** Profile views or searches of one's number (docs/prd.md F-SO-02/03). */
enum class VisitKind {
    VIEW,
    SEARCH,
}

/** One visit: the visitor's name only if their visibility allows it (never their number). */
data class ProfileVisit(
    val name: String?,
    val badge: SupporterBadge?,
    val atMillis: Long,
)

/** The visits of the last [days] days; [total] counts all of them, [items] lists the newest. */
data class ProfileVisits(
    val kind: VisitKind,
    val days: Int,
    val total: Int,
    val items: List<ProfileVisit>,
)

/** Someone asking for the signed-in user's name (docs/prd.md F-SO-05); asking shares their number and name. */
data class ContactRequest(
    val id: Long,
    val requester: PhoneNumber,
    val requesterName: String?,
    val createdAtMillis: Long,
)

/** What the signed-in user may see of another number's profile. */
data class PublicProfile(
    val number: PhoneNumber,
    val name: String?,
    val avatarUrl: String?,
    val badge: SupporterBadge?,
)
