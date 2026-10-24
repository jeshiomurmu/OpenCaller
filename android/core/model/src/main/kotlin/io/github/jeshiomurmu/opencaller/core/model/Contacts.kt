package io.github.jeshiomurmu.opencaller.core.model

/** The kind of a contact's phone number (`ContactsContract.CommonDataKinds.Phone.TYPE`, simplified). */
enum class PhoneLabel {
    /** Mobile. */
    MOBILE,

    /** Home. */
    HOME,

    /** Work. */
    WORK,

    /** Main. */
    MAIN,

    /** Anything else, including custom labels. */
    OTHER,
}

/**
 * One of a contact's phone numbers.
 *
 * @property number the number in E.164 when it could be read, `null` for short codes or unparsable entries.
 * @property dialString the number as stored in the contact (what is shown and dialled when [number] is `null`).
 */
data class ContactPhone(
    val number: PhoneNumber?,
    val dialString: String,
    val label: PhoneLabel,
)

/**
 * A contact in the list. Contacts stay in Android's contacts provider and are never synced to OpenCaller's server
 * (docs/prd.md F-CM-04).
 *
 * @property id the contact's `_ID`.
 * @property lookupKey the contact's `LOOKUP_KEY`, stable across aggregation changes.
 */
data class ContactSummary(
    val id: Long,
    val lookupKey: String,
    val name: String,
    val starred: Boolean,
    val phones: List<ContactPhone>,
)

/**
 * A contact with everything the detail screen shows.
 *
 * @property rawContactId the raw contact that edits are written to, `null` when the contact has none OpenCaller can
 *   change.
 */
data class ContactDetail(
    val id: Long,
    val lookupKey: String,
    val name: String,
    val starred: Boolean,
    val phones: List<ContactPhone>,
    val emails: List<String>,
    val rawContactId: Long?,
)

/** A phone number being entered on the create/edit screen. */
data class ContactPhoneDraft(
    val dialString: String,
    val label: PhoneLabel = PhoneLabel.MOBILE,
)

/** A new or changed contact, as entered on the create/edit screen. */
data class ContactDraft(
    val name: String = "",
    val phones: List<ContactPhoneDraft> = listOf(ContactPhoneDraft("")),
    val emails: List<String> = emptyList(),
) {
    /** The phone numbers that aren't blank. */
    val filledPhones: List<ContactPhoneDraft> get() = phones.filter { it.dialString.isNotBlank() }

    /** The e-mail addresses that aren't blank. */
    val filledEmails: List<String> get() = emails.map(String::trim).filter(String::isNotEmpty)

    /** True when there is something to save: a name or at least one number. */
    val canSave: Boolean get() = name.isNotBlank() || filledPhones.isNotEmpty()
}
